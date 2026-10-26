package com.garan.tesnav.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.model.AMapNaviPath
import com.garan.tesnav.BuildConfig
import com.garan.tesnav.MainActivity
import com.garan.tesnav.config.AmapConfiguration
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.data.CommaStateStore
import com.garan.tesnav.data.NavigationRepository
import com.garan.tesnav.data.NavigationStateStore
import com.garan.tesnav.export.ExportConfig
import com.garan.tesnav.export.ExportConnectionState
import com.garan.tesnav.export.AndroidKeystoreNavAssistIdentity
import com.garan.tesnav.export.HttpNavAssistV2Exporter
import com.garan.tesnav.export.NavAssistModelRoute
import com.garan.tesnav.export.NavAssistPairingStore
import com.garan.tesnav.export.NavAssistV2ConnectionStatus
import com.garan.tesnav.export.NavAssistV2ExportConfig
import com.garan.tesnav.export.NavAssistV2Session
import com.garan.tesnav.export.NavAssistDeliveryState
import com.garan.tesnav.export.UdpNavAssistV2EndpointDiscovery
import com.garan.tesnav.export.WebSocketNavigationDataExporter
import com.garan.tesnav.homeassistant.HomeAssistantConnectionState
import com.garan.tesnav.homeassistant.HomeAssistantNavigationClient
import com.garan.tesnav.homeassistant.TeslaNavigationDestination
import com.garan.tesnav.model.GeoPoint
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.OemVehicleLaneState
import com.garan.tesnav.util.NavigationTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/** Keeps the navigation engine, state callbacks, and WebSocket alive in background. */
class NavigationForegroundService : Service() {
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var appliedApiKey: String? = null
    private var deferredApiKey: String? = null

    lateinit var stateStore: NavigationStateStore
        private set
    lateinit var commaStateStore: CommaStateStore
        private set
    lateinit var exporter: WebSocketNavigationDataExporter
        private set
    private lateinit var navAssistV2Exporter: HttpNavAssistV2Exporter
    @Volatile private var modelRoute: NavAssistModelRoute? = null
        private set
    internal fun modelMapPreviewRoute(): NavAssistModelRoute? {
        val state = stateStore.state.value
        return modelRoute?.takeIf { navigationDataSource.value == NavigationDataSource.AMAP_API &&
            state.acceptedPathId == it.pathId && state.routeRevision == it.routeRevision }
    }
    private lateinit var navAssistSession: NavAssistV2Session
    private val sourceGate = com.garan.tesnav.model.NavigationSourceGate(
        BuildConfig.NAV_ASSIST_SOURCE_BUDGET_MS, BuildConfig.NAV_ASSIST_PROGRESS_BUDGET_MS,
    )
    lateinit var homeAssistantClient: HomeAssistantNavigationClient
        private set
    private lateinit var repository: NavigationRepository
    private var locationBridge: NavigationLocationBridge? = null
    private var amapVehicleLocationPublisher: AmapVehicleLocationPublisher? = null
    private val amapAutoVehicleLocation = com.garan.tesnav.model.VehicleLocationObservation()
    private val amapAutoLocationSelector = com.garan.tesnav.model.NavigationLocationFallback()
    private lateinit var laneSpeech: LaneChangeSpeech
    private val exporterObservationJobs = mutableListOf<Job>()
    private var legacyExportEnabled = false
    private lateinit var navAssistIdentity: AndroidKeystoreNavAssistIdentity
    private lateinit var navAssistPairingStore: NavAssistPairingStore

    private val mutableCommaConnectionState = MutableStateFlow(ExportConnectionState.STOPPED)
    val commaConnectionState: StateFlow<ExportConnectionState> = mutableCommaConnectionState.asStateFlow()
    private val mutableCommaLastError = MutableStateFlow<String?>(null)
    val commaLastError: StateFlow<String?> = mutableCommaLastError.asStateFlow()
    private val mutableNavAssistV2Status = MutableStateFlow(NavAssistV2ConnectionStatus.UNCONFIGURED)
    val navAssistV2Status: StateFlow<NavAssistV2ConnectionStatus> = mutableNavAssistV2Status.asStateFlow()
    private val mutableNavAssistDeliveryState = MutableStateFlow(NavAssistDeliveryState())
    val navAssistDeliveryState: StateFlow<NavAssistDeliveryState> = mutableNavAssistDeliveryState.asStateFlow()
    private val mutableNavAssistV2ResolvedEndpoint = MutableStateFlow<String?>(null)
    val navAssistV2ResolvedEndpoint: StateFlow<String?> = mutableNavAssistV2ResolvedEndpoint.asStateFlow()
    private val mutableNavAssistV2LastError = MutableStateFlow<String?>(null)
    val navAssistV2LastError: StateFlow<String?> = mutableNavAssistV2LastError.asStateFlow()
    private val mutableOemVehicleLaneState = MutableStateFlow(OemVehicleLaneState())
    val oemVehicleLaneState: StateFlow<OemVehicleLaneState> = mutableOemVehicleLaneState.asStateFlow()
    private val mutableAmapAutoBroadcasts = MutableStateFlow<Map<String, AmapAutoBroadcastSnapshot>>(emptyMap())
    internal val amapAutoBroadcasts: StateFlow<Map<String, AmapAutoBroadcastSnapshot>> = mutableAmapAutoBroadcasts.asStateFlow()
    private val mutableAmapAutoNavigationState = MutableStateFlow<NavigationState?>(null)
    internal val amapAutoNavigationState: StateFlow<NavigationState?> = mutableAmapAutoNavigationState.asStateFlow()
    private val mutableNavigationDataSource = MutableStateFlow(NavigationDataSource.AMAP_API)
    val navigationDataSource: StateFlow<NavigationDataSource> = mutableNavigationDataSource.asStateFlow()
    lateinit var selectedNavigationStateFlow: StateFlow<NavigationState>
        private set

