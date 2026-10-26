package com.garan.tesnav.ar;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Optional envelope; native AMap still receives only the decoded, unpainted camera image. */
public final class ArVideoPacket {
    public final byte[] jpeg;
    public final String geometry;
    private ArVideoPacket(byte[] jpeg, String geometry) { this.jpeg = jpeg; this.geometry = geometry; }
    public static ArVideoPacket decode(byte[] payload, String protocol) throws IOException {
        if (payload == null || !ArFrameFormat.validJpegLength(payload.length)) throw new IOException("Invalid packet size");
        if (protocol == null || protocol.isEmpty()) return new ArVideoPacket(payload, "");
        if (!"2".equals(protocol) || payload.length < 12 || payload[0] != 'C' || payload[1] != 'A' ||
                payload[2] != 'R' || payload[3] != '2') throw new IOException("Invalid geometry envelope");
        int length = ByteBuffer.wrap(payload, 4, 4).getInt();
        if (length < 2 || length > 16_384 || length + 8 >= payload.length) throw new IOException("Invalid geometry length");
        return new ArVideoPacket(Arrays.copyOfRange(payload, 8 + length, payload.length),
                new String(payload, 8, length, StandardCharsets.UTF_8));
    }
}
