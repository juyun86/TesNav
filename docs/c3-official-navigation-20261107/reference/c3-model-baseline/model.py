# Derived from commaai/openpilot (MIT) and sunnypilot (MIT); see NOTICE.md.
from collections.abc import Callable
import base64
import math
import os
import pickle
import numpy as np
from tinygrad.device import Buffer
from tinygrad.dtype import DType, dtypes, _from_np_dtype
from tinygrad.engine.realize import lower_and_compile
from tinygrad.tensor import Tensor
from tinygrad.helpers import round_up
from tinygrad.uop.ops import UOp
from tinygrad_repo.examples.openpilot.helpers import load_pickle
from openpilot.system.camerad.cameras.nv12_info import get_nv12_info
from openpilot.selfdrive.modeld.parse_model_outputs import Parser
from openpilot.selfdrive.modeld.constants import ModelConstants
from msgq.visionipc import VisionBuf
from .assets import directory

SEND_RAW_PRED = os.getenv('SEND_RAW_PRED')
MODELS_DIR = directory() / 'models'
MAX_NONFINITE_RECOVERIES = 3  # consecutive bad frames before giving the model up

def input_view(buffer: Buffer, shape: tuple[int, ...], dtype: DType, offset: int) -> Tensor:
  view = buffer.view(math.prod(shape) * dtype.itemsize, offset).ensure_allocated()
  return Tensor(UOp.from_buffer(view, dtype)).reshape(shape)


