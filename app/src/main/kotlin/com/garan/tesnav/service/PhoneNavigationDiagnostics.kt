package com.garan.tesnav.service

import android.os.SystemClock
import com.garan.tesnav.export.NavAssistDeliveryState
import com.garan.tesnav.model.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** Phone-owned disk/compression. Never runs on the navigation or ACK coroutine. */
class PhoneNavigationDiagnostics(
    private val directory: File,
    private val endpoint: () -> String?,
    private val delivery: () -> NavAssistDeliveryState,
    private val navigation: () -> NavigationState,
) {
    private val mutableStatus = MutableStateFlow("诊断记录：等待 C3 连接")
    val status = mutableStatus.asStateFlow()

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        var epoch: String? = null
        var cursor = 0L
        var target: String? = null
        val writer = PhoneDiagnosticLogWriter(directory)
        fun append(record: JSONObject) {
            writer.append(record.toString())
        }
            while (isActive) {
                try {
                    val ack = delivery()
                    val uri = endpoint()?.takeIf { it.startsWith("udp://") }?.let(URI::create)
                    val age = ack.ackAtElapsedMs?.let { SystemClock.elapsedRealtime() - it }
                    if (uri?.host == null || !ack.acknowledged || !ack.ownerConfirmed ||
                        ack.sessionId == null || age == null || age !in 0..3_000) {
                        mutableStatus.value = "诊断记录：等待 C3 有效回执"
                    } else {
                        val nextTarget = "${uri.host}/${ack.sessionId}"
                        if (target != nextTarget) { target = nextTarget; epoch = null; cursor = 0 }
                        val connection = diagnosticUrl(uri.host, ack.sessionId, cursor).openConnection() as HttpURLConnection
                        connection.connectTimeout = 1_000
                        connection.readTimeout = 1_000
                        connection.instanceFollowRedirects = false
                        try {
                            if (connection.responseCode != 200) {
                                mutableStatus.value = "诊断记录：C3 接口未就绪（${connection.responseCode}）"
                            } else {
                                val body = connection.inputStream.use { it.readBytesLimited(262_144) }
                                val batch = JSONObject(body.toString(Charsets.UTF_8))
                                check(batch.getInt("version") == 1)
                                val newEpoch = batch.getString("epoch")
                                if (epoch != null && epoch != newEpoch) {
                                    append(JSONObject().put("kind", "receiverRestart").put("wallMs", System.currentTimeMillis()))
                                    cursor = 0
                                }
                                epoch = newEpoch
                                val oldest = batch.getLong("oldestSequence")
                                val gap = if (oldest > cursor + 1) oldest - cursor - 1 else 0
                                if (gap > 0) append(JSONObject().put("kind", "gap").put("missing", gap))
                                val events = batch.getJSONArray("events")
                                check(events.length() <= 128)
                                for (i in 0 until events.length()) {
                                    val event = events.getJSONObject(i)
                                    val sequence = event.getLong("sequence")
                                    if (sequence > cursor) {
                                        append(JSONObject().put("kind", "c3Observation").put("epoch", epoch).put("data", event))
                                        cursor = sequence
                                    }
                                }
                                val state = navigation()
                                append(JSONObject().put("kind", "appNavigation").put("wallMs", System.currentTimeMillis())
                                    .put("source", state.navigationProvider).put("mode", state.navigationMode.name)
                                    .put("turn", state.nextTurnType).put("distance", state.nextTurnDistanceMeters)
                                    .put("road", state.nextRoad).put("guidanceElapsedMs", state.guidanceReceivedElapsedMs)
                                    .put("ackSequence", ack.sequence))
                                mutableStatus.value = "诊断记录：手机保存中，C3 序号 $cursor${if (gap > 0) "，断连缺失 $gap 条" else ""}"
                            }
                        } finally { connection.disconnect() }
                    }
                } catch (_: Exception) {
                    writer.rotateAfterFailure()
                    mutableStatus.value = "诊断记录：暂不可用，等待重连"
                }
                delay(1_000)
            }
    }
}

internal fun diagnosticUrl(host: String, session: String, cursor: Long): java.net.URL {
    val base = URI("http", null, host, 7766, "/v3/diagnostics", null, null).toASCIIString()
    return java.net.URL("$base?sessionId=${URLEncoder.encode(session, "UTF-8")}&after=$cursor")
}

/** Independent complete gzip members, bounded files, no open stream across suspension. */
internal class PhoneDiagnosticLogWriter(
    private val directory: File,
    private val maxBytes: Int = 1_048_576,
    private val maxFiles: Int = 20,
) {
    private var file: File? = null
    init { require(maxBytes > 0 && maxFiles > 0) }
    fun rotateAfterFailure() { file = null }
    fun append(json: String) {
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write((json + "\n").toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        if (file == null || file!!.length() + bytes.size > maxBytes) {
            check(directory.isDirectory || directory.mkdirs())
            directory.listFiles { f -> f.name.startsWith("nav-") && f.name.endsWith(".jsonl.gz") }
                ?.sortedByDescending(File::lastModified)?.drop(maxFiles - 1)?.forEach { check(it.delete()) }
            file = File(directory, "nav-${System.currentTimeMillis()}-${UUID.randomUUID()}.jsonl.gz")
        }
        java.io.FileOutputStream(file!!, true).use { it.write(bytes) }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(out.size() + count <= limit)
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
