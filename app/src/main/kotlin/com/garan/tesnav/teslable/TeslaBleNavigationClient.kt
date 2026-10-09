package com.garan.tesnav.teslable

import android.content.Context
import android.os.SystemClock
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng
import com.garan.tesnav.homeassistant.TeslaNavigationDestination
import com.garan.tesnav.util.NavigationTrace
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TeslaBleNavigationState(
    val status: String = "蓝牙同步未开启",
    val destination: TeslaNavigationDestination? = null,
    val name: String = "",
    val observedElapsedMs: Long? = null,
    val queryDurationMs: Long? = null,
    val destinationAbsentConfirmed: Boolean = false,
) {
    fun fresh(now: Long): Boolean = observedElapsedMs?.let { now >= it && now - it < 10000 } == true
}

/** Runs when destination sync is enabled. The only automatic request is GetDriveState. */
internal class TeslaBleNavigationClient(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(TeslaBleNavigationState())
    private val mutableDashboard = MutableStateFlow(VehicleDashboardData())
    val dashboard = mutableDashboard.asStateFlow()
    @Volatile var dashboardActive = false
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    @Volatile private var transport: TeslaBleTransport? = null
    private val prefs get() = context.getSharedPreferences("tesla_ble_navigation", Context.MODE_PRIVATE)
    val vin: String get() = prefs.getString("vin", "").orEmpty()
    val coordinateSystem: String get() = prefs.getString("coordinates", "UNKNOWN").orEmpty()
    val configured: Boolean get() = TeslaNavigationProtocol.validVin(vin) && coordinateSystem in setOf("GCJ02", "WGS84")

    fun configure(vin: String, coordinates: String) {
        require(TeslaNavigationProtocol.validVin(vin)) { "请填写有效的 17 位 VIN" }
        require(coordinates in setOf("GCJ02", "WGS84")) { "请先确认原车目的地坐标系" }
        check(job?.isActive != true) { "请先关闭蓝牙同步再修改配置" }
        check(prefs.edit().putString("vin", vin).putString("coordinates", coordinates).commit())
    }
    fun start() {
        stop(); require(configured) { "请先配置原车 VIN 和目的地坐标系" }
        job = scope.launch {
            var consecutiveFailures = 0
            var chargeReadDisabled = false
            while (isActive) {
                val connection = TeslaBleTransport(context)
                transport = connection
                var stage = "connect"
                var stageStarted = SystemClock.elapsedRealtime()
                try {
                    NavigationTrace.append(context.filesDir, "connect_start", "TesNav-TeslaBLE")
                    mutableState.value = TeslaBleNavigationState(status = "正在连接原车蓝牙…")
                    val identity = TeslaBleIdentity.loadOrCreate(context)
                    connection.connect(vin)
                    val connectMs = SystemClock.elapsedRealtime() - stageStarted
                    stage = "authenticate"
                    stageStarted = SystemClock.elapsedRealtime()
                    var session = TeslaNavigationSession(vin, identity, SystemClock::elapsedRealtime)
                    val handshake = session.handshake()
                    connection.send(handshake.bytes)
                    session.acceptHandshake(connection.receiveFor(session, handshake), handshake)
                    NavigationTrace.append(context.filesDir,
                        "ready connectMs=$connectMs authMs=${SystemClock.elapsedRealtime() - stageStarted}", "TesNav-TeslaBLE")
                    var queryRetries = 0
                    var sessionRefreshes = 0
                    var chargeReadAt: Long? = null
                    var lastQueryIncludedCharge = false
                    while (isActive) {
                        try {
                            val queryStarted = SystemClock.elapsedRealtime()
                            stage = "query_write"
                            stageStarted = queryStarted
                            val includeCharge = !chargeReadDisabled && dashboardActive && (chargeReadAt == null || queryStarted - chargeReadAt >= 10000)
                            lastQueryIncludedCharge = includeCharge
                            val request = session.query(includeCharge)
                            connection.send(request.bytes)
                            val writtenAt = SystemClock.elapsedRealtime()
                            stage = "query_reply"
                            stageStarted = writtenAt
                            val response = session.decrypt(connection.receiveFor(session, request), request)
                            val receivedAt = SystemClock.elapsedRealtime()
                            val raw = TeslaNavigationProtocol.destination(response)
                            val drive = TeslaWire.bytes(TeslaWire.bytes(response, 2)!!, 5)!!
                            val fields = TeslaWire.fields(drive)
                            fun routeScalar(n: Int): String = fields.lastOrNull { it.number == n && it.type == 5 }?.bytes?.let {
                                val value = java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                                when { !value.isFinite() -> "invalid"; value == 0f -> "zero"; value > 0f -> "positive"; else -> "negative" }
                            } ?: "absent"
                            NavigationTrace.append(context.filesDir,
                                "route_fields namePresent=${TeslaWire.bytes(drive, 7) != null} nameEmpty=${raw?.name.isNullOrEmpty()} " +
                                    "coordinatePresent=${TeslaWire.bytes(drive, 12) != null} minutes=${routeScalar(8)} miles=${routeScalar(9)} " +
                                    "routeUpdate=${TeslaWire.value(drive, 14)} absentConfirmed=${TeslaNavigationProtocol.destinationAbsent(response)}", "TesNav-TeslaBLE")
                            val displayResult = runCatching { TeslaNavigationProtocol.dashboard(response, receivedAt,
                                receivedAt - queryStarted, mutableDashboard.value) }
                            mutableDashboard.value = displayResult.getOrDefault(VehicleDashboardData())
                            if (includeCharge || displayResult.isFailure) {
                                val data = mutableDashboard.value
                                NavigationTrace.append(context.filesDir,
                                    "dashboard_fields speed=${data.speed != null} gear=${data.gear != null} " +
                                        "battery=${data.battery != null} range=${data.range != null} " +
                                        "queryMs=${receivedAt - queryStarted} parseError=${displayResult.exceptionOrNull()?.javaClass?.simpleName ?: "none"}",
                                    "TesNav-TeslaBLE")
                            }
                            if (includeCharge) chargeReadAt = receivedAt
                            val converted = raw?.let {
                                if (coordinateSystem == "WGS84") CoordinateConverter(context)
                                    .from(CoordinateConverter.CoordType.GPS).coord(LatLng(it.latitude, it.longitude)).convert()
                                else LatLng(it.latitude, it.longitude)
                            }
                            val destination = converted?.let { TeslaNavigationDestination(it.latitude, it.longitude) }
                            NavigationTrace.append(context.filesDir,
                                "query_complete writeMs=${writtenAt - queryStarted} replyMs=${receivedAt - writtenAt} " +
                                    "hasDestination=${destination != null} changed=${destination != mutableState.value.destination}", "TesNav-TeslaBLE")
                            mutableState.value = TeslaBleNavigationState(
                                status = if (raw == null) "已连接 · 原车未返回导航目的地" else "已连接 · 目的地已读取",
                                destination = destination,
                                name = raw?.name.orEmpty(), observedElapsedMs = receivedAt,
                                queryDurationMs = receivedAt - queryStarted,
                                destinationAbsentConfirmed = raw == null && TeslaNavigationProtocol.destinationAbsent(response))
                            consecutiveFailures = 0
                            queryRetries = 0
                            sessionRefreshes = 0
                            // Keep one request in flight; do not add three seconds to every vehicle response.
                            delay(1000)
                        } catch (e: Exception) {
                            if (lastQueryIncludedCharge) chargeReadDisabled = true
                            currentCoroutineContext().ensureActive()
                            val action = TeslaBleRecovery.boundedAction(e, stage, queryRetries, sessionRefreshes)
                            val fault = (e as? TeslaQueryFault)?.code
                            NavigationTrace.append(context.filesDir,
                                "query_failed stage=$stage type=${e.javaClass.simpleName} fault=${fault ?: -1} action=$action",
                                "TesNav-TeslaBLE")
                            when {
                                action == TeslaBleRecovery.Action.RETRY_QUERY -> {
                                    queryRetries++
                                    mutableState.value = TeslaBleNavigationState(status = "原车暂未响应，稍后重试查询")
                                    delay(1_000L * queryRetries)
                                }
                                action == TeslaBleRecovery.Action.REFRESH_SESSION -> {
                                    sessionRefreshes++
                                    mutableState.value = TeslaBleNavigationState(status = "正在恢复原车蓝牙认证…")
                                    delay(1000)
                                    stage = "session_refresh"
                                    stageStarted = SystemClock.elapsedRealtime()
                                    // A fresh challenge and route; old replies cannot authenticate this session.
                                    session = TeslaNavigationSession(vin, identity, SystemClock::elapsedRealtime)
                                    val refresh = session.handshake()
                                    connection.send(refresh.bytes)
                                    session.acceptHandshake(connection.receiveFor(session, refresh), refresh)
                                    NavigationTrace.append(context.filesDir, "session_refresh_complete", "TesNav-TeslaBLE")
                                }
                                else -> throw e
                            }
                        }
                    }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(4)
                    val reconnectDelay = TeslaBleRecovery.reconnectDelayMs(consecutiveFailures)
                    NavigationTrace.append(context.filesDir,
                        "failed stage=$stage durationMs=${SystemClock.elapsedRealtime() - stageStarted} " +
                            "type=${e.javaClass.simpleName} fault=${(e as? TeslaQueryFault)?.code ?: -1} " +
                            "failureCount=$consecutiveFailures reconnectDelayMs=$reconnectDelay", "TesNav-TeslaBLE")
                    mutableState.value = TeslaBleNavigationState(status = when (e) {
                        is TimeoutCancellationException -> "原车蓝牙响应超时，将重新连接"
                        is SecurityException -> "蓝牙权限未授予，请在设置中重新开启同步"
                        else -> e.message ?: "原车蓝牙查询失败"
                    })
                } finally { connection.close(); if (transport === connection) transport = null }
                delay(TeslaBleRecovery.reconnectDelayMs(consecutiveFailures))
            }
        }
    }
    suspend fun pair() {
        check(job?.isActive != true) { "请先关闭蓝牙同步再配钥" }
        require(TeslaNavigationProtocol.validVin(vin)) { "请先保存 VIN" }
        val connection = TeslaBleTransport(context)
        try {
            val identity = TeslaBleIdentity.loadOrCreate(context)
            connection.connect(vin)
            connection.send(TeslaNavigationProtocol.pairRequest(TeslaNavigationProtocol.publicBytes(identity.public)))
            // A completed GATT write is not proof of vehicle enrollment.
            mutableState.value = TeslaBleNavigationState(status = "配钥请求已发送，请按原车提示刷卡确认，然后开启同步验证")
            withTimeout(60000) {
                while (true) {
                    delay(2000)
                    val session = TeslaNavigationSession(vin, identity, SystemClock::elapsedRealtime)
                    val request = session.handshake()
                    connection.send(request.bytes)
                    try {
                        session.acceptHandshake(connection.receiveFor(session, request), request)
                        mutableState.value = TeslaBleNavigationState(status = "配钥已通过原车认证，可以开启自动同步")
                        break
                    } catch (e: Exception) { currentCoroutineContext().ensureActive() }
                }
            }
        } finally { connection.close() }
    }
    fun stop() {
        job?.cancel(); job = null; transport?.close(); transport = null
        mutableDashboard.value = VehicleDashboardData()
        mutableState.value = TeslaBleNavigationState()
    }
    fun release() { stop(); scope.cancel() }
}
