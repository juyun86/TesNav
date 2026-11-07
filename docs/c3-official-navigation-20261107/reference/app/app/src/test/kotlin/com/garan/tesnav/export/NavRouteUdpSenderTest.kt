package com.garan.tesnav.export

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

class NavRouteUdpSenderTest {
    @Test fun `receiver must acknowledge the admitted session and sequence`() = runBlocking {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 3000
            val server = thread {
                val request = DatagramPacket(ByteArray(8192), 8192)
                receiver.receive(request)
                val ack = """{"messageType":"navassist_udp_ack","schemaVersion":3,"sessionId":"s","sequence":7}""".toByteArray()
                receiver.send(DatagramPacket(ack, ack.size, request.address, request.port))
            }
            val fragments = NavRouteFragments.encode("{}".toByteArray(), "s", 3)
            assertTrue(NavRouteUdpSender.send("127.0.0.1", receiver.localPort, fragments, "s", 5, { 7 }, { true }))
            server.join(3000)
        }
    }

    @Test fun `cancelled route does not transmit`() = runBlocking {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { receiver ->
            assertFalse(NavRouteUdpSender.send("127.0.0.1", receiver.localPort, listOf(byteArrayOf(1)), "s", 1, { 1 }, { false }))
        }
    }

    @Test fun `android fragments reach staged python route store`() = runBlocking {
        val script = System.getenv("PHONE_ROUTE_LOOPBACK_SCRIPT")
        assumeTrue(!script.isNullOrEmpty())
        val process = ProcessBuilder("python", script).redirectErrorStream(true).start()
        try {
            val output = process.inputStream.bufferedReader()
            val port = output.readLine().toInt()
            val random = java.util.Random(12)
            val points = List(1500) { i -> mapOf(
                "latitude" to 31.0 + i * .00001 + random.nextDouble() * .000001,
                "longitude" to 120.0 + i * .00001 + random.nextDouble() * .000001,
            ) }
            val payload = CanonicalJson.encode(mapOf(
                "schemaVersion" to 3, "messageType" to "navigation_route", "sessionId" to "phone-session",
                "sequence" to 9, "routeRevision" to 3, "sourceWallTimeMs" to 1_000_000,
                "coordinateSystem" to "gcj02", "points" to points,
                "amapSteps" to listOf(mapOf(
                    "start" to 0, "end" to 1499, "icon" to 3,
                    "first" to listOf(points.first()["longitude"], points.first()["latitude"]),
                    "last" to listOf(points.last()["longitude"], points.last()["latitude"]),
                )),
            )).toByteArray()
            val fragments = NavRouteFragments.encode(payload, "phone-session", 3)
            assertTrue(fragments.size > 1)
            assertTrue(NavRouteUdpSender.send("127.0.0.1", port, fragments, "phone-session", 9, { 9 }, { true }))
            assertEquals("accepted", output.readLine())
            assertEquals(0, process.waitFor())
        } finally {
            process.destroyForcibly()
        }
    }
}
