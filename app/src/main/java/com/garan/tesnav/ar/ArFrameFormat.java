package com.garan.tesnav.ar;

import java.net.URI;

/** Pure frame/endpoint checks shared with local regression tests. No Android or navigation state. */
public final class ArFrameFormat {
    private ArFrameFormat() {}
    /** IGDCameraService uses 0 for NV21; our converter uses 1. */
    public static int recorderStorageFormat(int format) {
        if (format == 0) return 1;
        if (format == 4) return 4;
        throw new IllegalArgumentException("Unsupported recorder format");
    }
    public static boolean validEndpoint(String endpoint) {
        try {
            URI uri = new URI(endpoint);
            String host = uri.getHost();
            return "http".equals(uri.getScheme()) && host != null && uri.getUserInfo() == null &&
                    uri.getQuery() == null && uri.getFragment() == null && "/frames".equals(uri.getPath()) &&
                    validPrivateIpv4(host) && uri.getPort() > 0 && uri.getPort() <= 65535;
        } catch (Exception ignored) { return false; }
    }
    public static String endpointForHost(String endpoint, String host) {
        try {
            if (!validEndpoint(endpoint) || !validPrivateIpv4(host)) throw new IllegalArgumentException("Invalid AR endpoint");
            URI current = new URI(endpoint);
            String resolved = new URI("http", null, host, current.getPort(), "/frames", null, null).toString();
            if (!validEndpoint(resolved)) throw new IllegalArgumentException("Invalid discovered AR endpoint");
            return resolved;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Invalid AR endpoint", error); }
    }
    private static boolean validPrivateIpv4(String host) {
        return host != null && host.matches("[0-9.]+") && validIpv4(host) &&
                (host.startsWith("192.168.") || host.startsWith("10.") ||
                    host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..+"));
    }
    private static boolean validIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            int value = Integer.parseInt(part);
            if (value > 255) return false;
        }
        return true;
    }
    public static int imageSize(int width, int height, int format) {
        if (width < 2 || height < 2 || width > 1920 || height > 1208 ||
                width % 2 != 0 || height % 2 != 0 || (format != 1 && format != 4))
            throw new IllegalArgumentException("Unsupported AR image parameters");
        return width * height * 3 / 2;
    }
    public static boolean validJpegLength(int length) { return length > 0 && length <= 4_000_000; }
    /** Same integer BT.601 conversion as the verified prototype: format 4 YV12, format 1 NV21. */
    public static void toYuv(int[] pixels, byte[] yuv, int width, int height, int format) {
        int size = imageSize(width, height, format), base = width * height;
        if (pixels.length < base || yuv.length < size) throw new IllegalArgumentException("Short image buffer");
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int rgb = pixels[y * width + x], r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            yuv[y * width + x] = clamp(((66*r + 129*g + 25*b + 128) >> 8) + 16);
            if ((y & 1) == 0 && (x & 1) == 0) {
                byte u = clamp(((-38*r - 74*g + 112*b + 128) >> 8) + 128);
                byte v = clamp(((112*r - 94*g - 18*b + 128) >> 8) + 128);
                int k = y / 2 * (width / 2) + x / 2;
                if (format == 1) { yuv[base+2*k] = v; yuv[base+2*k+1] = u; }
                else { yuv[base+k] = v; yuv[base+base/4+k] = u; }
            }
        }
    }
    private static byte clamp(int value) { return (byte)Math.max(0, Math.min(255, value)); }
}
