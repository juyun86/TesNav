package com.garan.tesnav.ar;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Pixel intrinsics for the exact decoded native frame; no screen/viewport coordinates. */
public final class ArCameraCalibration {
    public static byte[] encode(ArH264Packet packet, long elapsedMs) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(packet.metadata).getAsJsonObject();
            JsonObject geometry = root.getAsJsonObject("geometry");
            JsonObject calibration = geometry.getAsJsonObject("cameraCalibration");
            if (packet.config || elapsedMs <= 0 || packet.width != 1280 || packet.height != 720 ||
                    !"narrowRoad".equals(root.get("camera").getAsString()) ||
                    !"native".equals(geometry.get("inputMode").getAsString()) ||
                    calibration.get("version").getAsInt() != 1 ||
                    !"ox03c10".equals(calibration.get("sensor").getAsString())) throw new IllegalArgumentException();
            JsonArray size=calibration.getAsJsonArray("sourceSize"), crop=calibration.getAsJsonArray("crop"), k=calibration.getAsJsonArray("intrinsics");
            if (size.size()!=2 || crop.size()!=4 || k.size()!=4 ||
                    size.get(0).getAsInt()!=root.get("sourceWidth").getAsInt() ||
                    size.get(1).getAsInt()!=root.get("sourceHeight").getAsInt() ||
                    !crop.equals(root.getAsJsonArray("crop"))) throw new IllegalArgumentException();
            double l=crop.get(0).getAsDouble(),t=crop.get(1).getAsDouble(),r=crop.get(2).getAsDouble(),b=crop.get(3).getAsDouble();
            // Current AVC path is a pixel crop, with no rescale or letterboxing.
            if (!(l>=0 && t>=0 && r<=size.get(0).getAsInt() && b<=size.get(1).getAsInt() &&
                    r-l==packet.width && b-t==packet.height)) throw new IllegalArgumentException();
            double fx=k.get(0).getAsDouble(),fy=k.get(1).getAsDouble(),cx=k.get(2).getAsDouble()-l,cy=k.get(3).getAsDouble()-t;
            if (!(Double.isFinite(fx) && Double.isFinite(fy) && fx>=100 && fy>=100 && fx<=10000 && fy<=10000 &&
                    cx>=0 && cx<packet.width && cy>=0 && cy<packet.height)) throw new IllegalArgumentException();
            double height = 0;
            if (calibration.has("heightM") && calibration.has("heightStatus") &&
                    "calibrated".equals(calibration.get("heightStatus").getAsString())) {
                double candidate = calibration.get("heightM").getAsDouble();
                if (Double.isFinite(candidate) && candidate >= 0.5 && candidate <= 3.0) height = candidate;
            }
            return ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
                    .put("C3KAMAP1".getBytes(StandardCharsets.US_ASCII)).putLong(elapsedMs)
                    .putInt(packet.width).putInt(packet.height).putDouble(fx).putDouble(fy)
                    .putDouble(cx).putDouble(cy).putDouble(height).putLong(packet.frameId).putLong(0).array();
        } catch (RuntimeException invalid) { throw new IOException("Invalid camera calibration",invalid); }
    }
    public static boolean hasValidHeight(byte[] encoded) {
        if (encoded == null || encoded.length != 80) return false;
        double height = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getDouble(56);
        return Double.isFinite(height) && height >= 0.5 && height <= 3.0;
    }
    private ArCameraCalibration() {}
}
