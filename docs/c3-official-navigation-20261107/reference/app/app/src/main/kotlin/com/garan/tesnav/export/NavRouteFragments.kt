package com.garan.tesnav.export

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import okio.ByteString.Companion.toByteString

/** Offline-tested codec for a complete phone route; no socket or control access. */
internal object NavRouteFragments {
    const val CHUNK_BYTES = 4096
    const val MAX_ROUTE_BYTES = 2 * 1024 * 1024
    const val MAX_CHUNKS = 512
    const val MAX_PACKET_BYTES = 8192

    fun encode(body: ByteArray, sessionId: String, routeRevision: Long): List<ByteArray> {
        require(body.isNotEmpty() && body.size <= MAX_ROUTE_BYTES)
        require(sessionId.isNotEmpty() && sessionId.length <= 64 &&
            sessionId.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._:-" })
        require(routeRevision >= 0)
        val compressed = ByteArrayOutputStream().apply {
            DeflaterOutputStream(this).use { it.write(body) }
        }.toByteArray()
        val chunks = (compressed.indices step CHUNK_BYTES).map { start ->
            compressed.copyOfRange(start, minOf(start + CHUNK_BYTES, compressed.size))
        }
        require(chunks.isNotEmpty() && chunks.size <= MAX_CHUNKS)
        val digest = MessageDigest.getInstance("SHA-256").digest(compressed)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return chunks.mapIndexed { index, bytes ->
            CanonicalJson.encode(mapOf(
                "messageType" to "route_fragment", "schemaVersion" to 1,
                "sessionId" to sessionId, "routeRevision" to routeRevision,
                "digest" to digest, "index" to index, "count" to chunks.size,
                "data" to bytes.toByteString().base64(),
            )).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_PACKET_BYTES) }
        }
    }
}
