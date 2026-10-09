package com.garan.tesnav.export

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class NavAssistV3DeliveryTest {
    private val broadcast = listOf(InetAddress.getByName("192.168.8.255"))
    private val host = "192.168.8.101"
    private fun ack() = NavAssistV3UdpAck(host, null)

    @Test fun `discovery then direct delivery without rediscovery on one missing ack`() {
        val client = JvmUdpNavAssistV3Client()
        assertEquals(broadcast, client.deliveryTargets("wifi-a") { broadcast })
        client.recordDelivery(ack())
        assertEquals(listOf(InetAddress.getByName(host)), client.deliveryTargets("wifi-a") { error("broadcast") })
        client.recordDelivery(null)
        assertEquals(host, client.deliveryTargets("wifi-a") { error("broadcast") }.single().hostAddress)
        client.recordDelivery(ack())
        client.recordDelivery(null)
        assertEquals(host, client.deliveryTargets("wifi-a") { error("broadcast") }.single().hostAddress)
    }

    @Test fun `consecutive failures rediscover and accept a changed C3 address`() {
        val client = JvmUdpNavAssistV3Client()
        client.deliveryTargets("wifi-a") { broadcast }
        client.recordDelivery(ack())
        repeat(2) { client.recordDelivery(null) }
        assertEquals(broadcast, client.deliveryTargets("wifi-a") { broadcast })
        client.recordDelivery(NavAssistV3UdpAck("192.168.8.102", null))
        assertEquals("192.168.8.102", client.deliveryTargets("wifi-a") { error("broadcast") }.single().hostAddress)
    }

    @Test fun `network identity change forgets previous destination immediately`() {
        val client = JvmUdpNavAssistV3Client()
        client.deliveryTargets("wifi-a") { broadcast }
        client.recordDelivery(ack())
        val other = listOf(InetAddress.getByName("192.168.9.255"))
        assertEquals(other, client.deliveryTargets("wifi-b") { other })
        assertEquals(broadcast, client.deliveryTargets("wifi-a") { broadcast })
    }

    @Test fun `missing interface identity cannot reuse a cached destination`() {
        val client = JvmUdpNavAssistV3Client()
        client.deliveryTargets("wifi-a") { broadcast }
        client.recordDelivery(ack())
        assertEquals(broadcast, client.deliveryTargets("") { broadcast })
    }
}
