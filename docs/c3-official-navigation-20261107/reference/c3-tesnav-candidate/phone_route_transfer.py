"""Staged UDP route receiver; reuses the existing admitted navigation owner."""
import json
from .route_chunks import RouteAssembler, unique_object
from .route_commit import commit_route
from .state import instruction


def is_route_fragment(body):
  try:
    data=json.loads(body,object_pairs_hook=unique_object)
    return isinstance(data,dict) and data.get('messageType')=='route_fragment'
  except (ValueError,TypeError,UnicodeDecodeError):
    return False


class PhoneRouteTransfer:
  def __init__(self):
    self.assembler=RouteAssembler()

  def reconcile(self, navigation, wall_ms, mono):
    snapshot=navigation.current(wall_ms,mono)
    if snapshot is None or instruction(snapshot,wall_ms) is None:
      self.assembler.clear()
      return None
    key=self.assembler.key
    if key is not None and key[:3]!=(navigation.owner,snapshot.session_id,snapshot.route_revision):
      self.assembler.clear()
    return snapshot

  def receive(self,body,peer,navigation,routes,wall_ms,mono,now_ns):
    snapshot=self.reconcile(navigation,wall_ms,mono)
    if snapshot is None or peer!=navigation.owner:
      return None
    raw=self.assembler.receive(body,peer=peer,session=snapshot.session_id,revision=snapshot.route_revision,now_ns=now_ns)
    if raw is None:return None
    route=commit_route(raw,store=routes,navigation=navigation,peer=peer,wall_ms=wall_ms,mono=mono)
    return {'messageType':'navassist_udp_ack','schemaVersion':3,'sessionId':route.session_id,'sequence':route.sequence}
