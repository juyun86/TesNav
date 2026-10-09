package com.garan.tesnav.teslable

import java.math.BigInteger
import java.security.*
import java.security.spec.*
import javax.crypto.Cipher
import org.junit.Assert.*
import org.junit.Test

class TeslaNavigationProtocolTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun response(drive: ByteArray) = TeslaWire.b(1, byteArrayOf()) + TeslaWire.b(2, TeslaWire.b(5, drive))
    private fun coords(lat: Float = 31.2f, lon: Float = 121.5f) = TeslaWire.f(1, lat.toRawBits()) + TeslaWire.f(2, lon.toRawBits())
    private inline fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }

    @Test fun queryContainsOnlyDriveState() {
        assertArrayEquals(hex("12040a022200"), TeslaNavigationProtocol.readDriveState)
    }
    @Test fun parsesOriginalCarDestinationAndSkipsUnrelatedFields() {
        val result = TeslaNavigationProtocol.destination(response(TeslaWire.b(7, "家".toByteArray()) +
            TeslaWire.b(12, coords()) + TeslaWire.v(102, 42)))!!
        assertEquals("家", result.name)
        assertEquals(31.2f.toDouble(), result.latitude, 0.0)
        assertEquals(121.5, result.longitude, 0.0)
    }
    @Test fun missingDestinationDoesNotInventOne() {
        assertNull(TeslaNavigationProtocol.destination(response(TeslaWire.v(102, 42))))
        assertNull(TeslaNavigationProtocol.destination(response(TeslaWire.b(7, "家".toByteArray()))))
        assertNull(TeslaNavigationProtocol.destination(response(TeslaWire.b(12, TeslaWire.f(1, 31f.toRawBits())))))
    }
    @Test fun cancellationRequiresSuccessfulDriveStateWithoutDestination() {
        assertTrue(TeslaNavigationProtocol.destinationAbsent(response(byteArrayOf())))
        assertFalse(TeslaNavigationProtocol.destinationAbsent(response(TeslaWire.b(7, "家".toByteArray()))))
        assertFalse(TeslaNavigationProtocol.destinationAbsent(response(TeslaWire.b(12, byteArrayOf()))))
        assertTrue(TeslaNavigationProtocol.destinationAbsent(response(TeslaWire.b(12, coords()))))
        assertNull(TeslaNavigationProtocol.destination(response(TeslaWire.b(12, coords()))))
        assertFalse(TeslaNavigationProtocol.destinationAbsent(response(TeslaWire.b(12, coords()) + TeslaWire.f(8, 1f.toRawBits()))))
        assertFalse(TeslaNavigationProtocol.destinationAbsent(response(TeslaWire.b(12, coords()) + TeslaWire.f(9, 0f.toRawBits()))))
        rejects { TeslaNavigationProtocol.destinationAbsent(TeslaWire.b(1, TeslaWire.v(1, 1))) }
        rejects { TeslaNavigationProtocol.destinationAbsent(TeslaWire.b(1, byteArrayOf())) }
    }

    @Test fun rejectsInvalidCoordinates() {
        for ((lat, lon) in listOf(Float.NaN to 121f, 91f to 121f, 31f to 181f, 0f to 0f))
            rejects { TeslaNavigationProtocol.destination(response(TeslaWire.b(12, coords(lat, lon)))) }
    }
    @Test fun vehicleFailureIsNotNavigationOff() {
        rejects { TeslaNavigationProtocol.destination(TeslaWire.b(1, TeslaWire.v(1, 1))) }
        rejects { TeslaNavigationProtocol.destination(TeslaWire.b(1, byteArrayOf())) }
        rejects { TeslaNavigationProtocol.destination(TeslaWire.b(1, byteArrayOf()) + TeslaWire.b(2, byteArrayOf())) }
    }
    @Test fun rejectsMalformedWireLengthsAndVarints() {
        for (data in listOf(hex("0affffffff7f"), hex("0a04ff"), hex("80808080808080808080"), hex("00")))
            rejects { TeslaWire.fields(data) }
    }
    @Test fun acceptsUnknownWellFormedFields() {
        val data = TeslaWire.v(100, 1234) + TeslaWire.b(101, byteArrayOf(7, 8)) + TeslaWire.f(102, 1234)
        assertEquals(3, TeslaWire.fields(data).size)
        assertEquals(1234L, TeslaWire.value(data, 100))
    }
    @Test fun validatesVin() {
        assertTrue(TeslaNavigationProtocol.validVin("5YJ30123456789ABC"))
        assertFalse(TeslaNavigationProtocol.validVin("5YJ30123456789ABI"))
        assertFalse(TeslaNavigationProtocol.validVin("short"))
    }
    @Test fun officialBluetoothNameVector() {
        // Tesla protocol.md, BLE local name example.
        assertEquals("S1a87a5a75f3df858C", TeslaNavigationProtocol.localName("5YJS0000000000000"))
    }
    @Test fun officialSessionHmacVector() {
        val key = hex("1b2fce19967b79db696f909cff89ea9a")
        val subkey = TeslaNavigationProtocol.hmac(key, "session info".toByteArray())
        assertArrayEquals(hex("fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300"), subkey)
        val info = hex("0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d971a104c463f9cc0d3d26906e982ed224adde6255a0a0000")
        val metadata = TeslaNavigationProtocol.metadata(0 to byteArrayOf(6), 2 to "5YJ30123456789ABC".toByteArray(),
            6 to hex("1588d5a30eabc6f8fc9a951b11f6fd11"))
        assertArrayEquals(hex("996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002"), TeslaNavigationProtocol.hmac(subkey, metadata + info))
    }
    @Test fun officialEcdhVector() {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val private = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger("2538CDC29A97C19C1E99A637D6CF4F8C970C118B56EDE1E6323E6D162C4B30DB", 16), params))
        val remote = hex("04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97")
        assertArrayEquals(hex("1b2fce19967b79db696f909cff89ea9a"), TeslaNavigationProtocol.sharedKey(private, remote))
    }
    @Test fun officialAesGcmAndMetadataVector() {
        val meta = TeslaNavigationProtocol.metadata(0 to byteArrayOf(5), 1 to byteArrayOf(3),
            2 to "5YJ30123456789ABC".toByteArray(), 3 to hex("4c463f9cc0d3d26906e982ed224adde6"),
            4 to TeslaNavigationProtocol.be32(2655), 5 to TeslaNavigationProtocol.be32(7), 7 to TeslaNavigationProtocol.be32(2))
        assertArrayEquals(hex("000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6040400000a5f050400000007070400000002ff"), meta)
        val aad = MessageDigest.getInstance("SHA-256").digest(meta)
        assertArrayEquals(hex("38038e8c0f2ec228e0ff64991481db3a7bbc133696c5"), TeslaNavigationProtocol.cipher(
            Cipher.ENCRYPT_MODE, hex("1b2fce19967b79db696f909cff89ea9a"), hex("dbf79447fa156674dae1caed"), aad, hex("120452020801")))
    }
    @Test fun destinationExpiresWithoutRenewingOldObservation() {
        val state = TeslaBleNavigationState(observedElapsedMs = 100L)
        assertTrue(state.fresh(100)); assertTrue(state.fresh(10099))
        assertFalse(state.fresh(10100)); assertFalse(state.fresh(99))
        assertFalse(TeslaBleNavigationState().fresh(100))
    }
}
