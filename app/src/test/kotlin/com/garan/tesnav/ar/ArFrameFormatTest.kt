package com.garan.tesnav.ar

import org.junit.Assert.*
import org.junit.Test

class ArFrameFormatTest {
    @Test fun nativeRecorderFormatsProduceVerifiedPixelLayouts() {
        val pixels = intArrayOf(0xffff0000.toInt(), 0, 0xff0000ff.toInt(), 0, 0, 0, 0, 0)
        for ((wire, expected) in listOf(0 to byteArrayOf(240.toByte(),90,110,240.toByte()),
            4 to byteArrayOf(240.toByte(),110,90,240.toByte()))) {
            val output = ByteArray(12)
            ArFrameFormat.toYuv(pixels, output, 4, 2, ArFrameFormat.recorderStorageFormat(wire))
            assertArrayEquals(expected, output.copyOfRange(8,12))
        }
        listOf(-1,1,2,3,5).forEach { format ->
            assertThrows(IllegalArgumentException::class.java) { ArFrameFormat.recorderStorageFormat(format) }
        }
    }
    @Test fun restrictEndpointToLocalTestStream() {
        listOf("192.168.0.12", "10.1.2.3", "172.16.0.1", "172.31.255.254").forEach {
            assertTrue(ArFrameFormat.validEndpoint("http://$it:8082/frames"))
        }
        listOf("http://8.8.8.8:8082/frames", "http://172.32.0.1:8082/frames",
            "http://192.168.0.999:8082/frames", "http://192.168.0.12/frames",
            "http://192.168.0.12:8082/frames?token=x", "http://user@192.168.0.12:8082/frames",
            "http://192.168.0.12:8082/other", "https://192.168.0.12:8082/frames",
            "http://192.168.example.com:8082/frames", "http://192.168.0.12:99999/frames").forEach {
            assertFalse(it, ArFrameFormat.validEndpoint(it))
        }
    }
    @Test fun authenticatedDiscoveryOnlyReplacesThePrivateHost() {
        assertEquals("http://192.168.53.232:8082/frames",
            ArFrameFormat.endpointForHost("http://192.168.0.12:8082/frames", "192.168.53.232"))
        assertThrows(IllegalArgumentException::class.java) {
            ArFrameFormat.endpointForHost("http://192.168.0.12:8082/frames", "8.8.8.8")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArFrameFormat.endpointForHost("http://192.168.0.12:8082/other", "192.168.53.232")
        }
    }
    @Test fun checkImageBoundsAndLayout() {
        assertEquals(1_382_400, ArFrameFormat.imageSize(1280, 720, 4))
        for ((w, h, f) in listOf(Triple(0, 720, 4), Triple(1281, 720, 4), Triple(1280, 721, 4),
            Triple(2000, 720, 4), Triple(1280, 720, 0))) {
            assertThrows(IllegalArgumentException::class.java) { ArFrameFormat.imageSize(w, h, f) }
        }
        assertFalse(ArFrameFormat.validJpegLength(-1))
        assertFalse(ArFrameFormat.validJpegLength(0))
        assertTrue(ArFrameFormat.validJpegLength(4_000_000))
        assertFalse(ArFrameFormat.validJpegLength(4_000_001))
    }
    @Test fun yv12BlackWhiteAndRedChroma() {
        val result = ByteArray(6)
        ArFrameFormat.toYuv(IntArray(4) { 0xff000000.toInt() }, result, 2, 2, 4)
        assertArrayEquals(byteArrayOf(16, 16, 16, 16, 128.toByte(), 128.toByte()), result)
        ArFrameFormat.toYuv(IntArray(4) { 0xffffffff.toInt() }, result, 2, 2, 4)
        assertArrayEquals(byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte()), result)
        ArFrameFormat.toYuv(IntArray(4) { 0xffff0000.toInt() }, result, 2, 2, 4)
        assertArrayEquals(byteArrayOf(82, 82, 82, 82, 240.toByte(), 90), result)
    }
    @Test fun nv21InterleavesChromaWhereYv12UsesPlanes() {
        val pixels = intArrayOf(0xffff0000.toInt(), 0, 0xff0000ff.toInt(), 0, 0, 0, 0, 0)
        val yv12 = ByteArray(12); val nv21 = ByteArray(12)
        ArFrameFormat.toYuv(pixels, yv12, 4, 2, 4)
        ArFrameFormat.toYuv(pixels, nv21, 4, 2, 1)
        assertArrayEquals(byteArrayOf(240.toByte(), 110, 90, 240.toByte()), yv12.copyOfRange(8, 12))
        assertArrayEquals(byteArrayOf(240.toByte(), 90, 110, 240.toByte()), nv21.copyOfRange(8, 12))
        assertThrows(IllegalArgumentException::class.java) { ArFrameFormat.toYuv(IntArray(1), ByteArray(6), 2, 2, 4) }
    }
}
