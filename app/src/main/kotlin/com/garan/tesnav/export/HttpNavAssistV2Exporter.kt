package com.garan.tesnav.export

import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.OemVehicleLaneState
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.JsonParser

internal fun confirmsNavigationSnapshot(body: String, response: String): Boolean = runCatching {
    val sent = JsonParser.parseString(body).asJsonObject
    val ack = JsonParser.parseString(response).asJsonObject
    ack["accepted"]?.asBoolean == true && ack["sessionId"]?.asString == sent["sessionId"].asString &&
        ack["sequence"]?.asLong == sent["sequence"].asLong
}.getOrDefault(false)

enum class NavAssistV2ConnectionStatus {
    UNCONFIGURED,
    SCANNING,
    MULTIPLE_DEVICES,
    DISCOVERED,
    ONLINE,
    ERROR,
}

interface NavAssistV2HttpClient {
    fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String)
    fun close()
}

internal class OkHttpNavAssistV2Client : NavAssistV2HttpClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun post(endpoint: HttpUrl, body: String, appKeyId: String, signature: String) {
        val request = Request.Builder()
            .url(endpoint)
            .header(NavAssistV3Auth.KEY_ID_HEADER, appKeyId)
            .header(NavAssistV3Auth.SIGNATURE_HEADER, signature)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val reason = response.body?.string()
                    ?.let(HTTP_REASON_PATTERN::find)
                    ?.groupValues
                    ?.getOrNull(1)
                error("HTTP ${response.code}${reason?.let { " ($it)" } ?: ""}")
            }
            check(confirmsNavigationSnapshot(body, response.body?.string().orEmpty())) {
                "HTTP response did not confirm the matching navigation snapshot"
            }
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val HTTP_REASON_PATTERN = Regex("\\\"reason\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    }
}

/**
 * Optional NavAssist v2 transport, isolated from the legacy WebSocket. A blank
 * base URL selects authenticated UDP discovery; a valid explicit URL remains
 * available as a test override.
 */
