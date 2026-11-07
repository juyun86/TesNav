# Derived from commaai/openpilot (MIT) and sunnypilot (MIT); see NOTICE.md.
import gc
import time
import numpy as np
from openpilot.cereal import messaging, log
from openpilot.cereal.messaging import PubMaster, SubMaster
from openpilot.cereal.services import SERVICE_LIST
from openpilot.cereal.visionipc import VisionStreamType
from msgq.visionipc import VisionIpcClient
from opendbc.car.structs import car
from opendbc.car.car_helpers import get_demo_car_params
from openpilot.common.params import Params
from openpilot.common.swaglog import cloudlog
from openpilot.common.filter_simple import FirstOrderFilter
from openpilot.common.realtime import config_realtime_process, DT_MDL
from openpilot.common.transformations.camera import DEVICE_CAMERAS
from openpilot.common.transformations.model import get_warp_matrix
from openpilot.selfdrive.controls.lib.desire_helper import DesireHelper
from openpilot.selfdrive.modeld.fill_model_msg import fill_model_msg, fill_driving_model_data, fill_pose_msg, PublishState
from openpilot.selfdrive.modeld.constants import ModelConstants
from openpilot.selfdrive.modeld.helpers import wait_for_chestnut
from openpilot.sunnypilot.livedelay.helpers import get_lat_delay
from openpilot.sunnypilot.selfdrive.controls.lib.relc import RoadEdgeLaneChangeController
from .model import ModelState
from .navigation import NavigationReader
from .blinker_publisher import BlinkerPublisher, KIND as BLINKER_KIND
from .plugin import NavigationModel
from .support import FrameMeta, ChestnutGpuState, get_action_from_model, LAT_SMOOTH_SECONDS, LONG_SMOOTH_SECONDS

NEAR_SEEN = set()
NAV_LOG_AT = [0.0]

