package com.garan.tesnav.export

import com.google.gson.JsonParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.delay

/** A bounded route transfer using the already discovered navigation endpoint. */
internal object NavRouteUdpSender {
    suspend fun send(
        host: String,
        port: Int,
        fragments: List<ByteArray>,
        sessionId: String,
        firstSequence: Long,
        lastAcceptedSequence: () -> Long?,
        stillCurrent: () -> Boolean,
    ): Boolean {
        val address = InetAddress.getByName(host)
        require(address is Inet4Address && (address.isSiteLocalAddress || address.isLoopbackAddress))
        require(port in 1..65535 && fragments.isNotEmpty() && fragments.size <= NavRouteFragments.MAX_CHUNKS)
        require(fragments.all { it.isNotEmpty() && it.size <= NavRouteFragments.MAX_PACKET_BYTES })
        DatagramSocket().use { socket ->
            socket.connect(address, port)
            for ((index, fragment) in fragments.withIndex()) {
                if (!stillCurrent()) return false
                socket.send(DatagramPacket(fragment, fragment.size))
                if (index + 1 < fragments.size) delay(150)
            }
            socket.soTimeout = 1500
            val data = ByteArray(NavAssistV3AckParser.MAX_ACK_BYTES + 1)
            val reply = DatagramPacket(data, data.size)
            try { socket.receive(reply) } catch (_: java.net.SocketTimeoutException) { return false }
            if (!stillCurrent()) return false
            val payload = data.copyOf(reply.length)
            val sequence = runCatching { JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject["sequence"].asLong }.getOrNull()
                ?: return false
            val latest = lastAcceptedSequence() ?: return false
            if (sequence < firstSequence || sequence > latest) return false
            return NavAssistV3AckParser.parse(payload, host, sessionId, sequence, 0L, 0L) != null
        }
    }
}
