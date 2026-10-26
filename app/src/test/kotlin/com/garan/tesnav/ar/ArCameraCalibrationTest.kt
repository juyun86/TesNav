package com.garan.tesnav.ar

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ArCameraCalibrationTest {
    private val metadata = """{"version":3,"codec":"h264","camera":"narrowRoad","config":false,"keyframe":true,"width":1280,"height":720,"sourceWidth":1928,"sourceHeight":1208,"crop":[340,462,1620,1182],"frameId":123,"timestampEofNs":1234567890,"ptsUs":1234567,"geometry":{"version":1,"frameId":123,"camera":"narrowRoad","inputMode":"native","cameraCalibration":{"version":1,"sensor":"ox03c10","sourceSize":[1928,1208],"crop":[340,462,1620,1182],"intrinsics":[2648,2648,964,604]}}}"""
    private fun packet(json: String): ArH264Packet {
        val text=json.toByteArray();val nal=byteArrayOf(0,0,0,1,0x65,2)
        return ArH264Packet.decode(ByteBuffer.allocate(8+text.size+nal.size).put("CAH3".toByteArray()).putInt(text.size).put(text).put(nal).array(),"3")
    }
    @Test fun convertsSourceOpticalCentreExactlyOnce() {
        val bytes=ArCameraCalibration.encode(packet(metadata),10000)
        assertEquals(80,bytes.size)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10000,b.getLong(8));assertEquals(1280,b.getInt(16));assertEquals(720,b.getInt(20))
        assertEquals(2648.0,b.getDouble(24),0.0);assertEquals(2648.0,b.getDouble(32),0.0)
        assertEquals(624.0,b.getDouble(40),0.0);assertEquals(142.0,b.getDouble(48),0.0)
    }
    @Test fun rejectsWrongCameraCropSizeAndInvalidFocalLength() {
        for (bad in listOf(metadata.replace("narrowRoad","wideRoad"),metadata.replace("ox03c10","os04c10"),
                metadata.replace("[2648,2648,964,604]","[0,2648,964,604]"),
                metadata.replace("[2648,2648,964,604]","[1e999,2648,964,604]"),
                metadata.replace("[340,462,1620,1182]","[340,460,1620,1182]"),
                metadata.replace("\"sourceSize\":[1928,1208]","\"sourceSize\":[1344,760]"))) {
            try { ArCameraCalibration.encode(packet(bad),10000);fail("bad calibration accepted") } catch (_: IOException) {}
        }
    }
    @Test fun heightRequiresValidCalibrationAndPreservesOptics() {
        for ((height,status,expected) in listOf(Triple("1.2077","calibrated",1.2077),
                Triple("0","calibrated",0.0),Triple("1e999","calibrated",0.0),
                Triple("3.1","calibrated",0.0),Triple("1.2","uncalibrated",0.0))) {
            val json=metadata.replace("\"sensor\":", "\"heightM\":$height,\"heightStatus\":\"$status\",\"sensor\":")
            val b=ByteBuffer.wrap(ArCameraCalibration.encode(packet(json),10000)).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(expected,b.getDouble(56),0.0)
            assertEquals(624.0,b.getDouble(40),0.0);assertEquals(142.0,b.getDouble(48),0.0)
        }
    }
    @Test fun missingHeightWaitsThenValidHeightCanStart() {
        val missing=ArCameraCalibration.encode(packet(metadata),10000)
        assertFalse(ArCameraCalibration.hasValidHeight(missing))
        val json=metadata.replace("\"sensor\":", "\"heightM\":1.208,\"heightStatus\":\"calibrated\",\"sensor\":")
        assertTrue(ArCameraCalibration.hasValidHeight(ArCameraCalibration.encode(packet(json),10050)))
        assertFalse(ArCameraCalibration.hasValidHeight(null))
        assertFalse(ArCameraCalibration.hasValidHeight(ByteArray(79)))
    }
}
