package com.garan.tesnav.ar;

import java.nio.ByteBuffer;

/** Copies decoder YUV_420_888 planes into the existing recorder layouts, without RGB. */
public final class ArYuv420 {
    private ArYuv420() {}
    public static final class Plane {
        final ByteBuffer data;
        final int rowStride, pixelStride, start;
        public Plane(ByteBuffer data, int rowStride, int pixelStride) {
            if (data == null || rowStride < 1 || pixelStride < 1) throw new IllegalArgumentException("Invalid plane");
            this.data = data; this.rowStride = rowStride; this.pixelStride = pixelStride; start = data.position();
        }
        int index(int x, int y) {
            long index = (long)start + (long)y*rowStride + (long)x*pixelStride;
            if (index < start || index >= data.limit()) throw new IllegalArgumentException("Truncated YUV plane");
            return (int)index;
        }
    }
    public static void copy(Plane y, Plane u, Plane v, int frameWidth, int frameHeight,
                            int left, int top, int width, int height, int storageFormat, byte[] output) {
        if (frameWidth < 2 || frameHeight < 2 || frameWidth > 4096 || frameHeight > 2160 ||
                left < 0 || top < 0 || width < 2 || height < 2 ||
                ((left|top|width|height)&1) != 0 || (long)left+width > frameWidth || (long)top+height > frameHeight ||
                (storageFormat != 1 && storageFormat != 4) || output == null ||
                output.length != ArFrameFormat.imageSize(width,height,storageFormat))
            throw new IllegalArgumentException("Invalid YUV crop or layout");
        // Validate the last sample before writing, including cropped final rows without padding.
        if ((long)(left+width-1)*y.pixelStride >= y.rowStride ||
                (long)((left+width)/2-1)*u.pixelStride >= u.rowStride ||
                (long)((left+width)/2-1)*v.pixelStride >= v.rowStride)
            throw new IllegalArgumentException("Invalid YUV row stride");
        y.index(left+width-1,top+height-1);
        u.index((left+width)/2-1,(top+height)/2-1);
        v.index((left+width)/2-1,(top+height)/2-1);
        ByteBuffer yData=y.data.duplicate();
        for (int row=0;row<height;row++) {
            int from=y.index(left,top+row), to=row*width;
            if (y.pixelStride == 1) {
                yData.position(from); yData.get(output,to,width);
            } else for (int col=0;col<width;col++) output[to+col]=y.data.get(from+col*y.pixelStride);
        }
        int luma=width*height, chroma=luma/4;
        ByteBuffer uData=u.data.duplicate(),vData=v.data.duplicate();
        // Bulk transfers avoid one direct-buffer access for every chroma sample.
        // Unusual large pixel strides retain the bounded per-sample path.
        byte[] uRow=u.pixelStride<=4 ? new byte[(width/2-1)*u.pixelStride+1] : null;
        byte[] vRow=v.pixelStride<=4 ? new byte[(width/2-1)*v.pixelStride+1] : null;
        for (int row=0;row<height/2;row++) {
            int ui=u.index(left/2,top/2+row), vi=v.index(left/2,top/2+row);
            if (uRow!=null) { uData.position(ui);uData.get(uRow); }
            if (vRow!=null) { vData.position(vi);vData.get(vRow); }
            for (int col=0;col<width/2;col++) {
                int at=row*(width/2)+col;
                byte ub=uRow==null ? u.data.get(ui+col*u.pixelStride) : uRow[col*u.pixelStride];
                byte vb=vRow==null ? v.data.get(vi+col*v.pixelStride) : vRow[col*v.pixelStride];
                if (storageFormat == 4) { output[luma+at]=vb; output[luma+chroma+at]=ub; }
                else { output[luma+2*at]=vb; output[luma+2*at+1]=ub; }
            }
        }
    }
}
