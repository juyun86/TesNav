package com.autonavi.amapauto.gdarcameraservicedemo;

import android.app.Service;
import android.content.*;
import android.os.*;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Fixed-package discovery shim only. Frames, configuration and lifetime belong to 特会开. */
public final class CameraService extends Service {
    static volatile boolean ready;
    private static volatile CameraService current;
    private volatile IBinder target;
    private final CountDownLatch connected = new CountDownLatch(1);
    private boolean bound;
    private final Object session = new Object();
    private IBinder cameraCallback;
    private Parcel cameraConfiguration;
    private boolean openRequested;
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            target = binder;
            // AMap can retain this relay across a 特会开 process replacement.
            // Restore only its existing camera session, never start a new one.
            restoreSession(binder);
            ready = true;
            connected.countDown();
        }
        public void onServiceDisconnected(ComponentName name) { target = null; ready = false; }
        public void onBindingDied(ComponentName name) {
            target = null; ready = false;
            // Android does not reconnect a binding invalidated by an APK replacement.
            if (bound) unbindService(this);
            bound = false;
            bindTarget();
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        current = this;
        ready = false;
        bindTarget();
    }
    static boolean cameraReady() {
        CameraService service = current;
        return ready && service != null && service.sourceReady();
    }
    boolean sourceReady() {
        IBinder binder = target;
        if (binder == null) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.autonavi.amapauto.gdarcameraservice.IGDCameraService");
            data.writeString("com.autonavi.amapauto");
            if (!binder.transact(5, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() == 1;
        } catch (RemoteException | RuntimeException error) { return false; }
        finally { data.recycle(); reply.recycle(); }
    }
    private void bindTarget() {
        Intent intent = new Intent().setComponent(new ComponentName("com.garan.tesnav.tenengkai",
                "com.garan.tesnav.ar.C3ArCameraService"));
        try { bound = bindService(intent, connection, BIND_AUTO_CREATE); }
        catch (SecurityException ignored) { connected.countDown(); }
        if (!bound) connected.countDown();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Explicit foreground ConnectActivity can repair a denied/stale association.
        if (target == null) {
            if (bound) unbindService(connection);
            bound = false; bindTarget();
        } else {
            // A stopped video stream clears the target callback without killing this
            // binding. An explicit user reopen must restore that retained AMap session too.
            restoreSession(target);
        }
        stopSelf(startId); // Bindings alone own the relay lifetime.
        return START_NOT_STICKY;
    }
    private void restoreSession(IBinder binder) {
        synchronized (session) {
            try {
                if (cameraConfiguration != null) forward(binder, 7, cameraConfiguration);
                if (cameraCallback != null && cameraCallback.isBinderAlive()) {
                    Parcel data = Parcel.obtain();
                    try {
                        data.writeInterfaceToken("com.autonavi.amapauto.gdarcameraservice.IGDCameraService");
                        data.writeString("com.autonavi.amapauto"); data.writeStrongBinder(cameraCallback);
                        forward(binder, 1, data);
                    } finally { data.recycle(); }
                    if (openRequested) {
                        data = Parcel.obtain();
                        try {
                            data.writeInterfaceToken("com.autonavi.amapauto.gdarcameraservice.IGDCameraService");
                            data.writeString("com.autonavi.amapauto"); forward(binder, 8, data);
                        } finally { data.recycle(); }
                    }
                }
            } catch (RemoteException | RuntimeException error) {
                android.util.Log.w("Tenengkai-ArRelay", "restore_failed type="+error.getClass().getSimpleName());
            }
        }
    }
    private final Binder relay = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            String[] packages = getPackageManager().getPackagesForUid(Binder.getCallingUid());
            if (packages == null || !Arrays.asList(packages).contains("com.autonavi.amapauto"))
                throw new SecurityException("AMap Auto only");
            try {
                if (!connected.await(3, TimeUnit.SECONDS) || target == null)
                    throw new RemoteException("Open the AR page in 特会开 first");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RemoteException("AR binding interrupted");
            }
            // Only the authenticated AMap caller can replace the session to recover.
            synchronized (session) {
                int position = data.dataPosition();
                try {
                    data.enforceInterface("com.autonavi.amapauto.gdarcameraservice.IGDCameraService");
                    data.readString();
                    if (code == 1) cameraCallback = data.readStrongBinder();
                    else if (code == 2) {
                        cameraCallback = null; openRequested = false;
                        if (cameraConfiguration != null) cameraConfiguration.recycle();
                        cameraConfiguration = null;
                    } else if (code == 7) {
                        if (cameraConfiguration != null) cameraConfiguration.recycle();
                        cameraConfiguration = Parcel.obtain();
                        cameraConfiguration.appendFrom(data, 0, data.dataSize());
                    } else if (code == 8) openRequested = true;
                    else if (code == 9 || code == 10) openRequested = false;
                } finally { data.setDataPosition(position); }
            }
            // The target authenticates this same-signature shim; video never passes through it.
            return target.transact(code, data, reply, flags);
        }
    };
    private void forward(IBinder binder, int code, Parcel data) throws RemoteException {
        Parcel reply = Parcel.obtain();
        try {
            data.setDataPosition(0);
            if (!binder.transact(code, data, reply, 0)) throw new RemoteException("Camera session rejected");
            reply.readException();
        } finally { reply.recycle(); }
    }
    @Override public IBinder onBind(Intent intent) { return relay; }
    @Override public void onDestroy() {
        if (current == this) current = null;
        target = null; ready = false;
        synchronized (session) {
            cameraCallback = null; openRequested = false;
            if (cameraConfiguration != null) cameraConfiguration.recycle();
            cameraConfiguration = null;
        }
        if (bound) unbindService(connection);
        super.onDestroy();
    }
}