    private val mutableTeslaSyncEnabled = MutableStateFlow(false)
    val teslaSyncEnabled: StateFlow<Boolean> = mutableTeslaSyncEnabled.asStateFlow()
    internal lateinit var teslaBleClient: com.garan.tesnav.teslable.TeslaBleNavigationClient
        private set
    private val mutableTeslaBleSyncEnabled = MutableStateFlow(false)
    internal val teslaBleSyncEnabled = mutableTeslaBleSyncEnabled.asStateFlow()

    private var commaStateReady = false
    private var lastTeslaNavActive: Boolean? = null
    private var routeRequestDestination: TeslaNavigationDestination? = null
    private var activeTeslaDestination: TeslaNavigationDestination? = null
    private var failedTeslaDestination: TeslaNavigationDestination? = null
    private var failedTeslaDestinationAttempts = 0
    private var failedTeslaDestinationRetryAt = 0L
    private var teslaAutoRequestId = 0L
    private val mutableTeslaAutoSyncStatus = MutableStateFlow("")
    internal val teslaAutoSyncStatus = mutableTeslaAutoSyncStatus.asStateFlow()
    private var lastNotificationContent: String? = null
    private var lastNavAssistDiagnosticKey: String? = null
    private var phoneDiagnostics: PhoneNavigationDiagnostics? = null
    val diagnosticRecordingStatus: String
        get() = phoneDiagnostics?.status?.value ?: "诊断记录：未启用"
    private var trafficLightReceiver: BroadcastReceiver? = null
    private val amapAutoSeenKeyTypes = mutableSetOf<Int>()

    inner class LocalBinder : Binder() {
        fun getService(): NavigationForegroundService = this@NavigationForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        mutableNavigationDataSource.value = NavigationSourcePolicy.restore(
            preferences().getString(NAVIGATION_SOURCE_KEY, null),
            BuildConfig.EXTENDED_NAV_SOURCES,
        )
        if (mutableNavigationDataSource.value == NavigationDataSource.AMAP_AUTO) {
            sourceGate.arm(android.os.SystemClock.elapsedRealtime())
        }
        createNotificationChannel()
        promoteToForeground("正在启动导航服务")
        if (!AmapConfiguration.prepare(applicationContext)) {
            stopSelf()
            return
        }
        appliedApiKey = AmapConfiguration.effectiveKey(applicationContext)
        acquireWakeLock()

        MapsInitializer.updatePrivacyShow(applicationContext, true, true)
        MapsInitializer.updatePrivacyAgree(applicationContext, true)
        AMapLocationClient.updatePrivacyShow(applicationContext, true, true)
        AMapLocationClient.updatePrivacyAgree(applicationContext, true)

        stateStore = NavigationStateStore()
        selectedNavigationStateFlow = combine(stateStore.state, amapAutoNavigationState, navigationDataSource) {
            api, auto, source -> NavigationSourcePolicy.stateFor(source, api, auto)
        }.stateIn(scope, SharingStarted.Eagerly, selectedNavigationState())
        if (BuildConfig.EXTENDED_NAV_SOURCES) {
            phoneDiagnostics = PhoneNavigationDiagnostics(
                java.io.File(filesDir, "navigation-diagnostics"),
                { navAssistV2ResolvedEndpoint.value }, { navAssistDeliveryState.value },
                { selectedNavigationStateFlow.value },
            ).also { it.start(scope) }
        }
        registerTrafficLightReceiver()
        commaStateStore = CommaStateStore()
        homeAssistantClient = HomeAssistantNavigationClient()
        teslaBleClient = com.garan.tesnav.teslable.TeslaBleNavigationClient(applicationContext)
        // A persisted preference is not authority to resume an interrupted driving event.
        mutableTeslaSyncEnabled.value = false
        if (preferences().getBoolean(HA_SYNC_ENABLED, false)) {
            stateStore.update { copy(errorMessage = "自动同步尚未恢复，请在设置中重新开启") }
        }
        // v3 uses an Android Keystore identity; remove the obsolete shared-secret preference on upgrade.
        getSharedPreferences("navassist_v2", MODE_PRIVATE).edit().clear().apply()
        navAssistIdentity = AndroidKeystoreNavAssistIdentity.loadOrCreate(applicationContext)
        navAssistPairingStore = NavAssistPairingStore(applicationContext)
        repository = NavigationRepository(applicationContext, stateStore) { path ->
            modelRoute = path?.let {
                NavAssistModelRoute(it.pathid, stateStore.state.value.routeRevision,
                    it.coordList.orEmpty().map { point -> GeoPoint(point.latitude, point.longitude) })
            }
            if (!legacyExportEnabled || !::exporter.isInitialized) return@NavigationRepository
            if (path == null) {
                exporter.clearRoute()
            } else {
                exporter.publishRoute(
                    pathId = path.pathid,
                    totalDistanceMeters = path.allLength,
                    points = path.coordList.orEmpty().map { GeoPoint(it.latitude, it.longitude) },
                )
            }
        }
        repository.initialize()
        locationBridge = NavigationLocationBridge(this, repository, ::refreshAmapAutoLocation)
        if (BuildConfig.EXTENDED_NAV_SOURCES) amapVehicleLocationPublisher = AmapVehicleLocationPublisher(this)
        scope.launch {
            while (true) {
                locationBridge?.update(selectedNavigationState(), mutableOemVehicleLaneState.value)
                refreshAmapAutoLocation()
                amapVehicleLocationPublisher?.update(
                    mutableNavigationDataSource.value == NavigationDataSource.AMAP_AUTO,
                    mutableOemVehicleLaneState.value)
                kotlinx.coroutines.delay(200)
            }
        }
        laneSpeech = LaneChangeSpeech(this,
            enabled = { repository.speechMode != com.garan.tesnav.config.SpeechMode.MUTED &&
                selectedNavigationState().navigationMode == NavigationMode.REALTIME },
            completed = { id, session ->
                if (::navAssistV2Exporter.isInitialized) navAssistV2Exporter.completeLaneAnnouncement(id, session)
            })
        rebuildDataExporters()
        observeRuntime()
        // The user opts into restoring destination sync, not an old driving event or route.
        if (BuildConfig.EXTENDED_NAV_SOURCES && teslaBleClient.configured &&
            preferences().getBoolean(TESLA_BLE_SYNC_ENABLED, true)) {
            setTeslaBleSyncEnabled(true)?.let { mutableTeslaAutoSyncStatus.value = "原车同步未开启：$it" }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = if (::repository.isInitialized) binder else null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::repository.isInitialized) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_SERVICE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        updateNotification()
        return START_STICKY
    }

