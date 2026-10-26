package com.autonavi.amapauto.gdarcameraservicedemo;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs the real relay against in-process Binder peers; never binds AMap or a video source. */
public final class SessionRecoveryTestRunner extends Instrumentation {
    private boolean h264Probe;
    private static void field(CameraService service, String name, Object value) throws Exception {
        Field field = CameraService.class.getDeclaredField(name);
        field.setAccessible(true); field.set(service, value);
    }
    private static final class Peer extends Binder {
        final List<Integer> calls = new ArrayList<>();
        int sourceReady = 1;
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            calls.add(code); reply.writeNoException(); reply.writeInt(sourceReady); return true;
        }
    }
    private static void expect(Peer peer, Integer... calls) {
        if (!peer.calls.equals(Arrays.asList(calls)))
            throw new AssertionError("Expected " + Arrays.toString(calls) + ", got " + peer.calls);
        peer.calls.clear();
    }
    @Override public void onCreate(Bundle arguments) {
        h264Probe=arguments!=null && "true".equals(arguments.getString("h264"));
        super.onCreate(arguments); start();
    }
    @Override public void onStart() {
        if (h264Probe) { H264ProbeRunner.run(this);return; }
        Bundle result = new Bundle();
        Parcel configuration = Parcel.obtain();
        try {
            CameraService service = new CameraService();
            Peer peer = new Peer();
            configuration.writeInterfaceToken("com.autonavi.amapauto.gdarcameraservice.IGDCameraService");
            configuration.writeString("com.autonavi.amapauto");
            field(service, "target", peer);
            field(service, "cameraConfiguration", configuration);
            field(service, "cameraCallback", new Binder());
            field(service, "openRequested", true);

            // A live binding survives stream STOP. Explicit reopening must replay all three.
            service.onStartCommand(new Intent(), 0, 1); expect(peer, 7, 1, 8);
            service.onStartCommand(new Intent(), 0, 2); expect(peer, 7, 1, 8);

            // AMap closed the camera: reopening the relay must not invent an open request.
            field(service, "openRequested", false);
            service.onStartCommand(new Intent(), 0, 3); expect(peer, 7, 1);

            // AMap unregistered: no retained subscriber may be revived.
            field(service, "cameraCallback", null);
            field(service, "cameraConfiguration", null);
            service.onStartCommand(new Intent(), 0, 4); expect(peer);

            // Target replacement still uses the same ordered recovery path.
            field(service, "cameraConfiguration", configuration);
            field(service, "cameraCallback", new Binder());
            field(service, "openRequested", true);
            Field connection = CameraService.class.getDeclaredField("connection");
            connection.setAccessible(true);
            ((ServiceConnection) connection.get(service)).onServiceConnected(null, peer);
            expect(peer, 7, 1, 8);
            peer.sourceReady = 0;
            if (service.sourceReady()) throw new AssertionError("Bound service is not a ready video source");
            expect(peer, 5);
            peer.sourceReady = 1;
            if (!service.sourceReady()) throw new AssertionError("Ready video source rejected");
            expect(peer, 5);
            field(service, "target", null);
            if (service.sourceReady()) throw new AssertionError("Missing source accepted");
            int frameChecks = NativeFrameContractChecks.run(getTargetContext());
            result.putString("stream", "5 session recovery, 3 source readiness and " + frameChecks +
                    " native frame contract checks passed; no external bindings or video sources");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", error.toString()); finish(1, result);
        } finally { configuration.recycle(); }
    }
}