def main(demo=False, *, ready=lambda: None):
  params = Params()
  gc.disable()
  navigation = NavigationReader(params)
  from openpilot.tesla.plugins import send_msg
  def send_blinker(payload, ttl):
    try:
      return send_msg(NavigationModel, BLINKER_KIND, payload, valid_for_ms=ttl)
    except OSError:
      return False
  blinker_publisher = BlinkerPublisher(send_blinker)
  # visionipc clients
  while True:
    available_streams = VisionIpcClient.available_streams("camerad", block=False)
    if available_streams:
      use_extra_client = VisionStreamType.VISION_STREAM_WIDE_ROAD in available_streams and VisionStreamType.VISION_STREAM_NARROW_ROAD in available_streams
      main_wide_camera = VisionStreamType.VISION_STREAM_NARROW_ROAD not in available_streams
      break
    time.sleep(.1)

  vipc_client_main_stream = VisionStreamType.VISION_STREAM_WIDE_ROAD if main_wide_camera else VisionStreamType.VISION_STREAM_NARROW_ROAD
  vipc_client_main = VisionIpcClient("camerad", vipc_client_main_stream, True)
  vipc_client_extra = VisionIpcClient("camerad", VisionStreamType.VISION_STREAM_WIDE_ROAD, False)
  cloudlog.warning(f"vision stream set up, main_wide_camera: {main_wide_camera}, use_extra_client: {use_extra_client}")

  while not vipc_client_main.connect(False):
    time.sleep(0.1)
  while use_extra_client and not vipc_client_extra.connect(False):
    time.sleep(0.1)

  cloudlog.warning(f"connected main cam with buffer size: {vipc_client_main.buffer_len} ({vipc_client_main.width} x {vipc_client_main.height})")
  if use_extra_client:
    cloudlog.warning(f"connected extra cam with buffer size: {vipc_client_extra.buffer_len} ({vipc_client_extra.width} x {vipc_client_extra.height})")

  wait_for_chestnut()
  model = ModelState(vipc_client_main.width, vipc_client_main.height)
  model.warmup()
  ready_reported = False

  config_realtime_process(7, 54)

  # messaging
  pub_socks = ["modelV2", "drivingModelData", "cameraOdometry", "modelDataV2SP"] + ["chestnutGpuState"]
  pm = PubMaster(pub_socks)
  sm = SubMaster(["deviceState", "carState", "narrowRoadCameraState", "extrinsicsCalibration", "driverMonitoringState",
                  "carControl", "controlsState", "lateralDelay"])

  publish_state = PublishState()
  params = Params()
  chestnut_state = ChestnutGpuState(pm, True)

  # setup filter to track dropped frames
  frame_dropped_filter = FirstOrderFilter(0., 10., 1. / ModelConstants.MODEL_RUN_FREQ)
  frame_id = 0
  last_vipc_frame_id = 0
  run_count = 0

  model_transform_main = np.zeros((3, 3), dtype=np.float32)
  model_transform_extra = np.zeros((3, 3), dtype=np.float32)
  extrinsics_calibration_seen = False
  buf_main, buf_extra = None, None
  meta_main = FrameMeta()
  meta_extra = FrameMeta()

  if demo:
    CP = get_demo_car_params()
  else:
    CP = messaging.log_from_bytes(params.get("CarParams", block=True), car.CarParams)
  cloudlog.info("modeld got CarParams: %s", CP.brand)

  # TODO this needs more thought, use .2s extra for now to estimate other delays
  # TODO Move smooth seconds to action function
  long_delay = CP.longitudinalActuatorDelay + LONG_SMOOTH_SECONDS
  prev_action = log.ModelDataV2.Action()

  DH = DesireHelper()
  RELC = RoadEdgeLaneChangeController()

  while True:
    # Keep receiving frames until we are at least 1 frame ahead of previous extra frame
    while meta_main.timestamp_sof < meta_extra.timestamp_sof + 25000000:
      buf_main = vipc_client_main.recv()
      meta_main = FrameMeta(vipc_client_main)
      if buf_main is None:
        break

    if buf_main is None:
      cloudlog.debug("vipc_client_main no frame")
      continue

    if use_extra_client:
      # Keep receiving extra frames until frame id matches main camera
      while True:
        buf_extra = vipc_client_extra.recv()
        meta_extra = FrameMeta(vipc_client_extra)
        if buf_extra is None or meta_main.timestamp_sof < meta_extra.timestamp_sof + 25000000:
          break

      if buf_extra is None:
        cloudlog.debug("vipc_client_extra no frame")
        continue

      if abs(meta_main.timestamp_sof - meta_extra.timestamp_sof) > 10000000:
        cloudlog.error(f"frames out of sync! main: {meta_main.frame_id} ({meta_main.timestamp_sof / 1e9:.5f}),\
                         extra: {meta_extra.frame_id} ({meta_extra.timestamp_sof / 1e9:.5f})")

    else:
      # Use single camera
      buf_extra = buf_main
      meta_extra = meta_main

    sm.update(0)
    desire = DH.desire
    is_rhd = sm["driverMonitoringState"].isRHD
    frame_id = sm["narrowRoadCameraState"].frameId
    v_ego = max(sm["carState"].vEgo, 0.)
    model.lat_delay = get_lat_delay(params, sm["lateralDelay"].lateralDelay)
    lat_delay = sm["lateralDelay"].lateralDelay + LAT_SMOOTH_SECONDS
    if sm.updated["extrinsicsCalibration"] and sm.seen['narrowRoadCameraState'] and sm.seen['deviceState']:
      device_from_calib_euler = np.array(sm["extrinsicsCalibration"].rpyCalib, dtype=np.float32)
      dc = DEVICE_CAMERAS[(str(sm['deviceState'].deviceType), str(sm['narrowRoadCameraState'].sensor))]
      main_intrinsics = dc.wide_road.intrinsics if main_wide_camera else dc.narrow_road.intrinsics
      model_transform_main = get_warp_matrix(device_from_calib_euler, main_intrinsics, False).astype(np.float32)
      has_wide_camera = use_extra_client or main_wide_camera
      extra_intrinsics = dc.wide_road.intrinsics if has_wide_camera else dc.narrow_road.intrinsics
      model_transform_extra = get_warp_matrix(device_from_calib_euler, extra_intrinsics, True).astype(np.float32)
      extrinsics_calibration_seen = True

    traffic_convention = np.zeros(2)
    traffic_convention[int(is_rhd)] = 1

    vec_desire = np.zeros(ModelConstants.DESIRE_LEN, dtype=np.float32)
    if desire >= 0 and desire < ModelConstants.DESIRE_LEN:
      vec_desire[desire] = 1

    # tracked dropped frames
    vipc_dropped_frames = max(0, meta_main.frame_id - last_vipc_frame_id - 1)
    frames_dropped = frame_dropped_filter.update(min(vipc_dropped_frames, 10))
    if run_count < 10: # let frame drops warm up
      frame_dropped_filter.x = 0.
      frames_dropped = 0.
    run_count = run_count + 1

    frame_drop_ratio = frames_dropped / (1 + frames_dropped)

    bufs = {name: buf_extra if 'big' in name else buf_main for name in model.vision_input_names}
    transforms = {name: model_transform_extra if 'big' in name else model_transform_main for name in model.vision_input_names}
    frame_delay = DT_MDL # compensate for time passed since the frame was captured: current_time - timestamp_eof is 50ms on average
    action_delay = DT_MDL / 2 # middle of the interval between model output (current state) and next frame (expected state)
    lat_action_t = lat_delay + frame_delay + action_delay
    long_action_t = long_delay + frame_delay + action_delay
    inputs: dict[str, np.ndarray] = {
      'desire_pulse': vec_desire,
      'traffic_convention': traffic_convention,
      'action_t': np.array([lat_action_t, long_action_t], dtype=np.float32),
    }

    inputs.update(navigation.read())
    if time.monotonic() - NAV_LOG_AT[0] > 1.0:
      NAV_LOG_AT[0] = time.monotonic()
      nav_vec = inputs.get('navigation')
      try:
        with open('/data/nav-work/nav_present.log', 'a') as f:
          if nav_vec is None:
            f.write('%s present=0\n' % time.strftime('%H:%M:%S'))
          else:
            f.write('%s present=1 cmd=%d dist=%.0f reset=%s\n' % (
                time.strftime('%H:%M:%S'), int(np.argmax(nav_vec[0, :46])), float(nav_vec[0, 46]) * 100.0,
                bool(inputs.get('nav_reset'))))
      except OSError:
        pass
    nav_vec = inputs.get('navigation')
    near = None
    if nav_vec is not None:
      cmd = int(np.argmax(nav_vec[0, :46]))
      dist_m = float(nav_vec[0, 46]) * 100.0
      if cmd in (9, 10, 11, 13, 14, 15, 16) and 0 <= dist_m < 200.0:
        near = (cmd, dist_m)
        bucket = (cmd, int(dist_m // 10))
        if bucket not in NEAR_SEEN:
          NEAR_SEEN.add(bucket)
          try:
            with open('/data/nav-work/near_turn.log', 'a') as f:
              f.write('%s cmd=%d dist=%.0f\n' % (time.strftime('%H:%M:%S'), cmd, dist_m))
          except OSError:
            pass
    mt1 = time.perf_counter()
    send_chestnut = run_count % round(ModelConstants.MODEL_RUN_FREQ / SERVICE_LIST['chestnutGpuState'].frequency) == 0
    model_output = model.run(bufs, transforms, inputs, chestnut_state.send if send_chestnut else None)
    mt2 = time.perf_counter()
    model_execution_time = mt2 - mt1

    if model_output is not None:
      if not ready_reported:
        ready()
        ready_reported = True
      selected_big = model.chestnut
      modelv2_send = messaging.new_message('modelV2')
      drivingdata_send = messaging.new_message('drivingModelData')
      posenet_send = messaging.new_message('cameraOdometry')
      mdv2sp_send = messaging.new_message('modelDataV2SP')

      if not sm['carControl'].latActive:
        prev_action.desiredCurvature = sm['controlsState'].curvature
      action = get_action_from_model(model_output, prev_action, lat_action_t, long_action_t, v_ego)
      if near is not None:
        try:
          with open('/data/nav-work/near_turn.log', 'a') as f:
            f.write('%s   curv=%.5f cmd=%d dist=%.0f\n' % (time.strftime('%H:%M:%S'), action.desiredCurvature, near[0], near[1]))
        except OSError:
          pass
      prev_action = action
      fill_model_msg(modelv2_send, model_output, action,
                     publish_state, meta_main.frame_id, meta_extra.frame_id, frame_id,
                     frame_drop_ratio, meta_main.timestamp_eof, model_execution_time, extrinsics_calibration_seen)
      modelv2_send.modelV2.big = selected_big
      blinker_publisher.update(model_output, frame_id=int(meta_main.frame_id),
                               source_ns=int(meta_main.timestamp_eof), now_ns=time.monotonic_ns(),
                               navigation_present=bool(model.navigation_present and not model.nav_suspended),
                               output_valid=bool(modelv2_send.valid and selected_big),
                               navigation_identity=navigation.identity)

      desire_state = modelv2_send.modelV2.meta.desireState
      l_lane_change_prob = desire_state[log.Desire.laneChangeLeft]
      r_lane_change_prob = desire_state[log.Desire.laneChangeRight]
      lane_change_prob = l_lane_change_prob + r_lane_change_prob
      left_edge, right_edge = RELC.update_and_fill(modelv2_send.modelV2, mdv2sp_send.modelDataV2SP, v_ego)
      DH.update(sm['carState'], sm['carControl'].latActive, lane_change_prob, left_edge, right_edge)
      modelv2_send.modelV2.meta.laneChangeState = DH.lane_change_state
      modelv2_send.modelV2.meta.laneChangeDirection = DH.lane_change_direction
      mdv2sp_send.valid = modelv2_send.valid
      mdv2sp_send.modelDataV2SP.laneTurnDirection = DH.lane_turn_direction

      fill_driving_model_data(drivingdata_send, modelv2_send)
      fill_pose_msg(posenet_send, model_output, meta_main.frame_id, vipc_dropped_frames, meta_main.timestamp_eof, extrinsics_calibration_seen)
      pm.send('modelV2', modelv2_send)
      pm.send('drivingModelData', drivingdata_send)
      # A dropped frame marks the pose invalid (fill_pose_msg: dropped >= 1). Publishing that makes
      # locationd see an invalid input for one cycle, which raises locationdTemporaryError and a
      # TAKE CONTROL soft disable. Skip that frame instead: locationd keeps the last valid pose and
      # can tell from the frameId gap that frames were dropped.
      if vipc_dropped_frames < 1:
        pm.send('cameraOdometry', posenet_send)
      pm.send('modelDataV2SP', mdv2sp_send)
    else:
      blinker_publisher.update({}, frame_id=int(meta_main.frame_id),
                               source_ns=int(meta_main.timestamp_eof), now_ns=time.monotonic_ns(),
                               navigation_present=False, output_valid=False)
    last_vipc_frame_id = meta_main.frame_id
