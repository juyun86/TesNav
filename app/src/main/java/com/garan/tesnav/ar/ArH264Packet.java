package com.garan.tesnav.ar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Version 3 Annex-B access unit. Version 2 JPEG packets remain a distinct protocol. */
public final class ArH264Packet {
    public final byte[] annexB;
    public final String metadata;
    public final String geometry;
    public final String camera;
    public final boolean config, keyframe;
    public final int width, height;
    public final long frameId, timestampEofNs, ptsUs;
    private ArH264Packet(byte[] data, String json, JsonObject root) {
        annexB=data; metadata=json; camera=root.get("camera").getAsString(); geometry=root.has("geometry") ? root.getAsJsonObject("geometry").toString() : "";
        config=root.get("config").getAsBoolean(); keyframe=root.get("keyframe").getAsBoolean();
        width=(int)integer(root,"width"); height=(int)integer(root,"height");
        frameId=integer(root,"frameId"); timestampEofNs=integer(root,"timestampEofNs"); ptsUs=integer(root,"ptsUs");
    }
    private static long integer(JsonObject root, String name) {
        if (!root.get(name).getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected number");
        return root.get(name).getAsBigDecimal().longValueExact();
    }
    private static int startCode(byte[] data, int at) {
        if (at+3<data.length && data[at]==0 && data[at+1]==0 && data[at+2]==0 && data[at+3]==1) return 4;
        if (at+2<data.length && data[at]==0 && data[at+1]==0 && data[at+2]==1) return 3;
        return 0;
    }
    private static void validateNals(byte[] data, boolean config, boolean keyframe) {
        boolean sps=false,pps=false,idr=false,slice=false;
        int at=0;
        while (at<data.length) {
            int prefix=startCode(data,at), begin=at+prefix;
            if (prefix==0 || begin>=data.length || (data[begin]&128)!=0) throw new IllegalArgumentException();
            int type=data[begin]&31;
            if (type==0 || type>23) throw new IllegalArgumentException();
            sps|=type==7;pps|=type==8;idr|=type==5;slice|=type>=1 && type<=5;
            at=begin+1;
            while (at<data.length && startCode(data,at)==0) at++;
        }
        if (config ? !sps || !pps || slice || keyframe : !slice || keyframe!=idr) throw new IllegalArgumentException();
    }
    public static ArH264Packet decode(byte[] payload, String protocol) throws IOException {
        try {
            if (!"3".equals(protocol) || payload == null || payload.length < 14 || payload.length > 2_016_392 ||
                    payload[0]!='C' || payload[1]!='A' || payload[2]!='H' || payload[3]!='3') throw new IllegalArgumentException();
            int length=ByteBuffer.wrap(payload,4,4).getInt();
            if (length < 2 || length > 16_384 || length+12 >= payload.length) throw new IllegalArgumentException();
            String json=new String(payload,8,length,StandardCharsets.UTF_8);
            JsonObject root=JsonParser.parseString(json).getAsJsonObject();
            if (integer(root,"version")!=3 || !"h264".equals(root.get("codec").getAsString()) ||
                    !root.get("config").getAsJsonPrimitive().isBoolean() || !root.get("keyframe").getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException();
            long width=integer(root,"width"),height=integer(root,"height");
            if (width<64 || width>4096 || height<64 || height>2160 || ((width|height)&1)!=0)
                throw new IllegalArgumentException();
            ArH264Packet packet=new ArH264Packet(Arrays.copyOfRange(payload,8+length,payload.length),json,root);
            if (packet.width < 64 || packet.height < 64 || packet.width > 4096 || packet.height > 2160 ||
                    ((packet.width|packet.height)&1)!=0 || packet.frameId < 0 || packet.frameId > 0xffffffffL ||
                    packet.ptsUs<0 || (packet.config && packet.timestampEofNs!=0) ||
                    (!packet.config && (packet.timestampEofNs<=0 || packet.timestampEofNs/1000 != packet.ptsUs)))
                throw new IllegalArgumentException();
            String camera=root.get("camera").getAsString();
            if (!"wideRoad".equals(camera) && !"narrowRoad".equals(camera))
                throw new IllegalArgumentException();
            if (root.has("geometry")) {
                JsonObject geometry=root.getAsJsonObject("geometry");
                if (integer(geometry,"version")!=1 || integer(geometry,"frameId")!=packet.frameId ||
                        !camera.equals(geometry.get("camera").getAsString()) ||
                        !"native".equals(geometry.get("inputMode").getAsString())) throw new IllegalArgumentException();
            }
            validateNals(packet.annexB,packet.config,packet.keyframe);
            return packet;
        } catch (RuntimeException invalid) { throw new IOException("Invalid H264 packet",invalid); }
    }
}
