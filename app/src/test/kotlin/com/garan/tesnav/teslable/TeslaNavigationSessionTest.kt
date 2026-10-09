package com.garan.tesnav.teslable

import java.security.*
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import org.junit.Assert.*
import org.junit.Test

class TeslaNavigationSessionTest {
    private val vin = "5YJ30123456789ABC"
    private fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private data class Fixture(val session: TeslaNavigationSession, val key: ByteArray, val route: ByteArray)
    private fun fixture(): Fixture {
        val client = pair(); val car = pair()
        val session = TeslaNavigationSession(vin, client) { 1000L }
        val request = session.handshake()
        val route = TeslaWire.bytes(TeslaWire.bytes(request.bytes, 7)!!, 2)!!
        val info = TeslaWire.v(1, 6) + TeslaWire.b(2, TeslaNavigationProtocol.publicBytes(car.public)) +
            TeslaWire.b(3, ByteArray(16) { 9 }) + TeslaWire.f(4, 1000)
        val key = TeslaNavigationProtocol.sharedKey(car.private, TeslaNavigationProtocol.publicBytes(client.public))
        val tag = TeslaNavigationProtocol.hmac(TeslaNavigationProtocol.hmac(key, "session info".toByteArray()),
            TeslaNavigationProtocol.metadata(0 to byteArrayOf(6), 2 to vin.toByteArray(), 6 to request.uuid) + info)
        session.acceptHandshake(TeslaWire.b(6, TeslaWire.b(2, route)) + TeslaWire.b(7, TeslaWire.v(1, 3)) +
            TeslaWire.b(15, info) + TeslaWire.b(13, TeslaWire.b(6, TeslaWire.b(1, tag))) + TeslaWire.b(50, request.uuid), request)
        return Fixture(session, key, route)
    }
    private fun reply(f: Fixture, request: TeslaNavigationSession.Request, counter: Long, includeUuid: Boolean = true): ByteArray {
        val plain = TeslaWire.b(1, byteArrayOf()) + TeslaWire.b(2, TeslaWire.b(5,
            TeslaWire.b(7, "目的地".toByteArray()) + TeslaWire.b(12,
                TeslaWire.f(1, 31.2f.toRawBits()) + TeslaWire.f(2, 121.5f.toRawBits()))))
        val aad = MessageDigest.getInstance("SHA-256").digest(TeslaNavigationProtocol.metadata(
            0 to byteArrayOf(9), 1 to byteArrayOf(3), 2 to vin.toByteArray(), 5 to TeslaNavigationProtocol.be32(counter),
            7 to TeslaNavigationProtocol.be32(2), 8 to request.responseId, 9 to TeslaNavigationProtocol.be32(0)))
        val nonce = TeslaNavigationProtocol.random(12)
        val encrypted = TeslaNavigationProtocol.cipher(Cipher.ENCRYPT_MODE, f.key, nonce, aad, plain)
        val sig = TeslaWire.b(9, TeslaWire.b(1, nonce) + TeslaWire.v(2, counter) + TeslaWire.b(3, encrypted.takeLast(16).toByteArray()))
        return TeslaWire.b(6, TeslaWire.b(2, f.route)) + TeslaWire.b(7, TeslaWire.v(1, 3)) +
            TeslaWire.b(10, encrypted.dropLast(16).toByteArray()) + TeslaWire.b(13, sig) + TeslaWire.v(52, 2) +
            if (includeUuid) TeslaWire.b(50, request.uuid) else byteArrayOf()
    }
    private inline fun rejects(block: () -> Unit) { var failed = false; try { block() } catch (_: Exception) { failed = true }; assertTrue(failed) }
    @Test fun authenticatedDestinationRoundTrip() {
        val f = fixture(); val request = f.session.query()
        val destination = TeslaNavigationProtocol.destination(f.session.decrypt(reply(f, request, 1), request))!!
        assertEquals("目的地", destination.name)
        assertEquals(121.5, destination.longitude, 0.0)
    }
    @Test fun protocolFaultRemainsTypedAndCannotPublishDestination() {
        val f = fixture(); val request = f.session.query()
        val response = TeslaWire.b(6, TeslaWire.b(2, f.route)) + TeslaWire.b(7, TeslaWire.v(1, 3)) +
            TeslaWire.b(50, request.uuid) + TeslaWire.b(12, TeslaWire.v(2, 15))
        try {
            f.session.decrypt(response, request)
            fail("fault reply must not be decoded as a destination")
        } catch (e: TeslaQueryFault) { assertEquals(15L, e.code) }
        // A rejected response did not advance the anti-replay counter.
        assertNotNull(TeslaNavigationProtocol.destination(f.session.decrypt(reply(f, request, 1), request)))
    }
    @Test fun absentBleUuidStillRequiresCryptographicRequestBinding() {
        val f = fixture(); val first = f.session.query(); val second = f.session.query()
        val old = reply(f, first, 1, includeUuid = false)
        rejects { f.session.decrypt(old, second) }
        assertNotNull(TeslaNavigationProtocol.destination(f.session.decrypt(reply(f, second, 2, includeUuid = false), second)))
    }
    @Test fun duplicateAndOlderResponsesAreRejected() {
        val f = fixture(); val first = f.session.query(); val received = reply(f, first, 2)
        f.session.decrypt(received, first)
        rejects { f.session.decrypt(received, first) }
        rejects { f.session.decrypt(reply(f, first, 1), first) }
        val second = f.session.query()
        // A different request has a different authenticated response ID and its own counter.
        assertNotNull(TeslaNavigationProtocol.destination(f.session.decrypt(reply(f, second, 1), second)))
    }
    @Test fun consecutiveQueriesCanEachStartTheirResponseCounterAtZero() {
        val f = fixture()
        repeat(20) {
            val request = f.session.query()
            val response = reply(f, request, 0)
            assertNotNull(TeslaNavigationProtocol.destination(f.session.decrypt(response, request)))
            rejects { f.session.decrypt(response, request) }
            rejects { f.session.decrypt(response, f.session.query()) }
        }
    }
    @Test fun unauthenticatedResponseCannotAdvanceRequestCounter() {
        val f = fixture(); val request = f.session.query()
        val response = reply(f, request, 10)
        val bad = TeslaWire.fields(response).fold(byteArrayOf()) { acc, field -> acc + when (field.type) {
            0 -> TeslaWire.v(field.number, field.value)
            else -> TeslaWire.b(field.number, if (field.number == 10) field.bytes.copyOf().also {
                it[0] = (it[0].toInt() xor 1).toByte()
            } else field.bytes)
        } }
        rejects { f.session.decrypt(bad, request) }
        assertNotNull(TeslaNavigationProtocol.destination(f.session.decrypt(reply(f, request, 0), request)))
    }
    @Test fun tamperedCiphertextCannotPublishDestination() {
        val f = fixture(); val request = f.session.query(); val received = reply(f, request, 1)
        val fields = TeslaWire.fields(received)
        val ciphertext = fields.first { it.number == 10 }.bytes.also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tampered = fields.fold(byteArrayOf()) { acc, field -> acc + when (field.type) {
            0 -> TeslaWire.v(field.number, field.value)
            else -> TeslaWire.b(field.number, if (field.number == 10) ciphertext else field.bytes)
        } }
        rejects { f.session.decrypt(tampered, request) }
    }
    @Test fun wrongConnectionAndRequestCannotPublishDestination() {
        val f = fixture(); val request = f.session.query(); val received = reply(f, request, 1)
        rejects { fixture().session.decrypt(received, request) }
        rejects { f.session.decrypt(received, f.session.query()) }
    }
    @Test fun monotonicallyIncreasingRequestsContainOnlyNavigationQuery() {
        val f = fixture()
        for (expected in 7L..9L) {
            val request = f.session.query()
            val sig = TeslaWire.bytes(TeslaWire.bytes(request.bytes, 13)!!, 5)!!
            assertEquals(expected, TeslaWire.value(sig, 3))
            val aad = MessageDigest.getInstance("SHA-256").digest(TeslaNavigationProtocol.metadata(
                0 to byteArrayOf(5), 1 to byteArrayOf(3), 2 to vin.toByteArray(), 3 to ByteArray(16) { 9 },
                4 to TeslaNavigationProtocol.be32(1010), 5 to TeslaNavigationProtocol.be32(expected), 7 to TeslaNavigationProtocol.be32(2)))
            assertArrayEquals(TeslaNavigationProtocol.readDriveState, TeslaNavigationProtocol.cipher(Cipher.DECRYPT_MODE,
                f.key, TeslaWire.bytes(sig, 2)!!, aad, TeslaWire.bytes(request.bytes, 10)!! + TeslaWire.bytes(sig, 5)!!))
        }
    }
}
