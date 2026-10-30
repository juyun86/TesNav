package com.garan.tesnav.ar;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Single-owner, synchronous decoder prototype. Not enabled by the JPEG receiver. */
public final class ArH264Decoder implements Closeable {
    /** Image is borrowed and closed immediately after this call; do not retain it. */
    public interface Sink { void decoded(ArH264Packet packet, Image image) throws IOException; }
    private final Sink sink;
    private final Map<Long, ArH264Packet> pending = new LinkedHashMap<>();
    private MediaCodec codec;
    private byte[] config;
    private String camera;
    private boolean waitingForKeyframe = true;
    private int width, height;
    private String codecName = "";
    private boolean ended;
    public ArH264Decoder(Sink sink) { if (sink == null) throw new IllegalArgumentException(); this.sink=sink; }
    public String codecName() { return codecName; }

    public void accept(ArH264Packet packet) throws IOException {
        if (ended) throw new IOException("AVC decoder already drained");
        if (packet.config) {
            if (config == null || !Arrays.equals(config,packet.annexB) || width!=packet.width || height!=packet.height || !packet.camera.equals(camera)) {
                stopCodec(); config=packet.annexB; width=packet.width; height=packet.height; camera=packet.camera;
            }
            return;
        }
        if (config == null) throw new IOException("Missing AVC configuration");
        if (!packet.camera.equals(camera)) throw new IOException("AVC camera changed without configuration");
        if (packet.width!=width || packet.height!=height) throw new IOException("AVC dimensions changed without configuration");
        if (waitingForKeyframe && !packet.keyframe) return;
        if (codec == null) startCodec();
        waitingForKeyframe=false;
        drain();
        if (pending.size()>=8 || pending.containsKey(packet.ptsUs)) throw new IOException("AVC input backlog or duplicate timestamp");
        try {
            int input=codec.dequeueInputBuffer(10_000);
            if (input<0) throw new IOException("AVC input timeout");
            ByteBuffer buffer=codec.getInputBuffer(input);
            if (buffer == null || buffer.capacity()<packet.annexB.length) throw new IOException("AVC input too large");
            buffer.clear(); buffer.put(packet.annexB);
            pending.put(packet.ptsUs,packet);
            codec.queueInputBuffer(input,0,packet.annexB.length,packet.ptsUs,0);
            drain();
        } catch (RuntimeException failure) { throw new IOException("AVC decoder failed",failure); }
    }
    private void startCodec() throws IOException {
        String selected=null;
        for (MediaCodecInfo info:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            boolean avc=false; for (String type:info.getSupportedTypes()) if ("video/avc".equalsIgnoreCase(type)) avc=true;
            if (!avc) continue;
            String name=info.getName();
            boolean hardware=Build.VERSION.SDK_INT>=29 ? info.isHardwareAccelerated() :
                !name.startsWith("OMX.google.") && !name.startsWith("c2.android.");
            MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType("video/avc");
            boolean flexible=false;
            for (int color:caps.colorFormats) if (color==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) flexible=true;
            if (hardware && flexible && caps.getVideoCapabilities().isSizeSupported(width,height)) { selected=name;break; }
        }
        if (selected==null) throw new IOException("AVC YUV decoder unavailable");
        try {
            MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,2_000_000);
            // Configuration is a validated Annex-B SPS/PPS bundle, delivered as codec data.
            codec=MediaCodec.createByCodecName(selected);codec.configure(format,null,null,0);codec.start();codecName=selected;
            int input=codec.dequeueInputBuffer(100_000);
            if (input<0) throw new IOException("AVC configuration timeout");
            ByteBuffer buffer=codec.getInputBuffer(input);
            if (buffer==null || buffer.capacity()<config.length) throw new IOException("AVC configuration too large");
            buffer.clear();buffer.put(config);
            codec.queueInputBuffer(input,0,config.length,0,MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
        } catch (RuntimeException failure) { stopCodec(); throw new IOException("AVC initialization failed",failure); }
          catch (IOException failure) { stopCodec();throw failure; }
    }
    public void drain() throws IOException {
        if (codec==null) return;
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        try {
            while (true) {
                int index=codec.dequeueOutputBuffer(info,0);
                if (index==MediaCodec.INFO_TRY_AGAIN_LATER) return;
                if (index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue;
                if (index<0) throw new IOException("Unexpected AVC output status");
                try {
                    if ((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0) continue;
                    if (info.size==0 && (info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0) { ended=true;return; }
                    ArH264Packet packet=pending.remove(info.presentationTimeUs);
                    if (packet==null) throw new IOException("AVC output timestamp is unmatched");
                    try (Image image=codec.getOutputImage(index)) {
                        if (image==null || image.getFormat()!=ImageFormat.YUV_420_888 || image.getPlanes().length!=3)
                            throw new IOException("AVC decoder did not expose YUV planes");
                        Rect crop=image.getCropRect();
                        if (crop.width()!=width || crop.height()!=height || ((crop.left|crop.top)&1)!=0)
                            throw new IOException("AVC output crop differs from source contract");
                        sink.decoded(packet,image);
                    }
                    if ((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0) ended=true;
                } finally { codec.releaseOutputBuffer(index,false); }
            }
        } catch (RuntimeException failure) { throw new IOException("AVC output failed",failure); }
    }
    public void finish() throws IOException {
        if (codec==null) return;
        try {
            int input=codec.dequeueInputBuffer(100_000);
            if (input<0) throw new IOException("AVC end-of-stream timeout");
            codec.queueInputBuffer(input,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            long deadline=android.os.SystemClock.elapsedRealtime()+2_000;
            while (!ended && android.os.SystemClock.elapsedRealtime()<deadline) { drain();android.os.SystemClock.sleep(5); }
            if (!ended || !pending.isEmpty()) throw new IOException("AVC output did not fully drain");
        } catch (RuntimeException failure) { throw new IOException("AVC drain failed",failure); }
    }
    private void stopCodec() {
        MediaCodec previous=codec;codec=null;waitingForKeyframe=true;pending.clear();codecName="";ended=false;
        if (previous!=null) {
            try { previous.stop(); } catch (RuntimeException ignored) {}
            finally { previous.release(); }
        }
    }
    @Override public void close() { stopCodec();config=null;camera=null;width=height=0; }
}