    fun planRoute(latitude: Double, longitude: Double): Boolean {
        if (mutableTeslaBleSyncEnabled.value) setTeslaBleSyncEnabled(false, persistPreference = false)
        return repository.planRoute(latitude, longitude)
    }
    internal fun navigateAmapAutoDestination(name: String, latitude: Double, longitude: Double): Boolean {
        if (mutableTeslaBleSyncEnabled.value) setTeslaBleSyncEnabled(false, persistPreference = false)
        return com.garan.tesnav.ui.AmapAutoActions.navigate(this, name, latitude, longitude)
    }
    fun selectRoute(routeId: Int): Boolean = repository.selectRoute(routeId)
    fun startRealtime(): Boolean = repository.startRealtime().also { accepted ->
        if (accepted) sourceGate.arm(android.os.SystemClock.elapsedRealtime())
        if (accepted && ::navAssistV2Exporter.isInitialized) navAssistV2Exporter.requestRediscovery()
    }
    fun startSimulation(): Boolean = repository.startSimulation().also { accepted ->
        if (accepted && ::navAssistV2Exporter.isInitialized) navAssistV2Exporter.requestRediscovery()
    }
    fun pauseSimulation(): Boolean = repository.pauseSimulation()
    fun resumeSimulation(): Boolean = repository.resumeSimulation()
    fun setSpeechEnabled(enabled: Boolean): Boolean = repository.setSpeechEnabled(enabled).also {
        if (!enabled) laneSpeech.cancel()
    }
    val speechMode: com.garan.tesnav.config.SpeechMode get() = repository.speechMode
    fun setSpeechMode(mode: com.garan.tesnav.config.SpeechMode): Boolean = repository.setSpeechMode(mode).also {
        if (mode == com.garan.tesnav.config.SpeechMode.MUTED) laneSpeech.cancel()
    }
    fun stopNavigation() {
        if (mutableTeslaBleSyncEnabled.value) setTeslaBleSyncEnabled(false, persistPreference = false)
        laneSpeech.cancel()
        sourceGate.disarm()
        repository.stopNavigation()
    }
    fun refreshAMapConfiguration(): String? {
        val key = AmapConfiguration.effectiveKey(applicationContext) ?: return null
        if (key == appliedApiKey || selectedNavigationState().navigationMode != NavigationMode.IDLE ||
            stateStore.state.value.navigationMode != NavigationMode.IDLE) return null
        val restartMessage = "导航配置已保存；地图仍占用导航引擎，请在系统设置中强行停止本应用后重新打开以生效"
        if (key == deferredApiKey) {
            stateStore.update { copy(errorMessage = restartMessage) }
            return restartMessage
        }
        if (!repository.release()) {
            deferredApiKey = key
            repository.initialize()
            stateStore.update { copy(errorMessage = restartMessage) }
            return restartMessage
        }
        AmapConfiguration.prepare(applicationContext)
        if (repository.initialize().isSuccess) {
            appliedApiKey = key
            deferredApiKey = null
        }
        return null
    }
    fun currentPath(): AMapNaviPath? = repository.currentPath().takeIf {
        mutableNavigationDataSource.value == NavigationDataSource.AMAP_API
    }

    fun canEditAmapKey(): Boolean = selectedNavigationState().navigationMode == NavigationMode.IDLE &&
        stateStore.state.value.navigationMode == NavigationMode.IDLE

    fun navAssistPairedDeviceId(): String? = navAssistPairingStore.pinnedDevice()?.deviceId

    fun clearNavAssistPairing(): String? {
        if (!navAssistPairingStore.clear()) return "清除自动配对失败"
        rebuildDataExporters()
        return null
    }

    fun setTeslaSyncEnabled(enabled: Boolean, persistBlePreference: Boolean = true) {
        if (mutableTeslaSyncEnabled.value == enabled && !mutableTeslaBleSyncEnabled.value) return
        if (persistBlePreference && (enabled || mutableTeslaBleSyncEnabled.value)) {
            preferences().edit().putBoolean(TESLA_BLE_SYNC_ENABLED, false).apply()
        }
        mutableTeslaBleSyncEnabled.value = false
        teslaBleClient.stop()
        mutableTeslaSyncEnabled.value = enabled
        // HA synchronization owns only routes started by HA on the API source.
        // Toggling the preference is neither an explicit start nor a stop of Auto/manual navigation.
        if (!enabled && NavigationSourcePolicy.syncOwnsNavigation(
                mutableNavigationDataSource.value, stateStore.state.value)) sourceGate.disarm()
        preferences().edit().putBoolean(HA_SYNC_ENABLED, enabled).apply()
        resetTeslaSyncTracking()
        if (enabled) startHomeAssistant() else homeAssistantClient.stop()
    }

