"""Atomic navigation.model.v1 payload on the existing pluginMsg broker.

At most 16 KiB outgoing JSON, bounded lossless 256x256 grayscale plus 47 floats.
source_ns is the original fix observation mapped to the host monotonic clock.
Compression never changes pixels. Too-complex images invalidate, not truncate.
"""
import base64
import binascii
import json
import zlib
import numpy as np
from .model_inputs import MapFrame, NavigationFrame, NavigationIdentity

KIND = 'navigation.model.v1'
MAX_PAYLOAD = 64 * 1024
BROKER_MAX_PAYLOAD = 16 * 1024


class PacketTooLarge(ValueError):
  """A map cannot be published without faulting the pluginMsg source."""


def without_road_context(payload):
  """Revoke road evidence without renewing or modifying the navigation frame."""
  result = dict(payload)
  result.pop('road_context', None)
  return result


def encode_packet(identity, source_ns, pixels, vector, *, generated_ns, expires_ns, road_context=None, road_source_ns=None):
  if (type(source_ns) is not int or type(generated_ns) is not int or type(expires_ns) is not int
      or not 0 < source_ns <= generated_ns < expires_ns):
    raise ValueError('Invalid packet timestamps')
  if pixels.shape != (256, 256) or pixels.dtype != np.uint8 or vector.shape != (47,):
    raise ValueError('Invalid model input layout')
  value = dict(version=2, session=identity.session, revision=identity.revision,
               source_ns=source_ns, generated_ns=generated_ns, expires_ns=expires_ns, coordinate_system='wgs84',
               map=base64.b64encode(zlib.compress(pixels.tobytes(), 1)).decode('ascii'),
               navigation=vector.tolist())
  if isinstance(road_context, dict) and type(road_source_ns) is int and 0 < road_source_ns <= generated_ns:
    value['road_context'] = {**road_context, 'source_ns': road_source_ns}
  size = len(json.dumps(value).encode())
  if size > BROKER_MAX_PAYLOAD and 'road_context' in value:
    del value['road_context']  # Preserve the existing navigation frame if metadata cannot fit.
    size = len(json.dumps(value).encode())
  if size > BROKER_MAX_PAYLOAD:
    raise PacketTooLarge('Map exceeds existing message budget')
  return value


def decode_packet(payload):
  if (not isinstance(payload, dict) or type(payload.get('version')) is not int or payload.get('version') not in (1, 2) or
      payload.get('coordinate_system') != 'wgs84' or
      type(payload.get('source_ns')) is not int or payload['source_ns'] <= 0):
    raise ValueError('Invalid navigation packet')
  generated = payload.get('generated_ns') if payload['version'] == 2 else None
  expiry = payload.get('expires_ns') if payload['version'] == 2 else None
  if payload['version'] == 2 and (type(generated) is not int or type(expiry) is not int or
                                  not payload['source_ns'] <= generated < expiry):
    raise ValueError('Invalid generation time')
  identity = NavigationIdentity('tesnav', payload.get('session'), payload.get('revision'))
  packed = payload.get('map')
  vector = payload.get('navigation')
  if not isinstance(packed, str) or len(packed) > MAX_PAYLOAD or not isinstance(vector, list) or len(vector) != 47:
    raise ValueError('Invalid packet size')
  try:
    inflater = zlib.decompressobj()
    raw = inflater.decompress(base64.b64decode(packed, validate=True), 65537)
    if len(raw) != 65536 or not inflater.eof or inflater.unused_data or inflater.unconsumed_tail:
      raise ValueError('Invalid map buffer')
    pixels = np.frombuffer(raw, dtype=np.uint8).reshape(256, 256).copy()
    values = np.asarray(vector, dtype=np.float32)
  except (zlib.error, binascii.Error, OverflowError) as error:
    raise ValueError('Invalid compressed map') from error
  return (MapFrame(identity, payload['source_ns'], pixels, generated_ns=generated, expires_ns=expiry),
          NavigationFrame(identity, payload['source_ns'], values, generated_ns=generated, expires_ns=expiry))
