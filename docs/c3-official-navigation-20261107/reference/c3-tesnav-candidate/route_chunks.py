"""Offline experiment: bounded route transfer over existing <=8192 byte UDP.

This is not installed or connected to vehicle I/O. Receiver must authorize the
peer and match a fresh accepted navigation snapshot before calling receive().
"""
import base64
import hashlib
import json
import math
import zlib

WIRE_LIMIT = 8192
CHUNK_BYTES = 4096
MAX_ROUTE_BYTES = 2 * 1024 * 1024
MAX_CHUNKS = 512
MAX_AGE_NS = 120_000_000_000


def unique_object(pairs):
  result = {}
  for key, value in pairs:
    if key in result:
      raise ValueError('duplicate field')
    result[key] = value
  return result


def encode_fragments(route):
  """Return bounded immutable pieces of an already encoded route body."""
  if not isinstance(route, bytes) or not 0 < len(route) <= MAX_ROUTE_BYTES:
    raise ValueError('invalid route size')
  packed = zlib.compress(route, 6)
  count = math.ceil(len(packed) / CHUNK_BYTES)
  if count > MAX_CHUNKS:
    raise ValueError('compressed route too large')
  digest = hashlib.sha256(packed).hexdigest()
  return digest, [base64.b64encode(packed[i:i+CHUNK_BYTES]).decode('ascii') for i in range(0, len(packed), CHUNK_BYTES)]


def make_fragment(session, revision, digest, index, pieces):
  data = {'messageType':'route_fragment','schemaVersion':1,'sessionId':session,'routeRevision':revision,
          'digest':digest,'index':index,'count':len(pieces),'data':pieces[index]}
  result = json.dumps(data, separators=(',', ':'), allow_nan=False).encode('ascii')
  if len(result) > WIRE_LIMIT:
    raise ValueError('fragment too large')
  return result


class RouteAssembler:
  """One bounded transfer, scoped to current accepted peer/session/revision."""
  def __init__(self):
    self.clear()

  def clear(self):
    self.key = None
    self.started_ns = 0
    self.parts = {}

  def receive(self, body, *, peer, session, revision, now_ns):
    if (not isinstance(body,bytes) or len(body)>WIRE_LIMIT or not isinstance(peer,str) or not peer or
        not isinstance(session,str) or not session or type(revision) is not int or revision<0 or
        type(now_ns) is not int or now_ns<=0):
      return None
    if self.key is not None and (now_ns < self.started_ns or now_ns-self.started_ns > MAX_AGE_NS or
                                 self.key[:3] != (peer,session,revision)):
      self.clear()
    try:
      v=json.loads(body,object_pairs_hook=unique_object)
      if (not isinstance(v,dict) or set(v)!={'messageType','schemaVersion','sessionId','routeRevision','digest','index','count','data'} or
          v['messageType']!='route_fragment' or type(v['schemaVersion']) is not int or v['schemaVersion']!=1 or
          v['sessionId']!=session or type(v['routeRevision']) is not int or v['routeRevision']!=revision or
          not isinstance(v['digest'],str) or len(v['digest'])!=64 or any(c not in '0123456789abcdef' for c in v['digest']) or
          type(v['count']) is not int or not 1<=v['count']<=MAX_CHUNKS or type(v['index']) is not int or not 0<=v['index']<v['count'] or
          not isinstance(v['data'],str) or len(v['data'])>4*((CHUNK_BYTES+2)//3)):
        return None
      part=base64.b64decode(v['data'],validate=True)
      if not 0<len(part)<=CHUNK_BYTES or (v['index']<v['count']-1 and len(part)!=CHUNK_BYTES):return None
      key=(peer,session,revision,v['digest'],v['count'])
      if key!=self.key:
        self.clear();self.key=key;self.started_ns=now_ns
      old=self.parts.get(v['index'])
      if old is not None and old!=part:
        self.clear();return None
      self.parts[v['index']]=part
      if len(self.parts)!=v['count']:return None
      packed=b''.join(self.parts[i] for i in range(v['count']))
      if hashlib.sha256(packed).hexdigest()!=v['digest']:
        self.clear();return None
      dec=zlib.decompressobj();route=dec.decompress(packed,MAX_ROUTE_BYTES+1)
      if len(route)>MAX_ROUTE_BYTES or dec.unconsumed_tail or not dec.eof or dec.unused_data:
        self.clear();return None
      self.clear();return route
    except (ValueError,TypeError,UnicodeDecodeError,zlib.error):
      return None
