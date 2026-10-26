package com.autosdk.drive.navi;

/** Engine display state, not a new localization measurement. No timers or driving commands. */
public final class LaneBridgeState {
  private long path = -1, lastGuidance = -1, lanePath = -1, lastSent = -1;
  private int segment = -1, laneSegment = -1;
  private String lanes;

  public synchronized void clear() { lanes = null; lanePath = -1; laneSegment = -1; lastSent = -1; }

  public synchronized void observe(String payload, long now, int mode) {
    clear();
    if (mode == 0 && payload != null && lastGuidance >= 0 && now >= lastGuidance
        && now - lastGuidance <= 2500 && path > 0 && segment >= 0) {
      lanes = payload; lanePath = path; laneSegment = segment; lastSent = now;
    }
  }

  public synchronized String guidance(long newPath, int newSegment, long now, int mode) {
    if (mode != 0 || newPath <= 0 || newSegment < 0 || lastGuidance < 0
        || now < lastGuidance || now - lastGuidance > 2500
        || path != newPath || segment != newSegment) clear();
    path = newPath; segment = newSegment; lastGuidance = mode == 0 ? now : -1;
    if (lanes == null || lanePath != path || laneSegment != segment || now - lastSent < 900) return null;
    lastSent = now;
    return lanes;
  }
}
