package com.autosdk.drive.navi;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import com.autonavi.gbl.guide.model.LaneInfo;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pinned U8 GBL callback bridge. 60074 is a private diagnostic extension, not an Auto standard. */
public final class TesNavLaneBridge {
  private static final LaneBridgeState state = new LaneBridgeState();
  private static final IdentityHashMap<Object, ArrayList<Object>> observers = new IdentityHashMap<>();
  private static Context context;
  private static int guideMode = -1;
  private static long guidanceAt = -1, statusAt = -1, diagnosisAt = -1, trackAt = -1, sentAt = -1;
  private static boolean entered;
  private static int status = -1, diagnosis = -1, displayMode = -1, positionStatus = -1;
  private static int laneNo = -1, laneCount = 0;
  private static long laneGroup = 0, engineTick = 0;
  private static int observerCount = 0, authorizationState = -1;

  public static synchronized void init(Context c) {
    if (c != null) context = c.getApplicationContext();
  }

  private static Object field(Object o, String name) throws Exception { return o.getClass().getField(name).get(o); }
  private static long number(Object o, String name) throws Exception { return ((Number) field(o, name)).longValue(); }
  private static void broadcast(int type, String key, String payload) {
    if (context == null) return;
    Intent i = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
    i.setPackage("com.garan.tesnav.tenengkai");
    i.putExtra("KEY_TYPE", type); i.putExtra("TYPE", 0); i.putExtra(key, payload);
    context.sendBroadcast(i);
  }

  public static synchronized void send(Context c, LaneInfo info, int mode) {
    try {
      init(c);
      boolean enabled = info != null && info.backLane != null && info.frontLane != null
          && info.backLane.size() > 0 && info.backLane.size() <= 16 && info.frontLane.size() == info.backLane.size();
      JSONArray lanes = new JSONArray();
      if (enabled) for (int i=0; i<info.backLane.size(); i++) {
        lanes.put(new JSONObject().put("gbl_back", info.backLane.get(i)).put("gbl_front", info.frontLane.get(i)));
      }
      String payload = new JSONObject().put("tesnav_lane_encoding", "gbl_lane_action_v1")
          .put("drive_way_enabled", enabled).put("drive_way_size", lanes.length()).put("drive_way_info", lanes).toString();
      state.observe(enabled ? payload : null, SystemClock.elapsedRealtime(), mode);
      Log.i("TesNavLaneBridge", "mode="+mode+" payload="+payload);
      if (mode == 0) broadcast(13012, "EXTRA_DRIVE_WAY", payload);
      if (!enabled) { trackAt = -1; laneNo = -1; }
    } catch (Exception e) { state.clear(); Log.w("TesNavLaneBridge", "lane callback: "+e.getClass().getSimpleName()); }
  }

  /** Called only by a real engine guidance callback; no background refresh of cached lanes. */
  public static synchronized void guidance(ArrayList<?> infos, int mode) {
    try {
      long now = SystemClock.elapsedRealtime();
      Object info = infos == null || infos.isEmpty() ? null : infos.get(0);
      long path = info == null ? -1 : number(info, "pathID");
      int segment = info == null ? -1 : (int) number(info, "curSegIdx");
      String payload = state.guidance(path, segment, now, mode);
      if (guideMode != mode || guidanceAt < 0 || now - guidanceAt > 2500) { trackAt = -1; laneNo = -1; }
      guideMode = mode; guidanceAt = now;
      if (mode != 0 || info == null) return;
      if (payload != null) broadcast(13012, "EXTRA_DRIVE_WAY", payload);
      if (sentAt < 0 || now - sentAt >= 900) { diagnostic(now); sentAt = now; }
    } catch (Exception e) { state.clear(); guideMode = -1; Log.w("TesNavLaneBridge", "guidance: "+e.getClass().getSimpleName()); }
  }

