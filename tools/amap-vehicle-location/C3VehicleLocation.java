import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.os.SystemClock;
import android.util.Log;
import com.autonavi.amapauto.location.model.LocSignData;

/** Paired Auto patch. Uses the existing GNSS adapter, never the global mock provider. */
public final class C3VehicleLocation {
    private static final String TAG = "C3VehicleLocation";
    private static final String ACTION = "com.garan.tesnav.tenengkai.VEHICLE_LOCATION";
    private static Context registered;
    private static long activeElapsed = -1, activeWall = -1, lastObserved = -1;
    private static final ThreadLocal<Location> injecting = new ThreadLocal<>();

    public static synchronized void register(Context context) {
        activeElapsed = activeWall = -1;
        if (context == null || registered != null) return;
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { receive(i); }
            }, new IntentFilter(ACTION), "com.garan.tesnav.tenengkai.permission.AR_BRIDGE", null);
            registered = context;
            Log.i(TAG, "receiver-ready permission=signature coordinates=GCJ02");
        } catch (RuntimeException e) { Log.e(TAG, "registration-failed", e); }
    }

    public static boolean valid(long elapsed, long wall, long nowElapsed, long nowWall,
            double latitude, double longitude, double accuracy, double speed, double bearing) {
        return elapsed >= 0 && elapsed <= nowElapsed && nowElapsed-elapsed < 3000 &&
            wall > 0 && wall <= nowWall && nowWall-wall < 3000 &&
            Double.isFinite(latitude) && latitude >= -90 && latitude <= 90 &&
            Double.isFinite(longitude) && longitude >= -180 && longitude <= 180 &&
            Double.isFinite(accuracy) && accuracy > 0 && accuracy <= 50 &&
            Double.isFinite(speed) && speed >= 0 && speed <= 83.333333 &&
            Double.isFinite(bearing) && bearing >= 0 && bearing < 360;
    }

    private static synchronized void receive(Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        if (!intent.getBooleanExtra("valid", false)) {
            activeElapsed = activeWall = -1;
            Log.i(TAG, "source=tablet reason=vehicle-unavailable");
            return;
        }
        long elapsed = intent.getLongExtra("observedElapsedMs", -1);
        long wall = intent.getLongExtra("observedWallMs", -1);
        double lat = intent.getDoubleExtra("latitude", Double.NaN);
        double lon = intent.getDoubleExtra("longitude", Double.NaN);
        double accuracy = intent.getDoubleExtra("accuracyM", Double.NaN);
        double speed = intent.getDoubleExtra("speedMps", Double.NaN);
        double bearing = intent.getDoubleExtra("bearingDeg", Double.NaN);
        if (!"GCJ02".equals(intent.getStringExtra("coordinateSystem")) ||
                !valid(elapsed, wall, SystemClock.elapsedRealtime(), System.currentTimeMillis(),
                    lat, lon, accuracy, speed, bearing) || elapsed <= lastObserved) return;
        Location fix = new Location("gps");
        double[] wgs = toWgs84(lon, lat);
        if (wgs == null) return;
        fix.setLatitude(wgs[1]); fix.setLongitude(wgs[0]); fix.setAccuracy((float)accuracy);
        fix.setSpeed((float)speed); fix.setBearing((float)bearing);
        fix.setTime(wall); fix.setElapsedRealtimeNanos(elapsed*1000000L);
        // No height is supplied by CAN; preserve the last independent tablet height when available.
        if (tabletAltitude != null) fix.setAltitude(tabletAltitude);
        injecting.set(fix);
        try {
            vr.g().a(fix);
            lastObserved = elapsed;
            activeElapsed = elapsed; activeWall = wall;
        } catch (RuntimeException | LinkageError e) {
            activeElapsed = activeWall = -1;
            Log.e(TAG, "submit-failed; tablet-fallback", e);
        } finally { injecting.remove(); }
    }

    private static Double tabletAltitude;
    public static synchronized boolean skipTablet(Location location) {
        if (injecting.get() == location && location != null) return false;
        if (location != null && location.hasAltitude()) tabletAltitude = location.getAltitude();
        long now = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
        boolean fresh = activeElapsed >= 0 && now >= activeElapsed && now-activeElapsed < 3000 &&
            wall >= activeWall && wall-activeWall < 3000;
        if (!fresh && activeElapsed >= 0) {
            activeElapsed = activeWall = -1;
            Log.i(TAG, "source=tablet reason=vehicle-expired");
        }
        return fresh;
    }

    /** Called immediately before the original PosService.setSignInfo, on the same thread. */
    public static void prepare(LocSignData data) {
        Location fix = injecting.get();
        if (fix == null) return;
        data.gnss.isEncrypted = 0; // The shared GNSS/AR path consumes WGS-84; conversion is explicit above.
        // tickTime is stamped by Auto's adapter on submission, not a CAN observation clock.
        // Observation age is checked before submission; do not backdate the engine's clock.
    }

    public static void submitted(LocSignData data) {
        if (injecting.get() == null) return;
        Log.i(TAG, "source=vehicle submitted-native tick=" + data.gnss.tickTime +
            " lat=" + data.gnss.lat + " lon=" + data.gnss.lon +
            " course=" + data.gnss.course + " speedKph=" + data.gnss.speed +
            " gcj=" + data.gnss.isEncrypted);
    }

    /** Same iterative inverse as C3 map_coordinates.py; coordtransform MIT notice accompanies this file. */
    public static double[] toWgs84(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude) ||
                Math.abs(longitude)>180 || Math.abs(latitude)>90) return null;
        if (!(longitude>73.66 && longitude<135.05 && latitude>3.86 && latitude<53.55))
            return new double[]{longitude, latitude};
        double lon=longitude, lat=latitude;
        for (int i=0; i<8; i++) {
            double x=lon-105, y=lat-35, pi=Math.PI;
            double dy=-100+2*x+3*y+.2*y*y+.1*x*y+.2*Math.sqrt(Math.abs(x));
            double dx=300+x+2*y+.1*x*x+.1*x*y+.1*Math.sqrt(Math.abs(x));
            double common=(20*Math.sin(6*x*pi)+20*Math.sin(2*x*pi))*2/3;
            dy+=common+(20*Math.sin(y*pi)+40*Math.sin(y/3*pi))*2/3;
            dy+=(160*Math.sin(y/12*pi)+320*Math.sin(y/30*pi))*2/3;
            dx+=common+(20*Math.sin(x*pi)+40*Math.sin(x/3*pi))*2/3;
            dx+=(150*Math.sin(x/12*pi)+300*Math.sin(x/30*pi))*2/3;
            double rad=Math.toRadians(lat), magic=1-.00669342162296594323*Math.pow(Math.sin(rad),2);
            double root=Math.sqrt(magic);
            double ex=lon+dx*180/(6378245/root*Math.cos(rad)*pi)-longitude;
            double ey=lat+dy*180/(6378245*(1-.00669342162296594323)/(magic*root)*pi)-latitude;
            lon-=ex; lat-=ey;
            if (Math.max(Math.abs(ex),Math.abs(ey))<1e-9) return new double[]{lon,lat};
        }
        return null;
    }
}
