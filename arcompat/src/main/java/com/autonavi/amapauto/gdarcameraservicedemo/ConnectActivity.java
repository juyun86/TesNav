package com.autonavi.amapauto.gdarcameraservicedemo;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.*;
import android.os.*;

/** User-initiated foreground handshake for devices that restrict associated services. */
public final class ConnectActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean bound;
    private long deadline;
    private ProgressDialog progress;
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (CameraService.cameraReady()) { setResult(RESULT_OK); finish(); }
            else if (SystemClock.elapsedRealtime() >= deadline) { setResult(RESULT_CANCELED); finish(); }
            else handler.postDelayed(this, 250);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { handler.post(check); }
        @Override public void onServiceDisconnected(ComponentName name) { setResult(RESULT_CANCELED); finish(); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        deadline = SystemClock.elapsedRealtime()+7_000;
        progress = new ProgressDialog(this);
        progress.setMessage("正在连接高德原生 AR，等待 C3 实时画面…");
        progress.setOnCancelListener(dialog -> finish());
        progress.show();
        try {
            startForegroundService(new Intent("NATIVE_START").setComponent(new ComponentName(
                "com.garan.tesnav.tenengkai", "com.garan.tesnav.ar.C3ArCameraService")));
            startService(new Intent(this, CameraService.class));
            bound = bindService(new Intent(this, CameraService.class), connection, BIND_AUTO_CREATE);
            if (!bound) finish();
            handler.postDelayed(check, 7_000);
        } catch (RuntimeException error) { setResult(RESULT_CANCELED); finish(); }
    }
    @Override public void onDestroy() {
        handler.removeCallbacks(check);
        if (progress != null) progress.dismiss();
        if (bound) unbindService(connection);
        super.onDestroy();
    }
}
