package com.autonavi.amapauto.gdarcameraservicedemo;

import android.app.Instrumentation;
import android.content.Context;
import android.media.Image;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.SharedMemory;
import java.io.DataInputStream;
import java.io.EOFException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Offline captured fixture only. Never binds a camera, navigation app, or C3 service. */
public final class H264ProbeRunner extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args);start(); }
    @Override public void onStart() { run(this); }
    static void run(Instrumentation ownerInstrumentation) {
        Bundle result=new Bundle();Object decoder=null;Method close=null;
        SharedMemory shared=null;ByteBuffer sharedBytes=null;
        try {
            Context owner=ownerInstrumentation.getTargetContext().createPackageContext("com.garan.tesnav.tenengkai",
                Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader loader=owner.getClassLoader();
            Class<?> packet=loader.loadClass("com.garan.tesnav.ar.ArH264Packet");
            Class<?> type=loader.loadClass("com.garan.tesnav.ar.ArH264Decoder");
            Class<?> sink=loader.loadClass("com.garan.tesnav.ar.ArH264Decoder$Sink");
            Class<?> plane=loader.loadClass("com.garan.tesnav.ar.ArYuv420$Plane");
            Constructor<?> planeConstructor=plane.getConstructor(ByteBuffer.class,int.class,int.class);
            Method copy=loader.loadClass("com.garan.tesnav.ar.ArYuv420").getMethod("copy",
                plane,plane,plane,int.class,int.class,int.class,int.class,int.class,int.class,int.class,byte[].class);
            Class<?> senderType=loader.loadClass("com.garan.tesnav.ar.C3ArCameraService");
            Object sender=senderType.getConstructor().newInstance();
            shared=SharedMemory.create("AVC-isolated-test",20+1280*720*3/2);sharedBytes=shared.mapReadWrite();
            for (String name:new String[]{"width","height","format","opened","memory"}) {
                java.lang.reflect.Field field=senderType.getDeclaredField(name);field.setAccessible(true);
                field.set(sender,name.equals("width") ? 1280 : name.equals("height") ? 720 : name.equals("format") ? 4 : name.equals("opened") ? true : sharedBytes);
            }
            Method publishYuv=senderType.getDeclaredMethod("publishYuv",Image.class);publishYuv.setAccessible(true);
            ByteBuffer memory=sharedBytes;
            int[] count={0};long[] total={0},latency={0},maximum={0},copyNanos={0},availableDelay={0};byte[][] scratch={null};
            Map<Long,Long> queued=new HashMap<>();
            Object receiver=Proxy.newProxyInstance(loader,new Class<?>[]{sink},(proxy,method,args)->{
                if (!method.getName().equals("decoded")) throw new AssertionError("Unexpected sink method");
                Object meta=args[0];Image image=(Image)args[1];
                long copyStart=SystemClock.elapsedRealtimeNanos();
                int width=packet.getField("width").getInt(meta),height=packet.getField("height").getInt(meta);
                if (width!=1280 || height!=720) throw new AssertionError("Decoded dimensions changed");
                Image.Plane[] planes=image.getPlanes();Object[] views=new Object[3];
                for (int i=0;i<3;i++) views[i]=planeConstructor.newInstance(planes[i].getBuffer(),planes[i].getRowStride(),planes[i].getPixelStride());
                if (scratch[0]==null) scratch[0]=new byte[width*height*3/2];
                android.graphics.Rect crop=image.getCropRect();
                copy.invoke(null,views[0],views[1],views[2],image.getWidth(),image.getHeight(),crop.left,crop.top,width,height,4,scratch[0]);
                copyNanos[0]+=SystemClock.elapsedRealtimeNanos()-copyStart;
                if (count[0]<3) {
                    memory.put(0,(byte)1);publishYuv.invoke(sender,image);
                    if (memory.get(0)!=0) throw new AssertionError("YUV publication did not signal consumer");
                    byte[] actual=new byte[scratch[0].length];ByteBuffer view=memory.duplicate();view.position(20);view.get(actual);
                    if (!java.util.Arrays.equals(actual,scratch[0])) throw new AssertionError("Recorder YV12 differs from decoder planes");
                }
                long pts=packet.getField("ptsUs").getLong(meta);Long at=queued.remove(pts);
                if (at==null) throw new AssertionError("Output frame has no matching input");
                long delay=SystemClock.elapsedRealtime()-at;latency[0]+=delay;maximum[0]=Math.max(maximum[0],delay);
                availableDelay[0]+=copyStart/1_000_000-at;
                count[0]++;total[0]+=scratch[0].length;return null;
            });
            decoder=type.getConstructor(sink).newInstance(receiver);close=type.getMethod("close");
            Method parse=packet.getMethod("decode",byte[].class,String.class),accept=type.getMethod("accept",packet);
            int inputs=0;long start=0,firstPts=0;
            try (DataInputStream stream=new DataInputStream(ownerInstrumentation.getContext().getAssets().open("h264-probe.cah"))) {
                while (true) {
                    int size;try { size=stream.readInt(); } catch (EOFException eof) { break; }
                    if (size<12 || size>2_016_392) throw new AssertionError("Fixture packet size");
                    byte[] bytes=new byte[size];stream.readFully(bytes);Object meta=parse.invoke(null,bytes,"3");
                    boolean config=packet.getField("config").getBoolean(meta);long pts=packet.getField("ptsUs").getLong(meta);
                    if (!config) {
                        if (inputs==0) { start=SystemClock.elapsedRealtime();firstPts=pts; }
                        long target=start+(pts-firstPts)/1000,remaining=target-SystemClock.elapsedRealtime();
                        if (remaining>0) SystemClock.sleep(remaining);
                        queued.put(pts,SystemClock.elapsedRealtime());inputs++;
                    }
                    accept.invoke(decoder,meta);
                }
            }
            type.getMethod("finish").invoke(decoder);
            if (inputs<30 || count[0]!=inputs || !queued.isEmpty()) throw new AssertionError("Decoded "+count[0]+" of "+inputs);
            result.putString("codec",(String)type.getMethod("codecName").invoke(decoder));
            result.putInt("decodedFrames",count[0]);result.putLong("yv12BytesCopied",total[0]);
            result.putDouble("replayInputToYuvMeanMs",latency[0]/(double)count[0]);result.putLong("replayInputToYuvMaxMs",maximum[0]);
            result.putDouble("yuvCopyMeanMs",copyNanos[0]/(double)count[0]/1_000_000);
            result.putDouble("inputToImageAvailableMeanMs",availableDelay[0]/(double)count[0]);
            result.putInt("isolatedRecorderFramesVerified",3);
            result.putString("scope","offline real-camera fixture; no C3/network/AMap bindings");ownerInstrumentation.finish(-1,result);
        } catch (Throwable failure) {
            java.io.StringWriter trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("error",trace.toString());ownerInstrumentation.finish(1,result);
        } finally {
            if (decoder!=null && close!=null) try {close.invoke(decoder);} catch (Exception ignored) {}
            if (sharedBytes!=null) SharedMemory.unmap(sharedBytes);
            if (shared!=null) shared.close();
        }
    }
}