class ModelState:
  prev_desire: np.ndarray  # for tracking the rising edge of the pulse

  def __init__(self, cam_w: int, cam_h: int):
    from tinygrad.runtime.ops_amd import AMDDevice
    AMDDevice.wait_timeout_ms = 3000  # Same wait bound as the official eGPU loop.
    chestnut = True
    jits = load_pickle(MODELS_DIR / 'big_driving_tinygrad.pkl', out_of_band=True)
    self.model_device = jits['input_specs']['new_img'][2]
    self.input_shapes = {name: (shape, np.dtype(dtype)) for name, (shape, dtype, _) in jits['input_specs'].items()}
    self.state_pairs = {name: f'next_{name}' for name in self.input_shapes if f'next_{name}' in jits['metadata']['output_shapes']}
    self.vision_input_names = ('img', 'big_img')
    self.output_slices = pickle.loads(base64.b64decode(jits['metadata']['metadata']['output_slices']))

    self.navigation_present = False
    self.nav_suspended = False  # drop navigation after a non-finite frame instead of dying
    self.nonfinite_frames = 0
    self.prev_desire = np.zeros(ModelConstants.DESIRE_LEN, dtype=np.float32)
    self.chestnut = chestnut

    stride, y_height, uv_height, _ = get_nv12_info(cam_w, cam_h)
    self.frame_copy_size = stride * (y_height + uv_height)
    self.pack_inputs()
    with open(MODELS_DIR / f'{"big_" if chestnut else ""}driving_warp_{cam_w}x{cam_h}_tinygrad.pkl', 'rb') as f:
      self.run_warp = pickle.load(f)['run']
    self.run_warp.captured._linear = lower_and_compile(self.run_warp.captured._linear)
    self.run_model = jits['run']
    self.run_model.captured._linear = lower_and_compile(self.run_model.captured._linear)
    self.outputs = {name: Tensor(np.zeros(shape, dtype=dtype), device=device).realize() for name, (shape, dtype, device) in jits['output_specs'].items()}
    for name, next_name in self.state_pairs.items():
      state = self.input_queues[name]
      self.outputs[next_name] = input_view(state._buffer(), state.shape, state.dtype, 0)
    self.parser = Parser()

  def pack_inputs(self) -> None:
    # Pack host inputs into one upload to reduce USB transfer overhead for the eGPU.
    self.input_queues = {name: Tensor(np.zeros(shape, dtype=dtype), device=self.model_device).realize()
                         for name, (shape, dtype) in self.input_shapes.items() if name in self.state_pairs}
    shapes = {'tfm': ((2, 3, 3), np.dtype(np.float32))} | {name: spec for name, spec in self.input_shapes.items()
                                                          if name not in self.state_pairs and name != 'new_img'}
    npy_size = sum(round_up(math.prod(shape) * dtype.itemsize, 128) for shape, dtype in shapes.values())
    self.packed_input = np.zeros(npy_size + 2 * self.frame_copy_size, dtype=np.uint8)
    self.input_host = Tensor(self.packed_input, device='NPY')._buffer()
    self.input_device = Tensor(self.packed_input, device=self.model_device)._buffer()
    self.npy = {}
    offset = 0
    for name, (shape, dtype) in shapes.items():
      self.npy[name] = np.ndarray(shape, dtype=dtype, buffer=self.packed_input, offset=offset)
      self.input_queues[name] = input_view(self.input_device, shape, _from_np_dtype(dtype), offset)
      offset += round_up(self.npy[name].nbytes, 128)
    self.frames = self.packed_input[npy_size:].reshape(2, self.frame_copy_size)
    self.warp_inputs = {'input_frame': input_view(self.input_device, self.frames.shape, dtypes.uint8, npy_size), 'M_inv': self.input_queues.pop('tfm')}

  def slice_outputs(self, model_outputs: np.ndarray, output_slices: dict[str, slice]) -> dict[str, np.ndarray]:
    return {k: model_outputs[np.newaxis, v] for k,v in output_slices.items()}

  def run(self, bufs: dict[str, VisionBuf], transforms: dict[str, np.ndarray],
          inputs: dict[str, np.ndarray], after_enqueue: Callable[[], None] | None = None) -> dict[str, np.ndarray]:
    for i, key in enumerate(self.vision_input_names):
      np.copyto(self.frames[i], np.frombuffer(bufs[key].data, dtype=np.uint8, count=self.frame_copy_size))
      self.npy['tfm'][i] = transforms[key]

    # Model decides when action is completed, so desire input is just a pulse triggered on rising edge
    inputs['desire_pulse'][0] = 0
    self.npy['desire'][:] = np.where(inputs['desire_pulse'] - self.prev_desire > .99, inputs['desire_pulse'], 0)
    self.prev_desire[:] = inputs['desire_pulse']
    self.npy['traffic_convention'][:] = inputs['traffic_convention']
    self.npy['action_t'][:] = inputs['action_t']
    if inputs.get('nav_reset', False):
      self.nav_suspended = False
    # Official nav-modeld writes the map/route every frame (zeros when absent) and never
    # resets the navigation state queues on its own; only a NaN frame suspends them here.
    navigation_present = 'nav_map' in inputs and 'navigation' in inputs and not self.nav_suspended
    self.navigation_present = navigation_present
    for name in ('nav_map', 'navigation'):
      if name in self.npy:
        self.npy[name][:] = inputs.get(name, 0) if navigation_present else 0

    self.input_device.copy_from(self.input_host)
    self.input_queues['new_img'] = self.run_warp(**self.warp_inputs)
    self.run_model(output_buffers=self.outputs, **self.input_queues)
    if after_enqueue is not None:
      after_enqueue()
    model_output = self.outputs['outputs'].numpy()[0]
    if self.chestnut and not np.all(np.isfinite(model_output)):
      return self._nonfinite(model_output, inputs)
    self.nonfinite_frames = 0
    outputs_dict = self.parser.parse_outputs(self.slice_outputs(model_output, self.output_slices))

    if SEND_RAW_PRED:
      outputs_dict['raw_pred'] = model_output.copy()
    return outputs_dict

  def reset_state(self) -> None:
    """Drop the recurrent state so one bad frame cannot poison every later frame."""
    for name in self.state_pairs:
      self.input_queues[name].assign(0).realize()
    self.prev_desire[:] = 0

  def _nonfinite(self, model_output, inputs):
    from .fault_capture import capture
    try:
      capture(self, model_output, inputs)
    except Exception as error:
      print('NAV_MODEL_CAPTURE_FAILED', type(error).__name__, flush=True)
    if self.navigation_present and not self.nav_suspended:
      # Every crash so far had finite map/route arrays, so the navigation branch is the suspect:
      # retry vision-only instead of handing the publisher back to the stock model.
      self.nav_suspended = True
      self.nonfinite_frames = 0
      print('NAV_MODEL_NAV_SUSPENDED', flush=True)
      self.reset_state()
      return None
    self.nonfinite_frames += 1
    print('NAV_MODEL_NONFINITE frames=%d' % self.nonfinite_frames, flush=True)
    self.reset_state()
    if self.nonfinite_frames > MAX_NONFINITE_RECOVERIES:
      raise RuntimeError("model output not finite")
    return None

  def warmup(self) -> None:
    dummy_frames = {k: np.zeros(self.frame_copy_size, dtype=np.uint8) for k in self.vision_input_names}
    eye = np.eye(3, dtype=np.float32)
    dims = {'desire_pulse': ModelConstants.DESIRE_LEN, 'traffic_convention': 2, 'action_t': 2}
    self.run(dummy_frames, dict.fromkeys(self.vision_input_names, eye), {k: np.zeros(v, dtype=np.float32) for k, v in dims.items()})
    self.packed_input[:] = 0
    for key in self.state_pairs:
      self.input_queues[key].assign(0).realize()
    self.prev_desire[:] = 0