internal class HttpNavAssistV2Exporter(
    private val config: NavAssistV2ExportConfig,
    private val stateProvider: () -> NavigationState?,
    private val identity: NavAssistSigningIdentity,
    private val endpointDiscovery: NavAssistV2EndpointDiscovery,
    private val pinnedDeviceProvider: () -> PinnedNavAssistDevice?,
    private val httpClient: NavAssistV2HttpClient = OkHttpNavAssistV2Client(),
    private val discoveryRetryMs: Long = DEFAULT_DISCOVERY_RETRY_MS,
    private val useUnauthenticatedUdp: Boolean = false,
    private val udpClient: NavAssistV3UdpClient = JvmUdpNavAssistV3Client(),
    navigationSession: NavAssistV2Session? = null,
    publisherDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requireOwnerHandshake: Boolean = false,
    private val ownerSettleMs: Long = 3_000L,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val feedbackElapsedMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : NavigationDataExporter {
    private val mutableConnectionState = MutableStateFlow(ExportConnectionState.STOPPED)
    override val connectionState: StateFlow<ExportConnectionState> = mutableConnectionState
    private val mutableLastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = mutableLastError
    private val mutableStatus = MutableStateFlow(NavAssistV2ConnectionStatus.UNCONFIGURED)
    val status: StateFlow<NavAssistV2ConnectionStatus> = mutableStatus
    private val mutableDeliveryState = MutableStateFlow(NavAssistDeliveryState())
    val deliveryState: StateFlow<NavAssistDeliveryState> = mutableDeliveryState
    private val mutableResolvedEndpoint = MutableStateFlow<String?>(null)
    val resolvedEndpoint: StateFlow<String?> = mutableResolvedEndpoint
    private val mutableOemVehicleLaneState = MutableStateFlow(OemVehicleLaneState())
    val oemVehicleLaneState: StateFlow<OemVehicleLaneState> = mutableOemVehicleLaneState
    private val mutableLaneAnnouncement = MutableStateFlow<LaneAnnouncement?>(null)
    val laneAnnouncement: StateFlow<LaneAnnouncement?> = mutableLaneAnnouncement
    @Volatile private var completedAnnouncement: LaneAnnouncement? = null

    @Synchronized
    fun completeLaneAnnouncement(id: String, sessionId: String) {
        val pending = mutableLaneAnnouncement.value ?: return
        if (running && pending.id == id && pending.sessionId == sessionId &&
            feedbackElapsedMs() - pending.receivedAtElapsedMs in 0L..1_000L) completedAnnouncement = pending
    }

    private fun withSpeechReceipt(snapshot: NavAssistV2Snapshot): NavAssistV2Snapshot {
        val done = completedAnnouncement ?: return snapshot
        val pending = mutableLaneAnnouncement.value ?: return snapshot
        return if (done.id == pending.id && done.sessionId == snapshot.sessionId &&
            feedbackElapsedMs() - pending.receivedAtElapsedMs in 0L..1_000L)
            snapshot.copy(laneChangeSpeechCompletedId = done.id) else snapshot
    }

    private val scope = CoroutineScope(SupervisorJob() + publisherDispatcher)
    private val session by lazy { navigationSession ?: NavAssistV2Session(validForMs = config.validForMs) }

    @Volatile private var running = false
    @Volatile private var rediscoveryRequested = false
    private var publisherJob: Job? = null
    private var stopped = false
    @Volatile private var ownerConfirmed = false
    private var ownerReadyAtMs: Long? = null

    @Synchronized
    override fun start() {
        if (running || stopped) return
        if (!config.isConfigured()) {
            mutableResolvedEndpoint.value = null
            if (config.hasValidLifetime() && config.baseUrl.isNotBlank()) {
                mutableLastError.value = "NavAssist v2 URL 必须是有效的 http/https URL"
                mutableConnectionState.value = ExportConnectionState.ERROR
                mutableStatus.value = NavAssistV2ConnectionStatus.ERROR
            } else {
                mutableLastError.value = null
                mutableConnectionState.value = ExportConnectionState.STOPPED
                mutableStatus.value = NavAssistV2ConnectionStatus.UNCONFIGURED
            }
            return
        }

        val explicitEndpoint = if (config.usesDiscovery()) null else {
            val pinned = pinnedDeviceProvider()
            snapshotEndpoint(config.baseUrl)?.let { url -> pinned?.let { ResolvedNavAssistEndpoint(url, it.deviceId) } }
        }
        if (!config.usesDiscovery() && explicitEndpoint == null) {
            mutableLastError.value = "NavAssist v2 URL 必须是有效的 http/https URL"
            mutableConnectionState.value = ExportConnectionState.ERROR
            mutableStatus.value = NavAssistV2ConnectionStatus.ERROR
            return
        }

        running = true
        mutableConnectionState.value = ExportConnectionState.STARTING
        val intervalMs = config.intervalMs.coerceAtLeast(NavAssistV2Protocol.MIN_INTERVAL_MS)
        if (useUnauthenticatedUdp && config.usesDiscovery()) {
            publisherJob = scope.launch {
                while (isActive && running) {
                    val startedAtNs = System.nanoTime()
                    val attempt = session.serializedSend {
                        if (!isActive || !running) return@serializedSend null
                        val state = stateProvider() ?: return@serializedSend null
                        if (!isActive || !running) return@serializedSend null
                        val snapshot = withSpeechReceipt(session.nextSnapshot(stateForOwner(state), System.currentTimeMillis()))
                        val body = CanonicalJson.encode(snapshot).toByteArray(Charsets.UTF_8)
                        if (!isActive || !running) return@serializedSend null
                        val ack = runCatching { udpClient.send(body, snapshot.sessionId, snapshot.sequence) }.getOrNull()
                        recordOwnerResponse(ack != null)
                        publishIfRunning {
                            mutableDeliveryState.value = NavAssistDeliveryState(
                                snapshot.sessionId, snapshot.sequence, ack != null,
                                monotonicMs().takeIf { ack != null }, ownerConfirmed, snapshot.routeActive,
                            )
                        }
                        Pair(snapshot, ack)
                    }
                    if (attempt != null) {
                        val ack = attempt.second
                        publishIfRunning {
                            mutableLaneAnnouncement.value = ack?.announcement
                            if (ack?.announcement?.id != completedAnnouncement?.id) completedAnnouncement = null
                            if (ack != null) {
                                mutableResolvedEndpoint.value = "udp://${ack.host}:4213"
                                mutableOemVehicleLaneState.value = ack.vehicleLane ?: OemVehicleLaneState()
                                mutableLastError.value = null
                                mutableConnectionState.value = ExportConnectionState.CONNECTED
                                mutableStatus.value = NavAssistV2ConnectionStatus.ONLINE
                            } else {
                                mutableOemVehicleLaneState.value = OemVehicleLaneState()
                                mutableResolvedEndpoint.value = null
                                mutableLastError.value = "未收到 C3XL UDP 确认"
                                mutableConnectionState.value = ExportConnectionState.STARTING
                                mutableStatus.value = NavAssistV2ConnectionStatus.SCANNING
                            }
                        }
                    }
                    val elapsedMs = (System.nanoTime() - startedAtNs) / NANOS_PER_MILLISECOND
                    delay((intervalMs - elapsedMs).coerceAtLeast(0L))
                }
            }
            return
        }
        publisherJob = scope.launch {
            var endpoint = explicitEndpoint
            var consecutivePostFailures = 0
            if (endpoint != null) publishDiscovered(endpoint)
            while (isActive && running) {
                if (rediscoveryRequested && config.usesDiscovery()) {
                    rediscoveryRequested = false
                    resetOwnerHandshake()
                    endpoint = null
                    consecutivePostFailures = 0
                    publishIfRunning { mutableResolvedEndpoint.value = null }
                }
                if (endpoint == null) {
                    endpoint = discoverEndpoint()
                    if (!isActive || !running) break
                    if (endpoint == null) {
                        delay(discoveryRetryMs.coerceAtLeast(intervalMs))
                        continue
                    }
                }

                val startedAtNs = System.nanoTime()
                when (postLatest(endpoint)) {
                    PostResult.NO_STATE -> Unit
                    PostResult.SUCCESS -> {
                        consecutivePostFailures = 0
                        publishIfRunning {
                            mutableLastError.value = null
                            mutableConnectionState.value = ExportConnectionState.CONNECTED
                            mutableStatus.value = NavAssistV2ConnectionStatus.ONLINE
                        }
                    }
                    PostResult.FAILURE -> {
                        consecutivePostFailures += 1
                        // A single missed response is common on phone hotspots. Keep the authenticated
                        // endpoint for one direct retry; rediscover after repeated failures so a Wi-Fi
                        // change can still move the session to the C3XL's new address.
                        if (config.usesDiscovery() && consecutivePostFailures >= POST_FAILURES_BEFORE_REDISCOVERY) {
                            endpoint = null
                            consecutivePostFailures = 0
                            publishIfRunning { mutableResolvedEndpoint.value = null }
                        }
                    }
                }
                val elapsedMs = (System.nanoTime() - startedAtNs) / NANOS_PER_MILLISECOND
                delay((intervalMs - elapsedMs).coerceAtLeast(0L))
            }
        }
    }

    override fun stop() {
        synchronized(this) {
            if (stopped) return
            stopped = true
            running = false
            mutableLaneAnnouncement.value = null
            mutableDeliveryState.value = NavAssistDeliveryState()
            completedAnnouncement = null
            rediscoveryRequested = false
            publisherJob?.cancel()
            publisherJob = null
            scope.cancel()
            mutableResolvedEndpoint.value = null
            mutableOemVehicleLaneState.value = OemVehicleLaneState()
            mutableConnectionState.value = ExportConnectionState.STOPPED
            mutableStatus.value = if (config.hasValidLifetime()) {
                NavAssistV2ConnectionStatus.ERROR
            } else {
                NavAssistV2ConnectionStatus.UNCONFIGURED
            }
        }
        httpClient.close()
    }

    /** Drops a cached LAN address so navigation starts against the C3XL's current Wi-Fi address. */
    @Synchronized
    internal fun requestRediscovery() {
        if (!running || !config.usesDiscovery()) return
        rediscoveryRequested = true
        resetOwnerHandshake()
        mutableResolvedEndpoint.value = null
        mutableConnectionState.value = ExportConnectionState.STARTING
        mutableStatus.value = NavAssistV2ConnectionStatus.SCANNING
    }

    private fun discoverEndpoint(): ResolvedNavAssistEndpoint? {
        if (!running) return null
        publishIfRunning {
            mutableConnectionState.value = ExportConnectionState.STARTING
            mutableStatus.value = NavAssistV2ConnectionStatus.SCANNING
        }
        return when (val result = endpointDiscovery.discover()) {
            is NavAssistV2DiscoveryResult.Found -> discoveryEndpoint(result.sourceHost)
                ?.let { ResolvedNavAssistEndpoint(it, result.deviceId) }
                ?.also(::publishDiscovered)
                ?: failDiscovery("C3XL 返回了无效地址")
            NavAssistV2DiscoveryResult.NotFound -> null
            NavAssistV2DiscoveryResult.MultipleAuthenticatedHosts -> {
                publishIfRunning {
                    mutableLastError.value = "发现多个已认证 C3XL，已拒绝自动选择"
                    mutableConnectionState.value = ExportConnectionState.ERROR
                    mutableStatus.value = NavAssistV2ConnectionStatus.MULTIPLE_DEVICES
                }
                null
            }
            is NavAssistV2DiscoveryResult.Failed -> failDiscovery(result.reason)
        }
    }

    private fun failDiscovery(reason: String): ResolvedNavAssistEndpoint? {
        publishIfRunning {
            mutableLastError.value = reason
            mutableConnectionState.value = ExportConnectionState.ERROR
            mutableStatus.value = NavAssistV2ConnectionStatus.ERROR
        }
        return null
    }

    private fun publishDiscovered(endpoint: ResolvedNavAssistEndpoint) {
        publishIfRunning {
            mutableLastError.value = null
            mutableResolvedEndpoint.value = endpoint.url.toString()
            mutableConnectionState.value = ExportConnectionState.STARTING
            mutableStatus.value = NavAssistV2ConnectionStatus.DISCOVERED
        }
    }

    /** Serialize result publication with stop; never hold this monitor across network calls. */
    private inline fun publishIfRunning(update: () -> Unit) {
        synchronized(this) {
            if (running) update()
        }
    }

    private fun postLatest(endpoint: ResolvedNavAssistEndpoint): PostResult = session.serializedSend {
        if (!running) return@serializedSend PostResult.NO_STATE
        val state = stateProvider() ?: return@serializedSend PostResult.NO_STATE
        if (!running) return@serializedSend PostResult.NO_STATE
        val snapshot = session.nextSnapshot(stateForOwner(state), System.currentTimeMillis())
        runCatching {
            val body = CanonicalJson.encode(snapshot)
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val signature = identity.sign(
                NavAssistV3Auth.snapshotSignatureMaterial(
                    endpoint.deviceId, identity.keyId, NavAssistV2Protocol.ENDPOINT_PATH, bodyBytes,
                ),
            )
            if (!running) return@serializedSend PostResult.NO_STATE
            httpClient.post(endpoint.url, body, identity.keyId, signature)
        }.fold(
            onSuccess = {
                recordOwnerResponse(true)
                publishIfRunning {
                    mutableDeliveryState.value = NavAssistDeliveryState(snapshot.sessionId, snapshot.sequence,
                        true, monotonicMs(), ownerConfirmed, snapshot.routeActive)
                }
                PostResult.SUCCESS
            },
            onFailure = { error ->
                recordOwnerResponse(false)
                publishIfRunning {
                    mutableDeliveryState.value = NavAssistDeliveryState(snapshot.sessionId, snapshot.sequence)
                    mutableLastError.value = describeHttpFailure(endpoint.url, error)
                    mutableConnectionState.value = ExportConnectionState.ERROR
                    mutableStatus.value = NavAssistV2ConnectionStatus.ERROR
                }
                PostResult.FAILURE
            },
        )
    }

    @Synchronized
    private fun stateForOwner(state: NavigationState): NavigationState {
        if (!requireOwnerHandshake || ownerConfirmed) return state
        // Local C3 rejects source age > 2000 ms and future skew > 1000 ms.
        // Wait after acquiring the shared send lock so an old unacknowledged packet expires.
        if (ownerReadyAtMs == null) ownerReadyAtMs = monotonicMs() + ownerSettleMs
        return state.copy(navAssistControlAllowed = false)
    }

    @Synchronized
    private fun recordOwnerResponse(accepted: Boolean) {
        if (!running) return
        if (!accepted) {
            // UDP reply loss is link telemetry; C3's per-snapshot TTL owns data freshness.
            // Lifecycle and explicit rediscovery still reset the sender handshake.
            if (!useUnauthenticatedUdp) resetOwnerHandshake()
        } else {
            ownerConfirmed = !requireOwnerHandshake || ownerSettleMs == 0L || ownerReadyAtMs?.let { monotonicMs() > it } == true
        }
    }

    @Synchronized
    private fun resetOwnerHandshake() {
        ownerConfirmed = false
        ownerReadyAtMs = null
    }

    internal fun snapshotEndpoint(baseUrl: String): HttpUrl? {
        val parsed = baseUrl.trim().toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        return parsed.resolve(NavAssistV2Protocol.ENDPOINT_PATH)
    }

    internal fun discoveryEndpoint(sourceHost: String): HttpUrl? = runCatching {
        HttpUrl.Builder()
            .scheme("http")
            .host(sourceHost)
            .port(NavAssistV2Discovery.SNAPSHOT_PORT)
            .addPathSegments(NavAssistV2Protocol.ENDPOINT_PATH.removePrefix("/"))
            .build()
    }.getOrNull()

    private fun describeHttpFailure(endpoint: HttpUrl, error: Throwable): String {
        val target = "${endpoint.host}:${endpoint.port}"
        return when (error) {
            is SocketTimeoutException -> "NavAssist HTTP 超时：$target（热点延迟或 C3XL 繁忙）"
            is ConnectException -> "NavAssist 无法连接：$target"
            is UnknownHostException -> "NavAssist 地址无效：${endpoint.host}"
            else -> "NavAssist HTTP 发送失败：${error.message ?: error.javaClass.simpleName}"
        }
    }

    private enum class PostResult { NO_STATE, SUCCESS, FAILURE }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val DEFAULT_DISCOVERY_RETRY_MS = 1_000L
        const val POST_FAILURES_BEFORE_REDISCOVERY = 2
    }
}

internal data class ResolvedNavAssistEndpoint(
    val url: HttpUrl,
    val deviceId: String,
)