    internal fun setTeslaBleSyncEnabled(enabled: Boolean, persistPreference: Boolean = true): String? {
        if (enabled == mutableTeslaBleSyncEnabled.value) {
            if (persistPreference) preferences().edit().putBoolean(TESLA_BLE_SYNC_ENABLED, enabled).apply()
            return null
        }
        if (enabled) {
            if (!BuildConfig.EXTENDED_NAV_SOURCES) return "当前应用未启用原车蓝牙同步"
            if (!teslaBleClient.configured) return "请先配置 VIN 和目的地坐标系"
            val permissions = if (Build.VERSION.SDK_INT >= 31) listOf(
                android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (permissions.any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                return "请在蓝牙同步设置中授予蓝牙权限"
            }
            val state = selectedNavigationState()
            if (mutableNavigationDataSource.value == NavigationDataSource.AMAP_API &&
                state.navigationMode != NavigationMode.IDLE && !state.startedFromTeslaSync) return "请先结束当前导航再开启原车同步"
        }
        setTeslaSyncEnabled(false, persistBlePreference = false)
        mutableTeslaBleSyncEnabled.value = enabled
        mutableTeslaSyncEnabled.value = enabled
        resetTeslaSyncTracking()
        if (enabled) {
            try {
                promoteToForeground("原车蓝牙目的地同步")
                teslaBleClient.start()
            } catch (e: Exception) {
                mutableTeslaBleSyncEnabled.value = false
                mutableTeslaSyncEnabled.value = false
                return e.message ?: "蓝牙同步启动失败"
            }
        }
        if (persistPreference) preferences().edit().putBoolean(TESLA_BLE_SYNC_ENABLED, enabled).apply()
        return null
    }

    fun selectNavigationDataSource(source: NavigationDataSource): String? {
        if (!NavigationSourcePolicy.isVisible(source, BuildConfig.EXTENDED_NAV_SOURCES)) {
            return "当前应用不支持${source.displayName}"
        }
        if (!source.integrated) return "${source.displayName}接口已保留，当前构建尚未配置SDK和授权"
        if (mutableNavigationDataSource.value == source) return null
        if (mutableTeslaBleSyncEnabled.value) resetTeslaSyncTracking()
        laneSpeech.cancel()
        sourceGate.disarm()
        if (::navAssistV2Exporter.isInitialized) navAssistV2Exporter.stop()
        mutableNavAssistDeliveryState.value = NavAssistDeliveryState()
        if (source == NavigationDataSource.AMAP_AUTO) {
            mutableAmapAutoNavigationState.value = mutableAmapAutoNavigationState.value?.copy(
                navAssistControlAllowed = false, navAssistSourceStatus = "awaitingGuidance")
        } else {
            stateStore.update { copy(navAssistControlAllowed = false, navAssistSourceStatus = "awaitingGuidance") }
        }
        mutableNavigationDataSource.value = source
        sourceGate.arm(android.os.SystemClock.elapsedRealtime())
        preferences().edit().putString(NAVIGATION_SOURCE_KEY, source.name).apply()
        rebuildDataExporters(sourceChanged = true)
        if (mutableTeslaBleSyncEnabled.value) tryStartTeslaNavigation()
        NavigationTrace.append(filesDir, "source selected=${source.name.lowercase()}", AMAP_AUTO_TRACE_TAG)
        return null
    }

    fun toggleAmapNavigationDataSource(): NavigationDataSource {
        val source = if (mutableNavigationDataSource.value == NavigationDataSource.AMAP_API) {
            NavigationDataSource.AMAP_AUTO
        } else {
            NavigationDataSource.AMAP_API
        }
        check(selectNavigationDataSource(source) == null)
        return source
    }

    override fun onDestroy() {
        trafficLightReceiver?.let { runCatching { unregisterReceiver(it) } }
        trafficLightReceiver = null
        locationBridge?.close()
        amapVehicleLocationPublisher?.close()
        if (::laneSpeech.isInitialized) laneSpeech.close()
        exporterObservationJobs.forEach(Job::cancel)
        exporterObservationJobs.clear()
        if (::homeAssistantClient.isInitialized) homeAssistantClient.release()
        if (::teslaBleClient.isInitialized) teslaBleClient.release()
        if (::navAssistV2Exporter.isInitialized) navAssistV2Exporter.stop()
        if (::exporter.isInitialized) exporter.stop()
        if (::repository.isInitialized) repository.release()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun registerTrafficLightReceiver() {
        if (trafficLightReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context?, intent: Intent?) {
                val extras = intent?.extras ?: return
                val values = extras.keySet().associateWith { key -> runCatching { extras.get(key) }.getOrNull() }
                val observedAtMs = System.currentTimeMillis()
                val snapshot = AmapAutoBroadcast.capture(intent.action, values, observedAtMs)
                mutableAmapAutoBroadcasts.value = AmapAutoBroadcast.retain(mutableAmapAutoBroadcasts.value, snapshot)

                val keyType = snapshot.keyType
                if (keyType != null && amapAutoSeenKeyTypes.add(keyType)) {
                    NavigationTrace.append(
                        filesDir,
                        "received action=${intent.action} keyType=$keyType keys=${snapshot.extras.keys.sorted().joinToString(",")}",
                        AMAP_AUTO_TRACE_TAG,
                    )
                }
                if (keyType == AMAP_AUTO_NAVIGATION_TYPE) {
                    mutableAmapAutoNavigationState.value = AmapAutoBroadcast.navigation(
                        previous = mutableAmapAutoNavigationState.value,
                        locationFallback = amapAutoLocationFallback(),
                        extras = values,
                        observedAtMs = observedAtMs,
                        receivedElapsedMs = android.os.SystemClock.elapsedRealtime(),
                        sourceBudgetMs = BuildConfig.NAV_ASSIST_SOURCE_BUDGET_MS,
                    )
                }
                if (keyType == AMAP_AUTO_LANE_TYPE) {
                    val laneFields = runCatching {
                        val obj = com.google.gson.JsonParser.parseString(values["EXTRA_DRIVE_WAY"] as? String).asJsonObject
                        val info = obj.getAsJsonArray("drive_way_info")
                        "enabled=${obj["drive_way_enabled"]} size=${obj["drive_way_size"]} " +
                            "lanes=" + info?.take(16)?.joinToString(";") { item ->
                                val lane = item.asJsonObject
                                "keys=${lane.keySet().sorted().joinToString(",")} back=${lane["drive_way_lane_Back_icon"]} " +
                                    "front=${lane["drive_way_lane_Front_icon"]} advised=${lane["trafficLaneAdvised"]}"
                            }
                    }.getOrElse { "parseError=${it.javaClass.simpleName}" }
                    NavigationTrace.append(filesDir, "lane_fields $laneFields", AMAP_AUTO_TRACE_TAG)
                    mutableAmapAutoNavigationState.value = AmapAutoBroadcast.driveWay(
                        previous = mutableAmapAutoNavigationState.value,
                        raw = values["EXTRA_DRIVE_WAY"] as? String,
                        observedAtMs = observedAtMs,
                        receivedElapsedMs = android.os.SystemClock.elapsedRealtime(),
                        sourceBudgetMs = BuildConfig.NAV_ASSIST_SOURCE_BUDGET_MS,
                    )
                }
                if (keyType == AMAP_AUTO_LANE_TYPE) {
                    val state = mutableAmapAutoNavigationState.value
                    NavigationTrace.append(filesDir,
                        "lane_result count=${state?.lanes?.size} event=${state?.laneLastEvent} " +
                            "guidanceAgeMs=${state?.guidanceCallbackElapsedMs?.let { android.os.SystemClock.elapsedRealtime() - it }}",
                        AMAP_AUTO_TRACE_TAG)
                }
                if (keyType != AMAP_AUTO_TRAFFIC_LIGHT_TYPE) return

                val observation = AmapAutoBroadcast.trafficLight(values, observedAtMs)
                if (observation == null) {
                    NavigationTrace.append(
                        filesDir,
                        "trafficLight ignored keys=${snapshot.extras.keys.sorted().joinToString(",")}",
                        AMAP_AUTO_TRACE_TAG,
                    )
                    return
                }
                stateStore.update { copy(trafficLight = observation) }
                mutableAmapAutoNavigationState.value = (mutableAmapAutoNavigationState.value ?: NavigationState())
                    .copy(trafficLight = observation)
                NavigationTrace.append(
                    filesDir,
                    "trafficLight status=${observation.status} direction=${observation.direction} " +
                        "countdown=${observation.countdownSeconds}",
                    AMAP_AUTO_TRACE_TAG,
                )
            }
        }
        val filter = IntentFilter().apply {
            addAction("AUTONAVI_STANDARD_BROADCAST_SEND")
            addAction("AUTONAVI_STANDARD_BROADCAST_RECV")
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        trafficLightReceiver = receiver
        NavigationTrace.append(filesDir, "receiver registered", AMAP_AUTO_TRACE_TAG)
    }

    /** Rebuilds both exporters so a token change takes effect without reinstalling or restarting the app. */
    private fun rebuildDataExporters(sourceChanged: Boolean = false) {
        exporterObservationJobs.forEach(Job::cancel)
        exporterObservationJobs.clear()
        if (::navAssistV2Exporter.isInitialized) navAssistV2Exporter.stop()
        if (::exporter.isInitialized) exporter.stop()

        val navAssistV2Config = NavAssistV2ExportConfig(
            baseUrl = BuildConfig.NAV_ASSIST_V2_URL,
            intervalMs = BuildConfig.NAV_ASSIST_V2_INTERVAL_MS,
        )
        if (!::navAssistSession.isInitialized) {
            navAssistSession = NavAssistV2Session(validForMs = navAssistV2Config.validForMs)
        } else if (sourceChanged) {
            navAssistSession.switchSource()
        }
        legacyExportEnabled = BuildConfig.EXPORT_ENABLED && !navAssistV2Config.isConfigured()
        exporter = WebSocketNavigationDataExporter(
            config = ExportConfig(
                // Discovery or a valid explicit v2 endpoint owns the phone-to-C3 link.
                enabled = legacyExportEnabled,
                webSocketUrl = BuildConfig.WEBSOCKET_URL,
                apiToken = BuildConfig.API_TOKEN,
                intervalMs = BuildConfig.EXPORT_INTERVAL_MS,
            ),
            stateProvider = { selectedNavigationState() },
            onCommaState = commaStateStore::set,
        )
        navAssistV2Exporter = HttpNavAssistV2Exporter(
            modelRouteProvider = {
                val route = modelRoute
                val state = stateStore.state.value
                route?.takeIf { mutableNavigationDataSource.value == NavigationDataSource.AMAP_API &&
                    state.acceptedPathId == it.pathId && state.routeRevision == it.routeRevision }
            },
            config = navAssistV2Config,
            navigationSession = navAssistSession,
            stateProvider = {
                val selected = selectedNavigationState()
                val prepared = sourceGate.prepare(selected, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())
                val sourceStatus = sourceGate.reason
                if (mutableNavigationDataSource.value == NavigationDataSource.AMAP_AUTO) {
                    if (selected.navAssistSourceStatus != sourceStatus ||
                        selected.navAssistControlAllowed != prepared.navAssistControlAllowed) {
                        mutableAmapAutoNavigationState.update { current ->
                            current?.copy(
                                navAssistSourceStatus = sourceStatus,
                                navAssistControlAllowed = prepared.navAssistControlAllowed,
                            )
                        }
                    }
                } else if (stateStore.state.value.navAssistSourceStatus != sourceStatus ||
                    stateStore.state.value.navAssistControlAllowed != prepared.navAssistControlAllowed) {
                    stateStore.update { copy(navAssistSourceStatus = sourceStatus, navAssistControlAllowed = prepared.navAssistControlAllowed) }
                }
                prepared
            },
            identity = navAssistIdentity,
            endpointDiscovery = UdpNavAssistV2EndpointDiscovery(navAssistIdentity, navAssistPairingStore),
            pinnedDeviceProvider = navAssistPairingStore::pinnedDevice,
            useUnauthenticatedUdp = true,
            udpClient = com.garan.tesnav.export.JvmUdpNavAssistV3Client(
                if (BuildConfig.DEBUG) { line -> com.garan.tesnav.util.NavigationTrace.append(filesDir, line, "NavAssist-UDP") } else null),
            requireOwnerHandshake = true,
            monotonicMs = { android.os.SystemClock.elapsedRealtime() },
        )
        observeExporterInstances()
        exporter.start()
        navAssistV2Exporter.start()
    }

    private fun selectedNavigationState(): NavigationState = NavigationSourcePolicy.stateFor(
        mutableNavigationDataSource.value, stateStore.state.value, mutableAmapAutoNavigationState.value,
    )

    private fun refreshAmapAutoLocation() {
        if (mutableNavigationDataSource.value != NavigationDataSource.AMAP_AUTO ||
            mutableAmapAutoNavigationState.value == null) return
        val location = amapAutoLocationFallback()
        val wall = System.currentTimeMillis()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        mutableAmapAutoNavigationState.update { current ->
            AmapAutoBroadcast.refreshLocation(current, location, wall, elapsed, BuildConfig.NAV_ASSIST_SOURCE_BUDGET_MS)
        }
    }

    private fun amapAutoLocationFallback(): NavigationState? {
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val phone = locationBridge?.latestPlanningLocation()
        val vehicle = amapAutoVehicleLocation.read(mutableOemVehicleLaneState.value, elapsed, wall)
        val selection = amapAutoLocationSelector.select(null, vehicle, true, elapsed, wall)
        val fix = when (selection.source) {
            com.garan.tesnav.model.NavigationLocationSource.VEHICLE -> vehicle
            // Retain Auto's existing low-speed phone fallback: heading may be absent.
            // The adapter still checks coordinate/accuracy and both original clocks.
            else -> phone
        } ?: return stateStore.state.value
        return NavigationState(
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracy = fix.accuracyM?.toFloat(),
            bearing = fix.bearingDeg?.toFloat(),
            speedKph = fix.speedMps?.times(3.6)?.toFloat() ?: 0f,
            locationTime = fix.measuredAtMs,
            locationObservedAtMs = fix.measuredAtMs,
            locationReceivedElapsedMs = fix.receivedElapsedMs,
            locationSourceStatus = fix.sourceEpoch,
        )
    }

    private fun observeExporterInstances() {
        val observedLegacyExporter = exporter
        val observedNavAssistExporter = navAssistV2Exporter
        mutableCommaConnectionState.value = observedLegacyExporter.connectionState.value
        mutableCommaLastError.value = observedLegacyExporter.lastError.value
        mutableNavAssistV2Status.value = observedNavAssistExporter.status.value
        mutableNavAssistDeliveryState.value = observedNavAssistExporter.deliveryState.value
        mutableNavAssistV2ResolvedEndpoint.value = observedNavAssistExporter.resolvedEndpoint.value
        mutableNavAssistV2LastError.value = observedNavAssistExporter.lastError.value
        mutableOemVehicleLaneState.value = observedNavAssistExporter.oemVehicleLaneState.value
        exporterObservationJobs += scope.launch {
            observedLegacyExporter.connectionState.collect { mutableCommaConnectionState.value = it }
        }
        exporterObservationJobs += scope.launch {
            observedLegacyExporter.lastError.collect { mutableCommaLastError.value = it }
        }
        exporterObservationJobs += scope.launch {
            observedNavAssistExporter.deliveryState.collect { mutableNavAssistDeliveryState.value = it }
        }
        exporterObservationJobs += scope.launch {
            observedNavAssistExporter.status.collect { mutableNavAssistV2Status.value = it }
        }
        exporterObservationJobs += scope.launch {
            observedNavAssistExporter.resolvedEndpoint.collect { mutableNavAssistV2ResolvedEndpoint.value = it }
        }
        exporterObservationJobs += scope.launch {
            observedNavAssistExporter.lastError.collect { mutableNavAssistV2LastError.value = it }
        }
        exporterObservationJobs += scope.launch {
            observedNavAssistExporter.oemVehicleLaneState.collect {
                mutableOemVehicleLaneState.value = it
                refreshAmapAutoLocation()
            }
        }
        exporterObservationJobs += scope.launch {
            observedNavAssistExporter.laneAnnouncement.collect { laneSpeech.offer(it) }
        }
    }

    private fun observeRuntime() {
        scope.launch {
            stateStore.state.collect { state ->
                handleTeslaRouteProgress(state)
                tryStartTeslaNavigation()
            }
        }
        scope.launch {
            selectedNavigationStateFlow.collect { state ->
                logNavAssistDiagnostics(state)
                updateNotification()
            }
        }
        scope.launch {
            commaConnectionState.collect { state ->
                if (state != ExportConnectionState.CONNECTED) {
                    commaStateReady = false
                    lastTeslaNavActive = null
                }
                updateNotification()
            }
        }
        scope.launch {
            commaStateStore.state.collect { state ->
                if (state.timestampMs == 0L || commaConnectionState.value != ExportConnectionState.CONNECTED) return@collect
                commaStateReady = true
                handleTeslaNavigationActive(state.isTeslaNavActive)
            }
        }
        scope.launch {
            homeAssistantClient.navigationState.collect {
                tryStartTeslaNavigation()
            }
        }
        scope.launch {
            homeAssistantClient.connectionState.collect {
                tryStartTeslaNavigation()
                updateNotification()
            }
        }
        scope.launch {
            navAssistV2Status.collect { updateNotification() }
        }
        scope.launch {
            teslaBleClient.state.collect { bleState ->
                if (mutableTeslaBleSyncEnabled.value) {
                    handleTeslaBleDestinationCancelled(bleState)
                    // A genuinely new target gets its own recovery budget.
                    if (bleState.fresh(android.os.SystemClock.elapsedRealtime()) && bleState.destination != null &&
                        failedTeslaDestination != null) {
                        if (bleState.destination != failedTeslaDestination) {
                            clearTeslaDestinationFailure()
                        }
                    }
                    handleTeslaRouteProgress(stateStore.state.value)
                    tryStartTeslaNavigation()
                }
            }
        }
    }

    private fun logNavAssistDiagnostics(state: NavigationState) {
        val distanceBucket = state.nextTurnDistanceMeters?.div(10)?.times(10)
        val key = "${state.navigationProvider}:${state.navigationMode}:${state.routePlanned}:${state.routeMatched}:" +
            "${state.maneuver}:${state.guidanceStepIndex}:$distanceBucket:${state.accuracy?.toInt()}:${state.navAssistSourceStatus}"
        if (key == lastNavAssistDiagnosticKey) return
        lastNavAssistDiagnosticKey = key
        val nowMs = System.currentTimeMillis()
        val locationAgeMs = state.locationObservedAtMs?.let { nowMs - it }
        val guidanceAgeMs = state.guidanceObservedAtMs?.let { nowMs - it }
        val line = "provider=${state.navigationProvider} mode=${state.navigationMode} planned=${state.routePlanned} matched=${state.routeMatched} " +
                "maneuver=${state.maneuver} step=${state.guidanceStepIndex} " +
                "distanceM=${state.nextTurnDistanceMeters} accuracyM=${state.accuracy} " +
                "locationAgeMs=$locationAgeMs guidanceAgeMs=$guidanceAgeMs source=${state.navAssistSourceStatus} " +
                "controlAllowed=${state.navAssistControlAllowed} sourceBudgetMs=${BuildConfig.NAV_ASSIST_SOURCE_BUDGET_MS} " +
                "progressBudgetMs=${BuildConfig.NAV_ASSIST_PROGRESS_BUDGET_MS}"
        Log.i(NAVASSIST_DIAGNOSTIC_TAG, line)
        if (BuildConfig.DEBUG) com.garan.tesnav.util.NavigationTrace.append(filesDir, line, NAVASSIST_DIAGNOSTIC_TAG)
    }

    private fun startHomeAssistant() {
        homeAssistantClient.start(BuildConfig.HOME_ASSISTANT_URL, BuildConfig.HOME_ASSISTANT_TOKEN)
    }

    private fun handleTeslaNavigationActive(active: Boolean) {
        if (mutableTeslaBleSyncEnabled.value) return
        if (!mutableTeslaSyncEnabled.value) return
        if (mutableNavigationDataSource.value != NavigationDataSource.AMAP_API) return
        if (lastTeslaNavActive == active) {
            if (active) tryStartTeslaNavigation()
            return
        }
        lastTeslaNavActive = active
        if (active) {
            failedTeslaDestination = null
            handleTeslaRouteProgress(stateStore.state.value)
            tryStartTeslaNavigation()
        } else {
            routeRequestDestination = null
            activeTeslaDestination = null
            failedTeslaDestination = null
            if (NavigationSourcePolicy.syncOwnsNavigation(mutableNavigationDataSource.value, stateStore.state.value)) {
                sourceGate.disarm()
                repository.stopNavigation()
            }
        }
    }

    private fun handleTeslaBleDestinationCancelled(state: com.garan.tesnav.teslable.TeslaBleNavigationState) {
        if (!state.fresh(android.os.SystemClock.elapsedRealtime()) || !state.destinationAbsentConfirmed ||
            state.destination != null) return
        val autoSelected = mutableNavigationDataSource.value == NavigationDataSource.AMAP_AUTO
        val ownsApi = NavigationSourcePolicy.syncOwnsNavigation(
            mutableNavigationDataSource.value, stateStore.state.value)
        clearTeslaDestinationFailure()
        if (routeRequestDestination == null && activeTeslaDestination == null && !ownsApi) return
        // Invalidate any destination still waiting for the embedded display to load.
        teslaAutoRequestId++
        routeRequestDestination = null
        failedTeslaDestination = null
        if (autoSelected && activeTeslaDestination != null) {
            val sent = com.garan.tesnav.ui.AmapAutoActions.stopNavigation(this)
            mutableTeslaAutoSyncStatus.value = if (sent) "原车已取消 · 已发送高德车机取消请求" else "高德车机取消请求发送失败"
            if (!sent) return
        } else if (ownsApi) {
            sourceGate.disarm()
            repository.stopNavigation()
        }
        activeTeslaDestination = null
    }

    private fun tryStartTeslaNavigation() {
        if (mutableNavigationDataSource.value == NavigationDataSource.AMAP_AUTO) {
            if (mutableTeslaBleSyncEnabled.value && teslaSyncReady()) trySendTeslaAutoDestination()
            return
        }
        if (mutableNavigationDataSource.value != NavigationDataSource.AMAP_API) return
        if (!teslaSyncReady()) return
        val destination = currentTeslaDestination() ?: return
        if (mutableTeslaBleSyncEnabled.value && routeRequestDestination != null) return
        if (destination == activeTeslaDestination || destination == routeRequestDestination || teslaDestinationFailureBlocks(destination)) return

        val navigationState = stateStore.state.value
        if (navigationState.latitude == null || navigationState.longitude == null) return
        val accepted = repository.planRoute(
            latitude = destination.latitude,
            longitude = destination.longitude,
            startedFromTeslaSync = true,
        )
        if (accepted) {
            // planRoute invalidates the installed path, including A -> B -> A during a pending request.
            if (mutableTeslaBleSyncEnabled.value) activeTeslaDestination = null
            routeRequestDestination = destination
        } else {
            recordTeslaDestinationFailure(destination)
        }
    }

    private fun trySendTeslaAutoDestination() {
        val destination = currentTeslaDestination() ?: return
        if (destination == activeTeslaDestination || destination == routeRequestDestination || teslaDestinationFailureBlocks(destination)) return
        val requestId = ++teslaAutoRequestId
        routeRequestDestination = destination
        mutableTeslaAutoSyncStatus.value = "正在发送目的地到高德车机"
        val queued = com.garan.tesnav.ui.AmapAutoActions.navigate(this, teslaBleClient.state.value.name,
            destination.latitude, destination.longitude,
            stillValid = {
                requestId == teslaAutoRequestId && mutableTeslaBleSyncEnabled.value &&
                    mutableNavigationDataSource.value == NavigationDataSource.AMAP_AUTO &&
                    teslaSyncReady() && currentTeslaDestination() == destination
            }, onDispatched = { sent ->
                if (requestId == teslaAutoRequestId) {
                    routeRequestDestination = null
                    if (sent) {
                        clearTeslaDestinationFailure()
                        activeTeslaDestination = destination // Dispatched target only, not a navigation ACK.
                        mutableTeslaAutoSyncStatus.value = "目的地已发送 · 以高德车机实际导航广播为准"
                    } else {
                        if (mutableNavigationDataSource.value == NavigationDataSource.AMAP_AUTO &&
                            teslaSyncReady() && currentTeslaDestination() == destination) recordTeslaDestinationFailure(destination)
                        mutableTeslaAutoSyncStatus.value = "目的地发送已取消或失败 · 等待有效原车目的地"
                    }
                }
            })
        if (!queued) {
            routeRequestDestination = null
            recordTeslaDestinationFailure(destination)
            mutableTeslaAutoSyncStatus.value = "未能打开高德车机，请检查安装和启动权限后重新开启同步"
        }
    }

    private fun handleTeslaRouteProgress(state: NavigationState) {
        if (mutableNavigationDataSource.value != NavigationDataSource.AMAP_API) return
        val requestedDestination = routeRequestDestination ?: return
        if (!teslaSyncReady()) return

        if (state.routePlanned && state.startedFromTeslaSync) {
            val latestDestination = currentTeslaDestination()
            routeRequestDestination = null
            if (latestDestination != requestedDestination) {
                tryStartTeslaNavigation()
            } else if (repository.startRealtime()) {
                sourceGate.arm(android.os.SystemClock.elapsedRealtime())
                activeTeslaDestination = requestedDestination
                clearTeslaDestinationFailure()
            } else {
                recordTeslaDestinationFailure(requestedDestination)
            }
        } else if (state.errorMessage?.startsWith("路线规划失败") == true ||
            state.errorMessage?.startsWith("路线规划请求失败") == true
        ) {
            routeRequestDestination = null
            recordTeslaDestinationFailure(requestedDestination)
        }
    }

    private fun teslaDestinationFailureBlocks(destination: TeslaNavigationDestination): Boolean =
        destination == failedTeslaDestination && !teslaDestinationRetryAllowed(
            mutableTeslaBleSyncEnabled.value, failedTeslaDestinationAttempts,
            failedTeslaDestinationRetryAt, android.os.SystemClock.elapsedRealtime())

    private fun recordTeslaDestinationFailure(destination: TeslaNavigationDestination) {
        if (failedTeslaDestination != destination) clearTeslaDestinationFailure()
        failedTeslaDestination = destination
        failedTeslaDestinationAttempts++
        failedTeslaDestinationRetryAt = android.os.SystemClock.elapsedRealtime() +
            com.garan.tesnav.teslable.TeslaBleRecovery.reconnectDelayMs(failedTeslaDestinationAttempts)
    }

    private fun clearTeslaDestinationFailure() {
        failedTeslaDestination = null
        failedTeslaDestinationAttempts = 0
        failedTeslaDestinationRetryAt = 0L
    }

    private fun resetTeslaSyncTracking() {
        teslaAutoRequestId++
        mutableTeslaAutoSyncStatus.value = ""
        commaStateReady = false
        lastTeslaNavActive = null
        routeRequestDestination = null
        activeTeslaDestination = null
        clearTeslaDestinationFailure()
    }

    private fun preferences() = getSharedPreferences(HA_PREFS, MODE_PRIVATE)

    private fun teslaSyncReady(): Boolean {
        if (!mutableTeslaSyncEnabled.value) return false
        if (mutableTeslaBleSyncEnabled.value) return teslaBleClient.state.value.fresh(android.os.SystemClock.elapsedRealtime()) &&
            teslaBleClient.state.value.destination != null
        return commaStateReady && commaConnectionState.value == ExportConnectionState.CONNECTED && lastTeslaNavActive == true &&
            homeAssistantClient.connectionState.value == HomeAssistantConnectionState.CONNECTED &&
            homeAssistantClient.navigationState.value.navigationActive == true
    }

    private fun currentTeslaDestination(): TeslaNavigationDestination? =
        if (mutableTeslaBleSyncEnabled.value) teslaBleClient.state.value.destination
        else homeAssistantClient.navigationState.value.destination

    private fun updateNotification() {
        if (!::stateStore.isInitialized || !::exporter.isInitialized) return
        val content = "${navigationDataSource.value.displayName} ${selectedNavigationState().navigationMode.name} · Comma ${commaConnectionState.value.name} · " +
            "C3XL ${navAssistV2Status.value.name}"
        if (content == lastNotificationContent) return
        lastNotificationContent = content
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIFICATION_ID,
            buildNotification(content),
        )
    }

    private fun promoteToForeground(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                if (mutableTeslaBleSyncEnabled.value) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(content: String): Notification {
        val appName = applicationInfo.loadLabel(packageManager)
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags(),
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NavigationForegroundService::class.java).setAction(ACTION_STOP_SERVICE),
            pendingFlags(),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("$appName 后台导航")
            .setContentText(content)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止后台服务", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val appName = applicationInfo.loadLabel(packageManager)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "$appName 后台导航",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持导航回调和 WebSocket 在后台运行"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TesNav:NavigationRuntime").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        private const val AMAP_AUTO_NAVIGATION_TYPE = 10001
        private const val AMAP_AUTO_LANE_TYPE = 13012
        private const val AMAP_AUTO_TRAFFIC_LIGHT_TYPE = 60073
        private const val AMAP_AUTO_TRACE_TAG = "TesNav-AmapAuto"
        private const val NAVIGATION_SOURCE_KEY = "navigation_data_source"
        private const val NAVASSIST_DIAGNOSTIC_TAG = "TesNavNavState"
        private const val ACTION_STOP_SERVICE = "com.garan.tesnav.action.STOP_RUNTIME"
        private const val CHANNEL_ID = "tesnav_navigation_runtime"
        private const val NOTIFICATION_ID = 1001
        const val HA_PREFS = "home_assistant_sync"
        const val HA_SYNC_ENABLED = "enabled"
        private const val TESLA_BLE_SYNC_ENABLED = "tesla_ble_sync_enabled"
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NavigationForegroundService::class.java),
            )
        }
    }
}

/** The caller also requires a fresh, currently present vehicle destination. */
internal fun teslaDestinationRetryAllowed(bleEnabled: Boolean, failures: Int, retryAt: Long, now: Long): Boolean =
    bleEnabled && failures in 1..3 && now >= retryAt
