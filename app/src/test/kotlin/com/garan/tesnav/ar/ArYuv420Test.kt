package com.garan.tesnav.ar

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ArYuv420Test {
    private fun plane(data: ByteArray, row: Int, pixel: Int = 1, start: Int = 0): ArYuv420.Plane {
        val buffer = ByteBuffer.allocateDirect(data.size).apply { put(data); position(start) }
        return ArYuv420.Plane(buffer,row,pixel)
    }
    @Test fun copiesPlanarAndInterleavedDecoderChromaWithoutRgb() {
        val y=plane(byteArrayOf(1,2,3,4,99,99,5,6,7,8,99,99),6)
        val u=plane(byteArrayOf(10,99,11),4,2)
        val v=plane(byteArrayOf(20,99,21),4,2)
        val planar=ByteArray(12); ArYuv420.copy(y,u,v,4,2,0,0,4,2,4,planar)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,20,21,10,11),planar)
        val nv21=ByteArray(12); ArYuv420.copy(y,u,v,4,2,0,0,4,2,1,nv21)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,20,10,21,11),nv21)
    }
    @Test fun cropUsesPlanePositionsAndStrideWithoutConsumingBuffers() {
        val yy=ByteBuffer.wrap(byteArrayOf(99,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16)).apply { position(1) }
        val y=ArYuv420.Plane(yy,4,1)
        val u=plane(byteArrayOf(30,31,32,33),2)
        val v=plane(byteArrayOf(40,41,42,43),2)
        val out=ByteArray(6);ArYuv420.copy(y,u,v,4,4,2,2,2,2,4,out)
        assertArrayEquals(byteArrayOf(11,12,15,16,43,33),out)
        assertEquals(1,yy.position())
    }
    @Test fun rejectsTruncatedPlanesBeforeWriting() {
        val y=plane(ByteArray(8),4);val uv=plane(byteArrayOf(1),2);val out=ByteArray(12){55}
        try { ArYuv420.copy(y,uv,uv,4,2,0,0,4,2,4,out);fail() } catch (_: IllegalArgumentException) {}
        assertTrue(out.all { it==55.toByte() })
    }
    @Test fun rejectsOddCropAndUnsupportedLayouts() {
        val y=plane(ByteArray(16),4);val uv=plane(ByteArray(4),2)
        for ((left,format) in listOf(1 to 4,0 to 2)) {
            try { ArYuv420.copy(y,uv,uv,4,4,left,0,2,2,format,ByteArray(6));fail() }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun rejectsOverlappingRowsBeforeWriting() {
        val y=plane(ByteArray(16),2);val uv=plane(ByteArray(4),2);val out=ByteArray(12){55}
        try { ArYuv420.copy(y,uv,uv,4,2,0,0,4,2,4,out);fail() } catch (_: IllegalArgumentException) {}
        assertTrue(out.all { it==55.toByte() })
    }
}