  private static synchronized void callback(String method, Object info) {
    try {
      long now = SystemClock.elapsedRealtime();
      if (method.equals("onLaneStatus")) {
        entered = (Boolean) field(info, "enter"); status = (int) number(info, "status"); statusAt = now;
        if (!entered) { trackAt = -1; laneNo = -1; }
      } else if (method.equals("onLanePositionStatus")) positionStatus = ((Number)info).intValue();
      else if (method.equals("onUpdateNaviDiagnosis")) {
        diagnosis = (int) number(info, "naviState"); displayMode = (int) number(info, "carDisplayMode"); diagnosisAt = now;
      } else if (method.equals("onUpdateDigitalTrack")) {
        Object group = field(info, "currentGroup");
        long tick = number(group, "tickTime");
        // Repeated engine samples must not renew observation freshness.
        if (tick <= 0 || tick == engineTick) return;
        engineTick = tick; trackAt = now;
        laneNo = (int) number(group, "laneNo"); laneCount = (int) number(group, "laneCount"); laneGroup = number(group, "laneGroupId");
      } else return;
      Log.i("TesNavLaneEngine", method+" entered="+entered+" status="+status+" diagnosis="+diagnosis+" display="+displayMode+" laneRaw="+laneNo);
      if (guideMode == 0 && guidanceAt >= 0 && now - guidanceAt <= 2500) diagnostic(now);
    } catch (Exception e) { Log.w("TesNavLaneEngine", "callback: "+e.getClass().getSimpleName()); }
  }

  private static void diagnostic(long now) throws Exception {
    JSONObject obj = new JSONObject().put("encoding", "tesnav_gbl_lane_engine_v1")
        .put("observerCount", observerCount).put("authorizationStateRaw", authorizationState)
        .put("entered", entered).put("status", status).put("statusAgeMs", age(now,statusAt))
        .put("diagnosis", diagnosis).put("carDisplayMode", displayMode).put("diagnosisAgeMs", age(now,diagnosisAt))
        .put("positionStatus", positionStatus).put("laneNoRaw", laneNo).put("laneCountRaw", laneCount)
        .put("laneGroupIdRaw", laneGroup).put("engineTickRaw", engineTick).put("trackAgeMs", age(now,trackAt))
        // CurrentLaneGroup belongs to a navigation digital track. Until its mode/index contract
        // is verified against real car lanes, never label it as measured ego localization.
        .put("egoPositionVerified", false).put("controlAllowed", false);
    broadcast(60074, "EXTRA_LANE_ENGINE", obj.toString());
  }
  private static long age(long now, long stamp) { return stamp < 0 || now < stamp ? -1 : now-stamp; }

  public static void attach(Object service) {
    if (service == null) return;
    ArrayList<Object> keep = new ArrayList<>();
    synchronized (TesNavLaneBridge.class) {
      if (observers.containsKey(service)) return;
      observers.put(service, keep);
    }
    try {
      add(service, "addLaneObserver", "ILaneObserver", keep);
      add(service, "addLaneDiagnosisObserver", "ILaneDiagnosisObserver", keep);
      Object navi = service.getClass().getMethod("getLaneNaviService").invoke(service);
      if (navi != null) add(navi, "addObserver", "ILaneDigitalTrackObserver", keep);
      synchronized (TesNavLaneBridge.class) {
        observerCount = keep.size();
        // Observe the app's existing entitlement result; never request or override authorization.
        try {
          Class<?> cls = Class.forName("com.byd.lane.aos.LaneAosManager");
          Object manager = cls.getMethod("getInstance").invoke(null);
          authorizationState = ((Number) cls.getMethod("getLastLaneState").invoke(manager)).intValue();
        } catch (Exception ignored) { authorizationState = -1; }
        diagnostic(SystemClock.elapsedRealtime());
      }
      Log.i("TesNavLaneEngine", "attached observers="+keep.size());
    } catch (Exception e) {
      // Retain partially registered observers and do not register duplicates on each surface.
      Log.w("TesNavLaneEngine", "attach failed: "+e.getClass().getSimpleName());
    }
  }
  private static void add(Object service, String method, String name, ArrayList<Object> keep) throws Exception {
    Class<?> type = Class.forName("com.autonavi.gbl.lane.observer."+name);
    Object observer = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy,m,args) -> {
      if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
      if (m.getName().equals("equals")) return args != null && args.length == 1 && proxy == args[0];
      if (m.getName().equals("toString")) return "TesNavLaneObserver";
      if (args != null && args.length == 1) callback(m.getName(),args[0]);
      return null;
    });
    service.getClass().getMethod(method, type).invoke(service, observer); keep.add(observer);
  }
}
