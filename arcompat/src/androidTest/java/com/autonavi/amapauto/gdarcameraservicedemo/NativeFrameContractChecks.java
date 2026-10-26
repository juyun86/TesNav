package com.autonavi.amapauto.gdarcameraservicedemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Binder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/** Isolated production sender instance; synthetic pixels never leave this test process. */
final class NativeFrameContractChecks {
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(Class<?> type, String name, Class<?>... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, arguments); method.setAccessible(true); return method;
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static final class Consumer extends Binder {
        int opened;
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            check(code == 3, "Unexpected callback");
            data.enforceInterface("com.autonavi.amapauto.gdarcameraservice.IGDCameraStateCallBack");
            check(data.readInt() == 1, "Missing shared descriptor");
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.CREATOR.createFromParcel(data)) {
                check(descriptor.getFileDescriptor().valid(), "Invalid shared descriptor");
                check(data.readInt() == 1, "Missing open result");
                check(data.readInt() == 4, "Expected native YV12 wire format");
                data.readInt(); data.readInt();
                check(data.readInt() == 4 && data.readInt() == 2, "Wrong frame dimensions");
                data.readString();
                check(data.readInt() == 12, "Wrong payload size");
                check("C3AR".equals(data.readString()), "Wrong shared memory name");
            } catch (java.io.IOException error) { throw new AssertionError(error); }
            opened++; reply.writeNoException(); return true;
        }
    }
    static int run(Context context) throws Exception {
        Context owner = context.createPackageContext("com.garan.tesnav.tenengkai",
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        Class<?> type = owner.getClassLoader().loadClass("com.garan.tesnav.ar.C3ArCameraService");
        Object sender = type.getConstructor().newInstance();
        Consumer consumer = new Consumer();
        Bitmap image = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
        Bitmap square = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        Method open = method(type, "openMemory", int.class);
        Method publish = method(type, "publish", Bitmap.class);
        Method close = method(type, "closeMemory");
        try {
            field(type, "width").setInt(sender, 4); field(type, "height").setInt(sender, 2);
            field(type, "callback").set(sender, consumer);
            field(type, "callbackHandler").set(sender, new android.os.Handler(android.os.Looper.getMainLooper()));
            field(type, "sourceReady").setBoolean(sender, true);
            field(type, "openRequested").setBoolean(sender, true);
            open.invoke(sender, 0);
            check(consumer.opened == 1 && field(type, "opened").getBoolean(sender), "Open handshake failed");
            ByteBuffer memory = (ByteBuffer) field(type, "memory").get(sender);
            check(memory.capacity() == 32 && memory.get(0) == 1, "Header must allow producer write");
            int before = field(type, "published").getInt(null);
            image.eraseColor(Color.RED); publish.invoke(sender, image);
            check(memory.get(0) == 0, "Consumer read flag missing");
            for (int i = 20; i < 28; i++) check((memory.get(i) & 255) == 82, "Y plane mismatch");
            check((memory.get(28) & 255) == 240 && (memory.get(30) & 255) == 90, "V/U planes swapped");
            publish.invoke(sender, image);
            check(field(type, "published").getInt(null) == before + 1, "Unconsumed frame overwritten");
            memory.put(0, (byte) 1); square.eraseColor(Color.RED); publish.invoke(sender, square);
            int[] expectedY = {16, 82, 82, 16, 16, 82, 82, 16};
            for (int i = 0; i < 8; i++) check((memory.get(20 + i) & 255) == expectedY[i], "Aspect fit changed");
            check(field(type, "published").getInt(null) == before + 2, "Consumer acknowledgement ignored");
            close.invoke(sender);
            check(!field(type, "opened").getBoolean(sender) && field(type, "memory").get(sender) == null,
                    "Closed session retains memory");
            int generation = field(type, "openGeneration").getInt(sender);
            field(type, "sourceReady").setBoolean(sender, false); open.invoke(sender, generation);
            check(consumer.opened == 1, "Unavailable source reopened");
            field(type, "sourceReady").setBoolean(sender, true);
            field(type, "openRequested").setBoolean(sender, false); open.invoke(sender, generation);
            check(consumer.opened == 1, "Closed request reopened");
            field(type, "openRequested").setBoolean(sender, true); open.invoke(sender, generation - 1);
            check(consumer.opened == 1, "Stale generation reopened");
            Method selectMode = method(type, "selectInputMode", String.class);
            field(type, "running").setBoolean(null, true);
            field(type, "nativeOwned").setBoolean(sender, true);
            check((Boolean)selectMode.invoke(sender, "ROAD_START"), "Native to enhanced must reconnect");
            check(!field(type, "nativeOwned").getBoolean(sender) && field(type, "roadOwned").getBoolean(sender)
                    && !field(type, "nativeRequest").getBoolean(sender), "Enhanced retained native input");
            check(!(Boolean)selectMode.invoke(sender, "ROAD_START"), "Same mode reconnects unnecessarily");
            check((Boolean)selectMode.invoke(sender, "NATIVE_START"), "Enhanced to native must reconnect");
            check(field(type, "nativeOwned").getBoolean(sender) && !field(type, "roadOwned").getBoolean(sender)
                    && field(type, "nativeRequest").getBoolean(sender), "Native mode ownership missing");
            field(type, "running").setBoolean(null, false);
            selectMode.invoke(sender, "ROAD_START");
            check(!field(type, "nativeOwned").getBoolean(sender), "Stopped native session leaked into enhanced");
            return 9;
        } finally { close.invoke(sender); image.recycle(); square.recycle(); }
    }
}
