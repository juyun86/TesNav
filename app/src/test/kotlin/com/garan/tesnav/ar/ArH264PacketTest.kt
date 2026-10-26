package com.garan.tesnav.ar

import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ArH264PacketTest {
    private fun packet(config: Boolean=false, key: Boolean=true, version: Int=3,
                       eof: Long=1234567890, pts: Long=eof/1000, width: Long=1280, geometry: String?=null,
                       bytes: ByteArray=byteArrayOf(0,0,0,1,0x65,2)): ByteArray {
        val suffix=geometry?.let { ",\"geometry\":$it" }.orEmpty()
        val json="""{"version":$version,"codec":"h264","camera":"wideRoad","config":$config,"keyframe":$key,"width":$width,"height":720,"frameId":123,"timestampEofNs":$eof,"ptsUs":$pts$suffix}""".toByteArray()
        return ByteBuffer.allocate(8+json.size+bytes.size).put("CAH3".toByteArray()).putInt(json.size).put(json).put(bytes).array()
    }
    private fun invalid(data: ByteArray, protocol: String="3") {
        try { ArH264Packet.decode(data,protocol);fail("invalid AVC packet accepted") } catch (_: IOException) {}
    }
    @Test fun retainsSourceFrameAndTimestamp() {
        val decoded=ArH264Packet.decode(packet(),"3")
        assertEquals(123,decoded.frameId);assertEquals(1234567890,decoded.timestampEofNs)
        assertEquals(1234567,decoded.ptsUs);assertEquals(1280,decoded.width);assertTrue(decoded.keyframe)
    }
    @Test fun acceptsSeparateConfigurationAndNonKeyAccessUnit() {
        val config=packet(config=true,key=false,eof=0,bytes=byteArrayOf(0,0,0,1,0x67,1,0,0,1,0x68,2))
        assertTrue(ArH264Packet.decode(config,"3").config)
        assertFalse(ArH264Packet.decode(packet(key=false,bytes=byteArrayOf(0,0,1,0x41,2)),"3").keyframe)
    }
    @Test fun rejectsProtocolLengthAndTimingMismatch() {
        invalid(packet(),"2");invalid(packet(version=2));invalid(packet(pts=12));invalid(packet().copyOf(10))
        val bad=packet();ByteBuffer.wrap(bad).putInt(4,16385);invalid(bad)
    }
    @Test fun rejectsForgedKeyframeAndMissingCodecConfiguration() {
        invalid(packet(key=false));invalid(packet(config=true,key=false))
        invalid(packet(config=true,key=false,bytes=byteArrayOf(0,0,0,1,0x67,1)))
        invalid(packet(bytes=byteArrayOf(0,0,0,1,0xe5.toByte(),2)))
    }
    @Test fun rejectsDimensionsThatWouldWrapAndNegativeTimestamps() {
        invalid(packet(width=4294968576));invalid(packet(width=1281));invalid(packet(eof=-1000,pts=-1))
    }
    @Test fun geometryMustBelongToTheSameCameraAndFrame() {
        val geometry="""{"version":1,"frameId":123,"camera":"wideRoad","inputMode":"native"}"""
        assertTrue(ArH264Packet.decode(packet(geometry=geometry),"3").geometry.contains("frameId"))
        invalid(packet(geometry=geometry.replace("123","124")))
        invalid(packet(geometry=geometry.replace("wideRoad","narrowRoad")))
    }
}
