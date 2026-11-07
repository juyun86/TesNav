"""Strict original-SDK route geometry, bound to an admitted live phone snapshot.

Envelope derives from the supplied nav_model_input.py research snapshot. No
network or actuator access. GCJ-02 stays labelled and is never sent as WGS-84.
"""
from dataclasses import dataclass
import hashlib
import json

from .model_navigation import KINDS, MODIFIERS
from .amap_steps import convert as convert_amap_steps
from .map_coordinates import to_wgs84
from .route_geometry import cumulative_distance
from .protocol import _integer, _number, _reject_duplicate_keys, _string
from .state import instruction
from .wire import SESSION_ID_PATTERN

MAX_ROUTE_BYTES, MAX_ROUTE_POINTS = 2 * 1024 * 1024, 20000


@dataclass(frozen=True)
class ModelRoute:
  session_id: str
  sequence: int
  route_revision: int
  source_wall_time_ms: int
  coordinate_system: str
  points: tuple
  steps: tuple = ()
  traveled: float = 0.0

  @property
  def identity(self):
    return self.session_id, self.route_revision, self.coordinate_system


def parse_steps(value):
  """Optional complete maneuver list from the phone: distances share the route origin and never decrease."""
  if value is None:
    return ()
  if not isinstance(value, list) or not 1 <= len(value) <= 2000:
    raise ValueError('Invalid step count')
  result, previous = [], -1.0
  for step in value:
    if not isinstance(step, dict) or set(step) != {'distance', 'type', 'modifier'}:
      raise ValueError('Invalid step fields')
    distance, kind, modifier = step['distance'], step['type'], step['modifier']
    if (type(distance) not in (int, float) or not 0 <= distance <= 10_000_000
        or not isinstance(kind, str) or kind not in KINDS
        or not isinstance(modifier, str) or modifier not in MODIFIERS):
      raise ValueError('Invalid step')
    distance = float(distance)
    if distance < previous:
      raise ValueError('Out of order step distance')
    previous = distance
    result.append({'distance': distance, 'type': kind, 'modifier': modifier})
  return tuple(result)


def parse_route(body):
  if not isinstance(body, bytes) or not 0 < len(body) <= MAX_ROUTE_BYTES:
    raise ValueError('Invalid route body size')
  try:
    raw = json.loads(body, object_pairs_hook=_reject_duplicate_keys)
    required = {'schemaVersion', 'messageType', 'sessionId', 'sequence', 'routeRevision',
                'sourceWallTimeMs', 'coordinateSystem', 'points'}
    keys = required | {'steps', 'amapSteps', 'traveled'}
    if (not isinstance(raw, dict) or not required <= set(raw) or set(raw) - keys or type(raw['schemaVersion']) is not int
        or raw['schemaVersion'] != 3 or raw['messageType'] != 'navigation_route'):
      raise ValueError('Invalid route envelope')
    session = _string(raw, 'sessionId', max_length=64)
    if not SESSION_ID_PATTERN.fullmatch(session) or raw['coordinateSystem'] not in ('gcj02', 'wgs84'):
      raise ValueError('Invalid route identity')
    if not isinstance(raw['points'], list) or not 2 <= len(raw['points']) <= MAX_ROUTE_POINTS:
      raise ValueError('Invalid route point count')
    points = []
    for point in raw['points']:
      if not isinstance(point, dict) or set(point) != {'latitude', 'longitude'}:
        raise ValueError('Invalid route point')
      points.append((_number(point, 'latitude', -90, 90), _number(point, 'longitude', -180, 180)))
    if len(set(points)) < 2:
      raise ValueError('Route has no distinct points')
    if raw.get('steps') is not None and raw.get('amapSteps') is not None:
      raise ValueError('Ambiguous route step formats')
    if raw.get('amapSteps') is not None:
      if raw['coordinateSystem'] != 'gcj02':
        raise ValueError('AMap steps require GCJ-02 geometry')
      steps = parse_steps(convert_amap_steps([(lon,lat) for lat,lon in points],raw['amapSteps'],
                                              to_wgs84=to_wgs84,cumulative_distance=cumulative_distance))
    else:
      steps = parse_steps(raw.get('steps'))
    traveled = raw.get('traveled', 0.0)
    if type(traveled) not in (int, float) or not -1e-9 <= traveled <= 10_000_000:
      raise ValueError('Invalid traveled distance')
    return ModelRoute(session, _integer(raw, 'sequence', 1, 2**63-1),
                      _integer(raw, 'routeRevision', 0, 2**63-1),
                      _integer(raw, 'sourceWallTimeMs', 0, 2**63-1),
                      raw['coordinateSystem'], tuple(points), steps, float(traveled))
  except (OverflowError, RecursionError, UnicodeError) as error:
    raise ValueError('Invalid route encoding or numeric value') from error


class RouteStore:
  def __init__(self):
    self.route = None
    self.identity, self.fingerprint = None, None
    self.sequence_floor = 1
    self._coordinates = None

  def current(self, nav, wall_ms, mono):
    snapshot = nav.current(wall_ms, mono)
    identity = ((snapshot.session_id, snapshot.route_revision, snapshot.coordinate_system)
                if snapshot is not None else None)
    valid = instruction(snapshot, wall_ms) is not None
    if self.route is not None and (not valid or identity != self.route.identity):
      # After a dropout/cancel, old route uploads cannot revive geometry. A new
      # upload must be tied to a subsequently accepted snapshot sequence.
      self.sequence_floor = max(self.sequence_floor, nav.latest.sequence + 1)
      self.route, self._coordinates = None, None
    return self.route if valid and self.route is not None and identity == self.route.identity else None

  def accept(self, body, peer, nav, wall_ms, mono):
    self.current(nav, wall_ms, mono)
    snapshot = nav.current(wall_ms, mono)
    if peer != nav.owner or instruction(snapshot, wall_ms) is None:
      raise ValueError('No matching live navigation owner')
    route = parse_route(body)
    identity = snapshot.session_id, snapshot.route_revision, snapshot.coordinate_system
    if (route.identity != identity or route.sequence > snapshot.sequence
        or not -1000 <= wall_ms - route.source_wall_time_ms <= 2000):
      raise ValueError('Route does not match the live snapshot')
    # Geometry and maneuvers describe one route. Same revision must never
    # silently retain old actions after the phone supplies different steps.
    fingerprint = hashlib.sha256(repr((route.points, route.steps)).encode()).digest()
    if identity != self.identity:
      self.identity, self.fingerprint, self.sequence_floor = identity, None, 1
    if route.sequence < self.sequence_floor:
      raise ValueError('Retired route upload')
    if self.fingerprint is not None and fingerprint != self.fingerprint:
      raise ValueError('Route geometry or steps cannot change within a route revision')
    self.fingerprint = fingerprint
    self.sequence_floor = route.sequence
    if self.route is None:
      self.route = route
      self._coordinates = [{'latitude': lat, 'longitude': lon} for lat, lon in route.points]
    return route

  def coordinates(self, nav, wall_ms, mono):
    route = self.current(nav, wall_ms, mono)
    return self._coordinates if route is not None and route.coordinate_system == 'wgs84' else None
