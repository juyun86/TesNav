"""Asynchronous route/map producer for the existing atomic pluginMsg channel."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import time
import numpy as np
from openpilot.common.swaglog import cloudlog
from .live_packet import encode_packet, without_road_context, PacketTooLarge
from .map_coordinates import to_wgs84
from .map_raster import mercator
from .minedata_map import MineDataMap
from .officialnav.mapbox_tiles import MapboxTiles
from .officialnav.renderer import MapRenderer, Position
from .officialnav.road_context import fix_confirms_route
from .model_inputs import NavigationIdentity
from .model_navigation import navigation_input
from .route_geometry import RouteTracker

SOURCE_TTL_NS = 1_500_000_000  # vehicle fix may be up to this old
PHONE_MAX_AGE_NS = 2_000_000_000  # phone fixes arrive later and are dead-reckoned forward
RENDER_PERIOD = .05  # official mapsd runs Ratekeeper(20)
SLOW_RENDER_S = .25  # a frame slower than this backs off instead of saturating one core
SLOW_RENDER_PERIOD = .1  # 5 Hz while frames are expensive
MOVING_KPH = 2.0  # below this the map only refreshes on a new fix
PREFETCH_WAIT_S = 10.0  # after this long a cold frame is rendered anyway (slow but bounded)
ROAD_FIX_MAX_AGE_NS = 1_400_000_000


def road_inputs_current(snapshot, vehicle_source_ns, road_fix, road_source_ns, now_ns):
  if not (snapshot is not None and snapshot.route_active is True and snapshot.route_matched is True and
          road_fix is not None and getattr(road_fix, 'route_active', None) is True and
          getattr(road_fix, 'route_matched', None) is True and getattr(road_fix, 'gps_weak', None) is False and
          type(vehicle_source_ns) is int and type(road_source_ns) is int and
          0 < now_ns-vehicle_source_ns <= ROAD_FIX_MAX_AGE_NS and
          0 < now_ns-road_source_ns <= ROAD_FIX_MAX_AGE_NS):
    return False
  try:
    if snapshot.coordinate_system != 'gcj02' or road_fix.coordinate_system != 'gcj02':
      return False
    vehicle = to_wgs84(snapshot.longitude, snapshot.latitude, 'gcj02')
    phone = to_wgs84(road_fix.longitude, road_fix.latitude, 'gcj02')
    # At publication, newly received fixes may already contradict the
    # asynchronous render. Recheck their mutual consistency immediately.
    return fix_confirms_route(road_fix, phone, (*vehicle, road_fix.bearing_deg),
                              align_seconds=(vehicle_source_ns-road_source_ns)/1e9,
                              vehicle_fix=vehicle, vehicle_speed_kph=snapshot.speed_kph)
  except (AttributeError, TypeError, ValueError, OverflowError):
    return False

MODIFIERS = {1: 'straight', 2: 'left', 3: 'right', 4: 'slight left', 5: 'slight right',
             6: 'sharp left', 7: 'sharp right', 8: 'left', 9: 'right', 10: 'left',
             11: 'right', 12: 'left', 13: 'right', 14: 'left', 15: 'right', 16: 'uturn'}


@dataclass(frozen=True)
class Maneuver:
  distance: float
  type: str
  modifier: str


class RouteContext:
  def __init__(self, route):
    if route.coordinate_system != 'gcj02':
      raise ValueError('Expected default MineData coordinate system')
    points = [to_wgs84(lon, lat, 'gcj02') for lat, lon in route.points]
    self.tracker = RouteTracker(points, coordinate_system='wgs84')
    self.projected = mercator(self.tracker.route)
    steps = tuple(getattr(route, 'steps', ()))
    if steps and isinstance(steps[0], dict):
      # Phone-supplied complete maneuvers already share the route origin; no rebinding.
      self.steps = [Maneuver(float(step['distance']), str(step['type']), str(step['modifier']))
                    for step in steps]
      return
    # Bind maneuver endpoints to this route's geometric distance, not the
    # independent distance scale of another provider or a current-turn hint.
    index, self.steps = 0, []
    for step in route.steps:
      endpoint = to_wgs84(step.points[-1][1], step.points[-1][0], 'gcj02')
      candidates = np.linalg.norm(self.tracker.route[index:] - endpoint, axis=1)
      index += int(candidates.argmin())
      if candidates.min() > 1e-7:
        raise ValueError('Step endpoint is not on route')
      action, aux = int(step.action), step.auxiliary_action
      if aux == 9:
        kind, modifier = 'arrive', ''
      elif aux in (4, 5) and action == 0:
        kind, modifier = ('roundabout' if aux == 4 else 'exit roundabout'), ''
      else:
        if action not in MODIFIERS:
          raise ValueError('Unknown MineData maneuver')
        modifier = MODIFIERS[action]
        kind = ('roundabout' if aux == 4 else 'exit roundabout' if aux == 5 else
                'on ramp' if aux == 7 else 'fork' if 8 <= action <= 15 else 'turn')
      self.steps.append(Maneuver(float(self.tracker.distance[index]), kind, modifier))


class SourceClock:
  """Least-delay estimate of the phone wall clock against the host monotonic clock.

  A one-way delay is at least the true offset plus queueing, so the running minimum converges upwards to the
  offset plus the minimum transit; no fixed "reserve" is assumed. The estimate is allowed to creep by 1 ms per
  minute to follow phone clock drift. A heartbeat that repeats a frozen fix does not lower the estimate, so the
  observation age keeps growing and the caller drops it instead of treating the repeat as fresh.
  """
  DRIFT_NS_PER_SECOND = 1_000_000 // 60  # 1 ms per minute covers tens of ppm

  def __init__(self):
    self.session = None
    self.offset = None
    self.since = 0

  def observe(self, snapshot, receive_ns):
    delta = receive_ns - snapshot.source_wall_time_ms*1_000_000
    if snapshot.session_id != self.session or self.offset is None:
      self.session, self.offset, self.since = snapshot.session_id, delta, receive_ns
      return
    if delta < self.offset:
      self.offset, self.since = delta, receive_ns
      return
    creep = (receive_ns - self.since) * self.DRIFT_NS_PER_SECOND // 1_000_000_000
    if creep:
      self.offset += creep
      self.since += creep * 1_000_000_000 // self.DRIFT_NS_PER_SECOND

  def stamp(self, snapshot):
    return snapshot.location_observed_at_ms*1_000_000 + (self.offset or 0)

  def age_ns(self, snapshot, now_ns):
    return now_ns - self.stamp(snapshot)


class LiveNavigation:
  def __init__(self, fetch, mapbox_token=''):
    self.official = MapRenderer(MapboxTiles(mapbox_token)) if mapbox_token else None
    if self.official is not None:
      self.official.roads = __import__('functools').lru_cache(maxsize=256)(self.official._load_roads)
    self.maps = None if self.official is not None else MineDataMap(fetch)
    self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='navigation-map')
    self.prefetch_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='navigation-prefetch')
    self.prefetch_src, self.prefetch_future = None, None
    self.prefetch_started = 0.0
    self.clock = SourceClock()
    self.pending = self.pending_key = self.identity = self.context = self.route = None
    self.last_stamp, self.next_at = 0, 0.
    self.last_render_s = 0.0
    self.last_image = None
    self.last_remaining_m = 0.0
    self.last_eta_s = 0.0
    self.last_error = ''

  def observe(self, snapshot, now_ns):
    self.clock.observe(snapshot, now_ns)

  def _render(self, route, snapshot, key, stamp, source_ttl_ns, *, road_fix=None, road_source_ns=None):
    started = time.monotonic()
    if self.route is not route:
      self.context = RouteContext(route)
      self.route = route
    context = self.context
    lon, lat = to_wgs84(snapshot.longitude, snapshot.latitude, 'gcj02')
    progress = float(context.tracker.project(lon, lat))
    fix_pose = context.tracker.pose(progress)
    age = max(0.0, (time.monotonic_ns() - stamp) / 1e9) if stamp else 0.0
    speed = max(0.0, float(getattr(snapshot, 'speed_kph', 0.0) or 0.0)) / 3.6
    if age > 0.0 and speed > 0.0:
      progress = min(progress + speed * age, float(context.tracker.distance[-1]))
    pose = context.tracker.pose(progress)
    if self.official is not None:
      rendered = self.official.render(Position(latitude=pose[1], longitude=pose[0], bearing=pose[2]), context.tracker.route)
      if not rendered.valid:
        raise ValueError('official map incomplete')
      image = rendered.image
      road_context = None
      try:
        if road_fix is not None and road_fix.coordinate_system == 'gcj02' and type(road_source_ns) is int:
          phone_fix = to_wgs84(road_fix.longitude, road_fix.latitude, 'gcj02')
          if fix_confirms_route(road_fix, phone_fix, fix_pose, align_seconds=(stamp-road_source_ns)/1e9,
                                vehicle_fix=(lon, lat), vehicle_speed_kph=snapshot.speed_kph):
            road_context = rendered.road_context
      except (AttributeError, TypeError, ValueError, OverflowError):
        pass  # Invalid optional road evidence must not interrupt navigation frames.
    else:
      image = self.maps.render(snapshot.longitude, snapshot.latitude, pose, context.projected, key)
      road_context = None
    if time.monotonic_ns() >= stamp + source_ttl_ns:
      # A slow render is stale, not a route error; discard without triggering a replan.
      return None
    encoded = navigation_input(context.steps, progress, complete=True, source_ns=stamp, now_ns=time.monotonic_ns())
    if not encoded.valid:
      raise ValueError('Navigation encoding: '+encoded.reason)
    generated_ns = time.monotonic_ns()
    if generated_ns >= stamp + source_ttl_ns:
      # The deadline can pass while encoding even if rendering finished in time.
      return None
    identity = NavigationIdentity('tesnav', snapshot.session_id, snapshot.route_revision)
    try:
      payload = encode_packet(identity, stamp, image, np.array(encoded.vector, dtype=np.float32),
                              generated_ns=generated_ns, expires_ns=stamp+source_ttl_ns,
                              road_context=road_context if generated_ns-stamp <= 1_400_000_000 else None,
                              road_source_ns=road_source_ns if type(road_source_ns) is int and
                                             0 <= generated_ns-road_source_ns <= 1_400_000_000 else None)
    except PacketTooLarge:
      return None
    remaining = [dict(distance=s.distance-progress, type=s.type, modifier=s.modifier)
                 for s in context.steps if s.distance >= progress]
    self.last_render_s = time.monotonic() - started
    self.last_image = image
    self.last_remaining_m = max(0.0, float(context.tracker.distance[-1]) - progress)
    self.last_eta_s = self.last_remaining_m / max(speed, 3.0)
    if self.official is not None and (self.prefetch_future is None or self.prefetch_future.done()):
      self.prefetch_future = self.prefetch_executor.submit(self._warm_ahead, pose)
    # Keep the display frame tied to this packet; the next asynchronous render
    # may update last_image before the caller publishes the completed result.
    return payload, remaining, context.tracker.route.tolist(), image.copy()

  def step(self, snapshot, route, key, *, vehicle_source_ns=None, source_ttl_ns=SOURCE_TTL_NS,
           road_fix=None, road_source_ns=None):
    now = time.monotonic_ns()
    valid = (snapshot is not None and route is not None and snapshot.navigation_mode == 'realtime' and
             ((snapshot.route_active and snapshot.route_matched) or vehicle_source_ns is not None and snapshot.destination is not None) and not snapshot.gps_weak and
             snapshot.location_present and snapshot.accuracy_m <= 30 and snapshot.coordinate_system == 'gcj02')
    stamp = (vehicle_source_ns if vehicle_source_ns is not None else self.clock.stamp(snapshot)) if valid else 0
    valid = valid and 0 < stamp <= now and now-stamp <= source_ttl_ns
    identity = (snapshot.session_id, snapshot.route_revision, snapshot.destination, id(route)) if valid else None
    changed = identity != self.identity
    if changed:
      self.identity, self.last_stamp = identity, 0
    if route is not None and route is not self.prefetch_src and (
        self.prefetch_future is None or self.prefetch_future.done()):
      self.prefetch_src = route
      self.prefetch_started = time.monotonic()
      self.prefetch_future = self.prefetch_executor.submit(self._prefetch, route, key)
    result = None
    if self.pending is not None and self.pending.done():
      try:
        candidate = self.pending.result()
        if (candidate is not None and self.pending_key == identity and identity is not None
            and candidate[0]['source_ns'] <= now <= candidate[0]['expires_ns']):
          # A render can finish after the route/independent fix has already
          # been revoked. Preserve the map but do not resurrect road authority.
          if ('road_context' in candidate[0] and
              not road_inputs_current(snapshot, stamp, road_fix, road_source_ns, now)):
            candidate = (without_road_context(candidate[0]), *candidate[1:])
          result = candidate
          self.last_error = ''
      except (ValueError, TypeError, KeyError, IndexError, OSError, RuntimeError) as error:
        self.last_error = type(error).__name__
        cloudlog.warning('nav map render failed: %s %s', type(error).__name__, str(error)[:80])
        self.next_at = time.monotonic() + 1.
      self.pending = self.pending_key = None
    # Only render when this frame's tiles are already cached: a cold frame blocks the only render
    # thread for seconds and is rejected as stale anyway. Prefetch warms tiles in route order.
    ready = True
    if self.maps is None:
      ready = True
    elif route is not None and snapshot is not None and time.monotonic() - self.prefetch_started < PREFETCH_WAIT_S:
      try:
        ready = all(tile in self.maps.cache for tile in self.maps.tiles_for(snapshot.longitude, snapshot.latitude))
      except (AttributeError, TypeError, ValueError):
        ready = True
    moving = (float(getattr(snapshot, 'speed_kph', 0.0) or 0.0) > MOVING_KPH) if snapshot is not None else False
    # Hybrid: keep the official 20 Hz cadence; only a slow frame drops us to 5 Hz until it recovers.
    period = SLOW_RENDER_PERIOD if self.last_render_s > SLOW_RENDER_S else RENDER_PERIOD
    if valid and ready and self.pending is None and time.monotonic() >= self.next_at and (stamp != self.last_stamp or moving):
      # Timer-driven while moving: the render projects the last fix forward by speed * age, so the
      # map no longer waits for the next GPS fix (that was the 3 Hz ceiling).
      self.pending = self.executor.submit(self._render, route, snapshot, key, stamp, source_ttl_ns,
                                          road_fix=road_fix, road_source_ns=road_source_ns)
      self.pending_key, self.last_stamp, self.next_at = identity, stamp, time.monotonic()+period
    return result, changed

  def _warm_ahead(self, pose):
    # Official renderer: fetch the tiles of the view ~120 m ahead so the next frames hit the cache
    # instead of blocking the render thread on the network.
    try:
      import math
      lon, lat, bearing = pose
      d = 120.0 / 6371000.0
      b = math.radians(bearing)
      lat2 = math.asin(math.sin(math.radians(lat)) * math.cos(d) + math.cos(math.radians(lat)) * math.sin(d) * math.cos(b))
      lon2 = lon + math.atan2(math.sin(b) * math.sin(d) * math.cos(math.radians(lat)),
                              math.cos(d) - math.sin(math.radians(lat)) * math.sin(lat2))
      wanted, _ = self.official.view_tiles(lon2, math.degrees(lat2))
      for x, y in wanted:
        self.official.roads(x, y)
    except (ValueError, OSError, RuntimeError):
      pass

  def _prefetch(self, route, key):
    if self.maps is None:
      return
    try:
      self.maps.prefetch([(lat, lon) for lat, lon in route.points[::20]], key)
    except Exception as error:
      self.last_error = type(error).__name__

  def close(self):
    self.prefetch_executor.shutdown(wait=False, cancel_futures=True)
    self.executor.shutdown(wait=False, cancel_futures=True)
