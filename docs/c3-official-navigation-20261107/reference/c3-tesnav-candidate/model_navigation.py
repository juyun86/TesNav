"""Offline official-model command adapter; no sockets, Params or model execution.

Encoding follows commaai/openpilot a03bb1d4 navigation/helpers.py. Unlike its
permissive fallback-to-straight, unknown vocabulary invalidates the whole input.
Distances are ordered from one shared origin; traveled uses that same origin.
Callers must separately bind route/session/coordinate identity before use.
"""
from dataclasses import dataclass
import math
import struct

SIDED = {
  'depart': (1, 2, 3), 'arrive': (4, 5, 6),
  'on ramp': (17, 18, 19), 'off ramp': (20, 20, 21), 'fork': (22, 23, 24),
  'roundabout': (26, 26, 26), 'rotary': (26, 26, 26), 'roundabout turn': (26, 26, 26),
  'exit roundabout': (27, 27, 27), 'exit rotary': (27, 27, 27), 'notification': (28, 28, 28),
}
TURNS = {'straight': 8, 'slight right': 9, 'right': 10, 'sharp right': 11, 'uturn': 13,
         'sharp left': 14, 'left': 15, 'slight left': 16}
KINDS = frozenset(SIDED) | {'turn', 'continue', 'new name', 'end of road', 'merge'}
MODIFIERS = frozenset(TURNS) | {''}
ZERO = (0.,) * 47


@dataclass(frozen=True)
class NavigationInput:
  valid: bool
  vector: tuple
  reason: str = ''


def finite_distance(value):
  return type(value) in (int, float) and 0 <= value <= 10_000_000 and math.isfinite(value)


def navigation_input(maneuvers, traveled, *, complete, source_ns, now_ns):
  """Return 47 float32-representable values and explicit validity, without I/O.

complete is evidence supplied by the exporter, not inferred from a nonempty
list. A current instruction alone must set complete=False. No cached output.
"""
  if complete is not True:
    return NavigationInput(False, ZERO, 'incomplete_steps')
  if (type(source_ns) is not int or type(now_ns) is not int or source_ns <= 0
      or not 0 <= now_ns - source_ns <= 2_000_000_000):
    return NavigationInput(False, ZERO, 'stale_steps')
  if not isinstance(maneuvers, (tuple, list)) or len(maneuvers) > 2000 or not finite_distance(traveled):
    return NavigationInput(False, ZERO, 'invalid_input')
  selected, previous = None, -1.
  for step in maneuvers:
    kind, modifier, distance = (getattr(step, name, None) for name in ('type', 'modifier', 'distance'))
    if (not isinstance(kind, str) or not isinstance(modifier, str) or kind not in KINDS
        or modifier not in MODIFIERS or not finite_distance(distance) or distance < previous):
      return NavigationInput(False, ZERO, 'invalid_steps')
    previous = distance
    if kind == 'merge':
      continue
    if kind in SIDED:
      straight, right, left = SIDED[kind]
      command = right if 'right' in modifier else left if 'left' in modifier else straight
    else:
      if modifier not in TURNS:
        return NavigationInput(False, ZERO, 'missing_turn_modifier')
      command = TURNS[modifier]
    remaining = distance - traveled
    if selected is None and command > 6 and remaining >= 0:
      selected = command, remaining
  vector = list(ZERO)
  if selected is not None:
    command, remaining = selected
    vector[command], vector[-1] = 1., remaining / 100.
  return NavigationInput(True, struct.unpack('<47f', struct.pack('<47f', *vector)))
