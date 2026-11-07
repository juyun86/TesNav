"""Existing UDP/HTTPS ingress, route acquisition and atomic model-map producer."""
import os
import time

from openpilot.cereal import messaging
from openpilot.common.params import Params
from openpilot.tesla import host_api
from openpilot.tesla.plugin_params import ParamsView
from openpilot.tesla.plugins import send_msg, PluginMsgs
from msgq.visionipc import VisionIpcServer
from openpilot.cereal.visionipc import VisionStreamType

from .plugin import TesNav
from .protocol import parse_snapshot
from .clock_sync import clock_probe
from .phone_fix import PhoneFixStore
from .route import RouteStore
from .phone_route_transfer import PhoneRouteTransfer, is_route_fragment
from .state import NavigationStore, instruction
from .minedata_worker import MineDataWorker
from .live_navigation import SOURCE_TTL_NS, LiveNavigation, road_inputs_current

REPLAN_COOLDOWN_S = 10.  # s between automatic re-plans after the fix leaves the route corridor
from .live_packet import KIND, without_road_context
from .vehicle_gps import (CAR_SPEED_MAX_AGE_NS, PHONE_MAX_AGE_NS, phone_observation, telemetry,
                          model_observation, with_car_speed)

# Banner text mirrors what official navd shows: it forwards the map provider's instruction text
# (Mapbox "Turn right onto X"), so compose the same wording from the maneuver type/modifier.
_TURN_TEXT = {
  'straight': ('直行', 'Straight'), 'left': ('左转', 'Turn left'), 'right': ('右转', 'Turn right'),
  'slight left': ('稍向左转', 'Slight left'), 'slight right': ('稍向右转', 'Slight right'),
  'sharp left': ('向左急转', 'Sharp left'), 'sharp right': ('向右急转', 'Sharp right'),
  'uturn': ('掉头', 'U-turn'),
}
_KIND_TEXT = {
  'fork': ('靠', 'Keep'), 'merge': ('汇入车流', 'Merge'), 'roundabout': ('进入环岛', 'Enter roundabout'),
  'arrive': ('到达目的地', 'Arrive at destination'), 'continue': ('继续直行', 'Continue'),
}
_LANG = {'at': 0.0, 'zh': True}


def ui_chinese(params):
  now = time.monotonic()
  if now - _LANG['at'] > 5.0:
    _LANG['at'] = now
    try:
      _LANG['zh'] = str(params.get('LanguageSetting') or '').startswith('zh')
    except Exception:
      pass
  return _LANG['zh']


def banner_text(kind, modifier, chinese):
  modifier = (modifier or '').strip()
  if kind == 'fork':
    pair = ('靠左', 'Keep left') if 'left' in modifier else ('靠右', 'Keep right')
    return pair[0] if chinese else pair[1]
  if kind in _KIND_TEXT:
    pair = _KIND_TEXT[kind]
    return pair[0] if chinese else pair[1]
  pair = _TURN_TEXT.get(modifier or 'straight', ('转向', 'Turn'))
  return pair[0] if chinese else pair[1]



def publish(pm, value):
  message = messaging.new_message('navInstruction', valid=value is not None)
  if value:
    for key, item in value.items():
      setattr(message.navInstruction, key, item)
  pm.send('navInstruction', message)


def publish_map_frame(server, image, frame_id, payload):
  # VisionIPC SOF is the source observation time, not the route revision.
  server.send(VisionStreamType.VISION_STREAM_MAP, image.tobytes(), frame_id,
              int(payload['source_ns']), int(payload['generated_ns']))


