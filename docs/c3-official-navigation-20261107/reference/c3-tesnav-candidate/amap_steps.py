"""Isolated AMap step conversion experiment; never supplies live navigation."""
import math
import numpy as np

# AMap IconType -> existing C3 vocabulary. Unknown values are rejected.
ICONS = {
  2:('turn','left'), 3:('turn','right'), 4:('turn','slight left'), 5:('turn','slight right'),
  6:('turn','sharp left'), 7:('turn','sharp right'), 8:('turn','uturn'), 9:('continue','straight'),
  11:('roundabout',''), 12:('exit roundabout',''), 15:('arrive',''), 17:('roundabout',''),
  18:('exit roundabout',''), 19:('turn','uturn'), 20:('continue','straight'),
  21:('roundabout',''), 22:('roundabout',''), 23:('roundabout',''), 24:('roundabout',''),
  25:('roundabout',''), 26:('roundabout',''), 27:('roundabout',''), 28:('roundabout',''),
  # AMap calls 65/66 "keep left/right"; preserve the fork direction in the
  # model's vocabulary instead of its deliberately skipped "merge" type.
  65:('fork','left'), 66:('fork','right'),
}
NON_MANEUVER = frozenset((0,1,10,13,14,16))
ROAD_TYPES = frozenset((*range(1,17),53,56,58))
RAMP_TYPES = frozenset((6,8,10,56,58))


def convert(points, segments, *, to_wgs84, cumulative_distance):
  """Bind each segment end to the same WGS84 distance scale as C3's route.

  points and segment coordinates are (longitude, latitude) GCJ-02. The SDK's
  index meaning and its icon's location still require real-device validation.
  Reject inconsistent geometry rather than invent an index or a direction.
  """
  if not isinstance(points,(list,tuple)) or not 2<=len(points)<=20_000 or not isinstance(segments,(list,tuple)) or not 1<=len(segments)<=2000:
    raise ValueError('invalid route size')
  for p in points:
    if (not isinstance(p,(tuple,list)) or len(p)!=2 or any(type(x) not in (int,float) or not math.isfinite(x) for x in p) or
        not -180<=p[0]<=180 or not -85<p[1]<85):raise ValueError('invalid coordinate')
  converted=[to_wgs84(lon,lat,'gcj02') for lon,lat in points]
  distance=cumulative_distance(np.asarray(converted,dtype=np.float64))
  result=[];previous=-1
  for segment in segments:
    required={'start','end','icon','first','last'}
    if not isinstance(segment,dict) or not required<=set(segment) or set(segment)-required-{'roadType'}:raise ValueError('invalid segment')
    start,end,icon=segment['start'],segment['end'],segment['icon']
    if (any(type(i) is not int for i in (start,end,icon)) or not 0<=start<=end<len(points) or start<previous):
      raise ValueError('invalid segment index')
    for actual,expected in ((segment['first'],points[start]),(segment['last'],points[end])):
      if (not isinstance(actual,(tuple,list)) or len(actual)!=2 or
          any(type(x) not in (float,int) or not math.isfinite(x) for x in actual) or
          max(abs(actual[0]-expected[0]),abs(actual[1]-expected[1]))>1e-6):
        raise ValueError('segment coordinates do not match route indices')
    previous=end
    if icon in NON_MANEUVER:continue
    if icon not in ICONS:raise ValueError('unknown maneuver')
    kind,modifier=ICONS[icon]
    road=segment.get('roadType')
    if road is not None and (type(road) is not int or road not in ROAD_TYPES):raise ValueError('unknown road type')
    if icon in (2,3,4,5,6,7,65,66):
      side='left' if icon in (2,4,6,65) else 'right'
      if road==9:kind,modifier='off ramp',side
      elif road in RAMP_TYPES:kind,modifier='on ramp',side
    result.append({'distance':float(distance[end]),'type':kind,'modifier':modifier})
  if not result:raise ValueError('no supported maneuvers')
  return result
