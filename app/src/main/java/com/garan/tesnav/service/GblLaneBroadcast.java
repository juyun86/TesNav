package com.garan.tesnav.service;
import com.google.gson.*;
import com.garan.tesnav.model.LaneState;
import com.garan.tesnav.util.NavigationMappers;
import java.util.*;
/** Explicit GBL encoding; never interpret these IDs as Auto display icon IDs. */
public final class GblLaneBroadcast {
  public static boolean isNative(String raw) {
    if (raw == null || raw.length()>32768) return false;
    try { return "gbl_lane_action_v1".equals(JsonParser.parseString(raw).getAsJsonObject().get("tesnav_lane_encoding").getAsString()); }
    catch (RuntimeException e) { return false; }
  }
  public static List<LaneState> parse(String raw) {
    if (!isNative(raw)) return null;
    JsonObject obj=JsonParser.parseString(raw).getAsJsonObject();
    if (!obj.get("drive_way_enabled").getAsBoolean()) return Collections.emptyList();
    int count=obj.get("drive_way_size").getAsInt();
    JsonArray lanes=obj.getAsJsonArray("drive_way_info");
    if (count<1 || count>16 || lanes.size()!=count) throw new IllegalArgumentException("lane count");
    List<LaneState> result=new ArrayList<>();
    for (int i=0;i<count;i++) {
      JsonObject lane=lanes.get(i).getAsJsonObject();
      int back=lane.get("gbl_back").getAsInt(), front=lane.get("gbl_front").getAsInt();
      List allowed=NavigationMappers.INSTANCE.navAssistV2LaneActions(back);
      List advised=NavigationMappers.INSTANCE.navAssistV2LaneActions(front);
      // Only confirmed directional enums can recommend a lane. Bus/variable/etc
      // retain their facts but never grant a maneuver through an unknown policy.
      boolean directional=back>=0 && back<=20 && back!=15;
      boolean recommended=directional && front>=0 && front<=20 && front!=15
          && !advised.isEmpty() && allowed.containsAll(advised);
      result.add(new LaneState(i,allowed,recommended,back,
          recommended?advised:Collections.emptyList(),recommended?Integer.valueOf(front):null,false));
    }
    return result;
  }
}