def main():
  # Keep the 20 Hz renderer off the cores used by selfdrived/pandad when the spare cores are online.
  try:
    os.sched_setaffinity(0, {4, 5})
    os.nice(5)
  except OSError:
    pass
  # Fail on corrupt replay state instead of silently accepting an old session.
  store = NavigationStore(host_api.data_dir(TesNav) / 'udp-replay.json')
  routes = RouteStore()
  phone_route_transfer = PhoneRouteTransfer()
  phone_fixes = PhoneFixStore()
  pm = messaging.PubMaster(['navInstruction', 'navRoute'])
  params = Params()
  plugin_params = ParamsView(params)
  worker = live = None
  model_instruction, model_expiry = None, 0
  last_road_payload = None
  route_sent = None
  vehicle_messages = PluginMsgs()
  car_state = messaging.SubMaster(['carState'])
  next_publish = 0.
  try:
    with TesNav.navigation_ingress() as ingress:
      if getattr(ingress, 'HTTPS_FETCH_ABI', 0) != 1:
        raise RuntimeError('Host navigation HTTPS transport adapter is required')
      if getattr(ingress, 'ACK_TELEMETRY_ABI', 0) != 1:
        raise RuntimeError('Host existing UDP telemetry acknowledgement support is required')
      worker = MineDataWorker(ingress.fetch_https)
      try:
        try:
          mapbox_token = open('/data/nav-work/mapbox_token').read().strip()
        except OSError:
          mapbox_token = open('/data/params/d/MapboxPublicKey').read().strip()
      except OSError:
        mapbox_token = ''
      live = LiveNavigation(ingress.fetch_https, mapbox_token=mapbox_token)
      server = VisionIpcServer('mapsd')
      server.create_buffers_with_sizes(VisionStreamType.VISION_STREAM_MAP, 4, 256, 256, 256 * 256, 256, 256 * 256)
      server.start_listener()
      frame_id = 0
      last_replan = 0.0
      while True:
        received = ingress.receive()
        mono, wall = time.monotonic(), time.time_ns() // 1_000_000
        if received:
          body, peer = received
          try:
            fragment = is_route_fragment(body)
            probe = None if fragment else clock_probe(body, peer, store, wall_ms=wall, mono=mono, parked=host_api.parked(params))
            if fragment:
              ack = phone_route_transfer.receive(body, peer, store, routes, wall, mono, int(mono*1e9))
              if ack is not None:
                ingress.ack(ack)
            elif probe is not None:
              ingress.ack(probe)
            elif phone_fixes.accept(body, peer, store, wall, mono):
              pass  # Optional sidecar; never replaces or acknowledges a navigation snapshot.
            else:
              # Pair a new source/session only while parked, including after restart.
              try:
                candidate = parse_snapshot(body)
              except (ValueError, UnicodeDecodeError, RecursionError):
                # Path 2: the phone uploads a complete route (geometry + maneuvers); this packet is not a snapshot.
                routes.accept(body, peer, store, wall, mono)
                raise ValueError('Route upload handled')
              # Same recorded owner+session may resume after a service restart; only a new or foreign source needs parking.
              # A restarted phone app opens a new session id from the same address; re-pairing is only
              # required for a different source, not for the recorded owner restarting its own app.
              resumable = peer == store.owner
              if not resumable and not host_api.parked(params):
                raise ValueError('Park to establish the navigation session')
              ack = store.accept(body, peer, wall, mono, parked=host_api.parked(params))
              live.observe(candidate, int(mono*1e9))
              accepted_route = routes.current(store, wall, mono)
              ack['modelRouteRevision'] = (accepted_route.route_revision
                                           if accepted_route is not None and accepted_route.steps else -1)
              vehicle = telemetry(vehicle_messages)
              if vehicle is not None:
                ack['vehicleTelemetry'] = vehicle
              ingress.ack(ack)
          except (ValueError, UnicodeDecodeError, RecursionError):
            pass  # Never log phone coordinates or payloads.
        if mono >= next_publish:
          phone_route_transfer.reconcile(store, wall, mono)
          snapshot = store.current(wall, mono)
          key = plugin_params.get('ExtMineDataKey') or ''
          model_snapshot, source_ns, oldest_ns = model_observation(snapshot, vehicle_messages)
          vehicle_fix_available = model_snapshot is not None
          road_fix, road_source_ns = phone_fixes.current(snapshot, time.monotonic_ns(),
                                                         vehicle_fix_available=vehicle_fix_available)
          ttl = SOURCE_TTL_NS
          if model_snapshot is not None:
            # Position age drives projection; oldest required observation limits freshness.
            ttl = min(ttl, oldest_ns + SOURCE_TTL_NS - source_ns)
          if model_snapshot is None:
            # Vehicle GPS is missing or stale: fall back to the phone fix, dead-reckoned from its own timestamp.
            model_snapshot, source_ns = phone_observation(snapshot, live.clock, time.monotonic_ns())
            ttl = PHONE_MAX_AGE_NS
          car_state.update(0)
          speed_now = time.monotonic_ns()
          speed_stamp = int(car_state.logMonoTime['carState'])
          model_snapshot = with_car_speed(model_snapshot, car_state['carState'].vEgo,
                                          stamp_ns=speed_stamp, now_ns=speed_now,
                                          valid=bool(car_state.seen['carState'] and car_state.valid['carState']
                                                     and car_state.alive['carState']))
          if (last_road_payload is not None and
              not road_inputs_current(model_snapshot, source_ns if vehicle_fix_available else None,
                                      road_fix, road_source_ns, speed_now)):
            # Cancel the optional permission immediately, including while the
            # renderer is busy. Keep the original map/timestamps unchanged.
            remaining = last_road_payload['expires_ns']-speed_now
            if remaining > 0:
              send_msg(TesNav, KIND, without_road_context(last_road_payload),
                       valid_for_ms=max(1, int(remaining/1_000_000)))
            last_road_payload = None
          if model_snapshot is not None:
            # A rendered frame expires when either its fix or the car-speed sample expires.
            ttl = min(ttl, speed_stamp + CAR_SPEED_MAX_AGE_NS - source_ns)
            uploaded = routes.current(store, wall, mono)
            if uploaded is not None and uploaded.steps:
              # Use only the phone's admitted complete route and maneuvers.
              route = uploaded
            else:
              # No alternate route: live.step revokes the prior map while the
              # phone route is missing or not yet complete.
              route = None
            result, changed = live.step(model_snapshot, route, key, vehicle_source_ns=source_ns, source_ttl_ns=ttl,
                                        road_fix=road_fix, road_source_ns=road_source_ns)
            if live.last_error and time.monotonic() - last_replan >= REPLAN_COOLDOWN_S:
              # Off the planned corridor (or a bad frame): plan again from the current position.
              worker.reset()
              live.last_error = ''
              last_replan = time.monotonic()
          else:
            result, changed = live.step(None, None, key)
          if changed:
            send_msg(TesNav, KIND, {'version': 1, 'valid': False}, valid_for_ms=500)
            model_instruction, model_expiry = None, 0
            last_road_payload = None
            route_sent = None
            clear = messaging.new_message('navRoute', valid=False)
            clear.navRoute.coordinates = []
            pm.send('navRoute', clear)
          if result is not None:
            payload, maneuvers, points, image = result
            if image is not None:
              frame_id += 1
              try:
                publish_map_frame(server, image, frame_id, payload)
              except Exception:
                pass
            remaining_ms = max(1, int((payload['expires_ns']-time.monotonic_ns())/1_000_000))
            send_msg(TesNav, KIND, payload, valid_for_ms=remaining_ms)
            last_road_payload = payload if 'road_context' in payload else None
            if maneuvers:
              first = maneuvers[0]
              model_instruction = dict(maneuverDistance=first['distance'], maneuverType=first['type'],
                                       maneuverModifier=first['modifier'], allManeuvers=maneuvers, lanes=[],
                                       maneuverPrimaryText=banner_text(first['type'], first['modifier'], ui_chinese(params)),
                                       maneuverSecondaryText='',
                                       showFull=float(first['distance']) < 150.0,
                                       distanceRemaining=float(live.last_remaining_m),
                                       timeRemaining=float(live.last_eta_s))
              model_expiry = time.monotonic_ns() + 5_000_000_000  # keep the panel populated between renders and reroutes
            if route_sent != id(route):
              message = messaging.new_message('navRoute', valid=True)
              message.navRoute.coordinates = [dict(latitude=lat, longitude=lon) for lon, lat in points]
              pm.send('navRoute', message)
              route_sent = id(route)
          value = model_instruction if time.monotonic_ns() <= model_expiry else instruction(snapshot, wall)
          publish(pm, value)
          next_publish = mono + .05
        time.sleep(max(0., next_publish - time.monotonic()))
  finally:
    if worker is not None:
      worker.close()
    if live is not None:
      live.close()
    send_msg(TesNav, KIND, {'version': 1, 'valid': False}, valid_for_ms=500)
    publish(pm, None)
    clear = messaging.new_message('navRoute', valid=False)
    clear.navRoute.coordinates = []
    pm.send('navRoute', clear)


if __name__ == '__main__':
  main()
