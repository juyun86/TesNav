package com.garan.tesnav.ar

import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ArVideoPacketTest {
    private val jpeg = byteArrayOf(-1,-40,1,2,3,-1,-39)
    private fun envelope(length: Int=2) = ByteBuffer.allocate(17).put("CAR2".toByteArray()).putInt(length)
        .put("{}".toByteArray()).put(jpeg).array()
    @Test fun `legacy JPEG stays unchanged`() {
        val packet=ArVideoPacket.decode(jpeg,null)
        assertSame(jpeg,packet.jpeg);assertEquals("",packet.geometry)
    }
    @Test fun `native pixels are not modified by geometry`() {
        val packet=ArVideoPacket.decode(envelope(),"2")
        assertArrayEquals(jpeg,packet.jpeg);assertEquals("{}",packet.geometry)
    }
    @Test fun `rejects invalid envelopes before decoding bitmap`() {
        for (bad in listOf(byteArrayOf(),jpeg,envelope(-1),envelope(Int.MAX_VALUE),envelope(16_385),envelope(9)))
            assertThrows(IOException::class.java) { ArVideoPacket.decode(bad,"2") }
        assertThrows(IOException::class.java) { ArVideoPacket.decode(envelope(),"3") }
    }
}
