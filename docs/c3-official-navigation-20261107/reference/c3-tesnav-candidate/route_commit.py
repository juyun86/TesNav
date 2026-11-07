"""Offline experiment binding complete route transfer to an admitted snapshot.

The transfer digest covers the original payload. Only transport sequence and
timestamp are rebound, from the receiver's actually accepted fresh snapshot;
position/guidance timestamps and route content are never refreshed here.
"""
import json
from .route_chunks import unique_object, MAX_ROUTE_BYTES

def commit_route(raw, *, store, navigation, peer, wall_ms, mono):
  if not isinstance(raw,bytes) or not 0<len(raw)<=MAX_ROUTE_BYTES:
    raise ValueError('invalid route payload')
  snapshot=navigation.current(wall_ms,mono)
  if snapshot is None or peer!=navigation.owner:
    raise ValueError('no admitted owner or snapshot')
  original=json.loads(raw,object_pairs_hook=unique_object)
  if (not isinstance(original,dict) or original.get('messageType')!='navigation_route' or
      original.get('sessionId')!=snapshot.session_id or type(original.get('routeRevision')) is not int or
      original['routeRevision']!=snapshot.route_revision or original.get('coordinateSystem')!=snapshot.coordinate_system):
    raise ValueError('transfer route identity changed')
  if (type(snapshot.sequence) is not int or type(snapshot.source_wall_time_ms) is not int or
      not -1000<=wall_ms-snapshot.source_wall_time_ms<=2000):
    raise ValueError('no fresh accepted snapshot')
  if (type(original.get('sequence')) is not int or not 0<original['sequence']<=snapshot.sequence or
      type(original.get('sourceWallTimeMs')) is not int or not 0<original['sourceWallTimeMs']<=snapshot.source_wall_time_ms+1000):
    raise ValueError('route references a future or malformed snapshot')
  original['sequence']=snapshot.sequence
  original['sourceWallTimeMs']=snapshot.source_wall_time_ms
  return store.accept(json.dumps(original,separators=(',',':'),allow_nan=False).encode(),peer,navigation,wall_ms,mono)
