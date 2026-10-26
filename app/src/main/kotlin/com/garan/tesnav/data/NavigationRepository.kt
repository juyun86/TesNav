package com.garan.tesnav.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.amap.api.navi.AMapNaviIndependentRouteListener
import com.amap.api.navi.model.AMapNaviPathGroup
import com.amap.api.navi.enums.TransportType
import android.util.Log
import com.garan.tesnav.config.SpeechMode
import com.garan.tesnav.config.SpeechPreferences
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.ParallelRoadListener
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.AMapNaviParallelRoadStatus
import com.amap.api.navi.enums.AMapNaviRouteNotifyDataType
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.AMapNaviPath
import com.amap.api.navi.model.AMapNaviRouteNotifyData
import com.garan.tesnav.model.CameraState
import com.garan.tesnav.model.GeoPoint
import com.garan.tesnav.normalization.AmapLaneNormalizer
import com.garan.tesnav.normalization.LegacyLaneObservation
import com.garan.tesnav.model.NavigationManeuver
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.RoadLayerStatus
import com.garan.tesnav.model.RouteChoice
import com.garan.tesnav.model.RouteNoticeState
import com.garan.tesnav.model.RouteNoticeType
import com.garan.tesnav.model.TrafficStatus
import com.garan.tesnav.model.WarningLevel
import com.garan.tesnav.util.NavigationMappers
import com.garan.tesnav.util.OverspeedEvaluator

/** Owns the AMap navigation engine so callbacks continue while the Activity is in background. */
class NavigationRepository(
    context: Context,
    private val stateStore: NavigationStateStore,
    private val overspeedEvaluator: OverspeedEvaluator = OverspeedEvaluator(),
    private val onRouteChanged: (AMapNaviPath?) -> Unit = {},
) : SimpleNaviListener(), ParallelRoadListener {
    private val appContext = context.applicationContext
    private val speechPreferences = SpeechPreferences(appContext)
    val speechMode: SpeechMode get() = speechPreferences.mode
    private var navi: AMapNavi? = null
    @Volatile private var engineGeneration = 0L
    private var engineCallbackListener: SimpleNaviListener? = null
    private var engineParallelRoadListener: ParallelRoadListener? = null
    private var routeCalculationPending = false
    private var routeRequests = RouteRequestAssociation()
    private var engineState: EngineState? = null
    private var calculationWakeup: (() -> Unit)? = null
    private val callbackHandler = Handler(Looper.getMainLooper())
    private var routeTimeout: Runnable? = null
    private var queuedRouteCalculation: (() -> Boolean)? = null
    private var plannedPaths: AMapNaviPathGroup? = null
    private var confirmedDestination: GeoPoint? = null
    private var reroutingFromPathId: Long? = null
    // Keep the installed group across stopNavi; successful replacement supersedes it.
    // Never call group.destroy(): SDK 11.2.100 finalize destroys the same pointer again.
    private var installedPathGroups = mutableListOf<AMapNaviPathGroup>()
    private var requestedNavigationMode: NavigationMode? = null
    private var publishedRouteSignature: String? = null
    private var routeRevision = 0L
    private var nextManeuverIconType: Int? = null
    private var lastGuidanceStepIndex: Int? = null
    private var lastGuidanceObservationKey: String? = null
    // Diagnostic request counter only; it does not prove callback ownership.
    private var diagnosticRequestSerial = 0L
    private val routeSelection = RouteSelectionCoordinator { routeId -> plannedPaths?.selectRouteWithIndex(routeId) == true }
    private val externalLocation = com.garan.tesnav.model.NavigationExternalLocationController(
        object : com.garan.tesnav.model.NavigationExternalLocationSink {
            override fun setExternalEnabled(enabled: Boolean) {
                checkNotNull(navi).setIsUseExtraGPSData(enabled)
            }
            override fun inject(fix: com.garan.tesnav.model.NavigationLocationObservation) {
                val type = when (fix.coordinateSystem) {
                    com.garan.tesnav.model.NavigationCoordinateSystem.WGS84 -> 1
                    com.garan.tesnav.model.NavigationCoordinateSystem.GCJ02 -> 2
                    else -> error("Unknown external coordinate system")
                }
                val location = android.location.Location("tesnav-vehicle").apply {
                    latitude = fix.latitude
                    longitude = fix.longitude
                    accuracy = checkNotNull(fix.accuracyM).toFloat()
                    speed = checkNotNull(fix.speedMps).toFloat()
                    bearing = checkNotNull(fix.bearingDeg).toFloat()
                    time = fix.measuredAtMs // Retain the source's explicit measurement/observation time basis.
                }
                checkNotNull(navi).setExtraGPSData(type, location)
            }
        })

    internal val usingVehicleLocation: Boolean get() = externalLocation.isExternalEnabled
    internal var phoneGpsSignalWeak: Boolean = false
        private set
    private var lastLocationSource: com.garan.tesnav.model.NavigationLocationSource? = null
    internal var phoneLocationObservation: com.garan.tesnav.model.NavigationLocationObservation? = null
        private set
    internal val needsIndependentPlanningLocation: Boolean get() = engineState?.hasSimulatedLocation == true
    private var independentPlanningLocation: com.garan.tesnav.model.NavigationLocationObservation? = null

    internal fun updatePlanningLocation(fix: com.garan.tesnav.model.NavigationLocationObservation?) {
        independentPlanningLocation = fix
        restorePhysicalLocationAfterSimulation()
    }

    private fun physicalPlanningOrigin(): GeoPoint? = com.garan.tesnav.model.independentPlanningOrigin(
        independentPlanningLocation, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())

    private fun restorePhysicalLocationAfterSimulation() {
        if (!needsIndependentPlanningLocation || stateStore.state.value.navigationMode in
            listOf(NavigationMode.SIMULATION, NavigationMode.REALTIME)) return
        val point = physicalPlanningOrigin()
        val fix = independentPlanningLocation.takeIf { point != null }
        update { copy(latitude = point?.latitude, longitude = point?.longitude,
            accuracy = fix?.accuracyM?.toFloat(), bearing = fix?.bearingDeg?.toFloat(),
            speedKph = fix?.speedMps?.times(3.6)?.toFloat() ?: 0f,
            locationTime = fix?.measuredAtMs, locationObservedAtMs = fix?.measuredAtMs,
            locationReceivedElapsedMs = fix?.receivedElapsedMs, routeMatched = false) }
    }

    /** Source observations retain their explicit measurement/observation time basis. */
    internal fun updateExternalLocation(
        phone: com.garan.tesnav.model.NavigationLocationObservation?,
        vehicle: com.garan.tesnav.model.NavigationLocationObservation?,
    ): com.garan.tesnav.model.NavigationLocationSelection {
        check(Looper.myLooper() == Looper.getMainLooper())
        val state = stateStore.state.value
        val result = externalLocation.update(phone, vehicle,
            state.navigationMode == NavigationMode.REALTIME && state.routePlanned && !state.routeRecalculating,
            android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis(), phoneGpsSignalWeak)
        val changed = result.source != lastLocationSource
        lastLocationSource = result.source
        val weak = result.source == com.garan.tesnav.model.NavigationLocationSource.NONE ||
            com.garan.tesnav.model.selectedLocationWeak(externalLocation.isExternalEnabled,
                externalLocation.injectedFix != null, phoneGpsSignalWeak)
        if (changed || stateStore.state.value.gpsSignalWeak != weak) update {
            copy(gpsSignalWeak = weak,
                locationSourceStatus = result.source.name.lowercase(),
                // New source must provide a new SDK position/match; old phone output is not authority.
                routeMatched = if (changed) false else routeMatched,
                locationReceivedElapsedMs = if (changed) null else locationReceivedElapsedMs,
                errorMessage = if (weak) "定位暂不可用" else errorMessage?.takeUnless { it == "GPS 信号弱" || it == "定位暂不可用" })
        }
        return result
    }

    fun initialize(): Result<Unit> = runCatching {
        if (navi != null) return@runCatching
        val generation = ++engineGeneration
        navi = AMapNavi.getInstance(appContext).also {
            it.setMultipleRouteNaviMode(true)
            it.setIsOpenTrafficLight("TLTRUE")
            // Map location layers can keep the singleton alive after destroy(). Keep its
            // physical request occupancy and installed paths across Repository rebindings.
            val shared = sharedEngineState?.takeIf { shared -> shared.engine === it }
                ?: EngineState(it).also { sharedEngineState = it }
            engineState = shared
            routeRequests = shared.requests
            installedPathGroups = shared.installedPaths
            calculationWakeup = {
                callbackHandler.post {
                    if (generation == engineGeneration) runQueuedRouteCalculation()
                }
                Unit
            }
            shared.wakeup = calculationWakeup
            engineCallbackListener = listenerForEngine(generation)
            it.addAMapNaviListener(engineCallbackListener)
            engineParallelRoadListener = object : ParallelRoadListener {
                override fun notifyParallelRoad(status: AMapNaviParallelRoadStatus?) {
                    if (generation == engineGeneration) this@NavigationRepository.notifyParallelRoad(status)
                }
            }
            it.addParallelRoadListener(engineParallelRoadListener)
            it.setUseInnerVoice(true)
            applySpeech(it, speechMode)
            it.setTrafficStatusUpdateEnabled(true)
            it.setTrafficInfoUpdateEnabled(true)
            it.setCameraInfoUpdateEnabled(true)
            it.startGPS()
        }
    }.onFailure { error -> update { copy(errorMessage = "高德导航初始化失败：${error.message}") } }

    fun currentPath(): AMapNaviPath? =
        if (stateStore.state.value.navigationMode == NavigationMode.ROUTE_PLANNED)
            plannedPaths?.mainPath ?: navi?.naviPath?.takeIf { it.pathid == stateStore.state.value.acceptedPathId }
        else navi?.naviPath

    private fun plannedRoutePaths(): Map<Int, AMapNaviPath> = plannedPaths?.let { group ->
        // SDK 11.2.100 selectRouteWithIndex takes route IDs starting at 12 (not array indices).
        (0 until group.pathCount).associate { index -> (index + 12) to group.getPath(index) }
    }.orEmpty()

    private fun clearRouteTimeout() {
        routeTimeout?.let(callbackHandler::removeCallbacks)
        routeTimeout = null
    }

    private fun clearPlannedPaths() {
        // Let the SDK wrapper finalize unused groups; never explicitly destroy a group still
        // referenced by the navigation engine or map rendering threads.
        plannedPaths = null
    }

    private fun runQueuedRouteCalculation() {
        val calculate = queuedRouteCalculation ?: return
        if (calculate()) queuedRouteCalculation = null
    }

    private fun completeSdkCalculation(shared: EngineState, token: Long) {
        if (!shared.requests.completeCall(token)) return
        // A detached Repository may still receive the physical call's terminal callback.
        // Wake the current owner; accepting the result still requires its original generation.
        shared.wakeup?.invoke()
    }

    fun selectRoute(routeId: Int): Boolean {
        val current = stateStore.state.value
        if (current.navigationMode !in listOf(NavigationMode.ROUTE_PLANNED, NavigationMode.REALTIME, NavigationMode.SIMULATION) ||
            !current.routePlanned) return false
        val path = plannedRoutePaths()[routeId] ?: return false
        return when (routeSelection.select(routeId, current.routeChoices, current.selectedRouteId)) {
            RouteSelectionOutcome.REJECTED -> false
            RouteSelectionOutcome.ALREADY_SELECTED -> true
            RouteSelectionOutcome.SELECTED -> {
                if (current.navigationMode != NavigationMode.ROUTE_PLANNED) {
                    val engine = navi ?: return false
                    if (runCatching {
                            engine.selectMainPathID(path.pathid)
                            engine.refreshNaviInfo()
                        }.isFailure) return false
                }
                traceSdk("route_selected") { "routeId=$routeId selectedPathId=${path.pathid}" }
                val choices = current.routeChoices.map { it.copy(selected = it.routeId == routeId) }
                val observedAtMs = System.currentTimeMillis()
                val revision = ++routeRevision
                resetGuidanceCallbackState()
                update {
                    copy(
                        routeRemainDistanceMeters = if (current.navigationMode == NavigationMode.ROUTE_PLANNED) path.allLength else routeRemainDistanceMeters,
                        routeRemainTimeSeconds = if (current.navigationMode == NavigationMode.ROUTE_PLANNED) path.allTime else routeRemainTimeSeconds,
                        remainingTrafficLightCount = if (current.navigationMode == NavigationMode.ROUTE_PLANNED) path.trafficLightCount else remainingTrafficLightCount,
                        routeTrafficLights = path.lightList.orEmpty().map { GeoPoint(it.latitude, it.longitude) },
                        routeChoices = choices,
                        selectedRouteId = routeId,
                        acceptedPathId = path.pathid,
                        guidancePathId = null,
                        lanesPathId = null,
                        guidanceReceivedElapsedMs = null,
                        guidanceCallbackElapsedMs = null,
                        unkeyedRouteFactsConfirmed = false,
                        routeRevision = revision,
                        routeObservedAtMs = observedAtMs,
                        guidanceObservedAtMs = null,
                        lanesObservedAtMs = null,
                        lanes = emptyList(),
                        currentRoad = null,
                        nextRoad = null,
                        nextTurnType = null,
                        nextTurnDistanceMeters = null,
                        currentStepIndex = null,
                        currentLinkIndex = null,
                        currentPointIndex = null,
                        routeMatched = null,
                        maneuver = NavigationManeuver.UNKNOWN,
                        nextManeuver = NavigationManeuver.UNKNOWN,
                        nextManeuverDistanceMeters = null,
                        guidanceStepIndex = null,
                        currentRoadClass = null,
                        currentRoadType = null,
                        parallelRoadStatus = RoadLayerStatus.UNKNOWN,
                        elevatedRoadStatus = RoadLayerStatus.UNKNOWN,
                        routeNotice = null,
                        laneCallbackCount = 0,
                        laneLastEvent = null,
                    )
                }
                publishRouteIfChanged(path)
                true
            }
        }
    }

    fun planRoute(
        latitude: Double,
        longitude: Double,
        startedFromTeslaSync: Boolean = false,
    ): Boolean {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        val engine = navi ?: return false
        val callState = engineState ?: return false
        if (routeRequests.recoveryRequired) {
            update { copy(errorMessage = ENGINE_RESTART_MESSAGE) }
            return false
        }
        val current = stateStore.state.value
        val physicalOrigin = if (needsIndependentPlanningLocation) physicalPlanningOrigin() else null
        val startLat = if (needsIndependentPlanningLocation) physicalOrigin?.latitude else current.latitude
        val startLng = if (needsIndependentPlanningLocation) physicalOrigin?.longitude else current.longitude
        if (startLat == null || startLng == null) {
            update { copy(errorMessage = if (needsIndependentPlanningLocation) "等待真实定位恢复，请稍后再规划" else "尚未获得当前位置，请稍后再规划") }
            return false
        }

        val requestToken = routeRequests.begin() ?: run {
            update { copy(errorMessage = "正在规划路线，请等待当前请求完成") }
            return false
        }
        reroutingFromPathId = null
        confirmedDestination = null
        diagnosticRequestSerial += 1
        traceSdk("plan_requested") { "originSource=${if (needsIndependentPlanningLocation) "phone-gps" else "sdk"} startLat=$startLat startLng=$startLng originObservedAtMs=${if (needsIndependentPlanningLocation) independentPlanningLocation?.measuredAtMs else current.locationObservedAtMs}" }
        engine.stopNavi()
        clearRouteTimeout()
        clearPlannedPaths()
        routeCalculationPending = true
        requestedNavigationMode = null
        val routeInvalidatedAtMs = System.currentTimeMillis()
        val revision = if (current.routePlanned) ++routeRevision else routeRevision
        update {
            clearRouteValues().copy(
                navigationMode = NavigationMode.IDLE,
                startedFromTeslaSync = startedFromTeslaSync,
                errorMessage = null,
                routeRevision = revision,
                routeObservedAtMs = if (current.routePlanned) routeInvalidatedAtMs else routeObservedAtMs,
            )
        }
        var accepted = true
        queuedRouteCalculation = calculate@{
            if (!routeRequests.isPending(requestToken)) return@calculate true
            if (!routeRequests.startCall(requestToken)) return@calculate false
            accepted = runCatching {
                applySpeech(engine, speechMode)
                val generation = engineGeneration
                engine.independentCalculateRoute(
                    com.amap.api.navi.model.NaviPoi("", com.amap.api.maps.model.LatLng(startLat, startLng), ""),
                    com.amap.api.navi.model.NaviPoi("", com.amap.api.maps.model.LatLng(latitude, longitude), ""),
                    emptyList(),
                    PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT,
                    TransportType.Drive,
                    object : AMapNaviIndependentRouteListener {
                        override fun onIndependentCalculateInvoke(requestId: Int) {
                            callbackHandler.post {
                                if (requestId <= 0) completeSdkCalculation(callState, requestToken)
                                if (generation != engineGeneration) return@post
                                if (requestId <= 0) {
                                    if (routeRequests.failInvocation(requestToken)) {
                                        routeFailed("路线规划请求失败：SDK 未建立有效请求")
                                    }
                                } else {
                                    routeRequests.bind(requestToken, requestId)
                                }
                                traceSdk("request_bound") { "token=$requestToken sdkRequestId=$requestId" }
                            }
                        }

                        override fun onIndependentCalculateSuccess(group: AMapNaviPathGroup?) {
                            callbackHandler.post {
                                traceSdk("independent_success") { "token=$requestToken sdkRequestId=${group?.routeRequestId}" }
                                completeSdkCalculation(callState, requestToken)
                                if (generation != engineGeneration) return@post
                                if (!routeRequests.finishCall(requestToken)) {
                                    return@post
                                }
                                clearRouteTimeout()
                                if (group == null || group.pathCount <= 0 || group.mainPath == null || group.mainPath.pathid <= 0) {
                                    routeFailed("路线规划失败：SDK 返回空路线")
                                    return@post
                                }
                                plannedPaths = group
                                routeSucceeded()
                                traceSdk("route_accepted") { "token=$requestToken sdkRequestId=${group.routeRequestId}" }
                            }
                        }

                        override fun onIndependentCalculateFail(result: AMapCalcRouteResult?) {
                            callbackHandler.post {
                                traceSdk("independent_failure") { "token=$requestToken sdkRequestId=${result?.routeRequestId} error=${result?.errorCode}" }
                                completeSdkCalculation(callState, requestToken)
                                if (generation != engineGeneration) return@post
                                if (routeRequests.finishCall(requestToken)) {
                                    routeFailed("路线规划失败：${result?.errorCode} ${result?.errorDescription.orEmpty()}")
                                }
                            }
                        }
                    },
                )
            }.getOrElse { error ->
                update { copy(errorMessage = "路线规划请求失败：${error.message}") }
                false
            }
            if (!accepted) {
                completeSdkCalculation(callState, requestToken)
                if (routeRequests.failInvocation(requestToken)) routeFailed("路线规划请求未被 SDK 接受，请重试")
            }
            true
        }
        // Includes waiting for a cancelled SDK request; never a source-health budget.
        routeTimeout = Runnable {
            if (routeRequests.timeout(requestToken)) {
                traceSdk("route_timeout") { "token=$requestToken" }
                routeFailed(if (routeRequests.recoveryRequired) ENGINE_RESTART_MESSAGE else "路线规划超时，请重试")
            }
        }.also { callbackHandler.postDelayed(it, 30_000L) }
        runQueuedRouteCalculation()
        traceSdk("plan_returned") { "accepted=$accepted" }
        return accepted
    }

    fun startRealtime(): Boolean = startNavigation(NaviType.GPS, NavigationMode.REALTIME)

    fun startSimulation(): Boolean {
        navi?.setEmulatorNaviSpeed(DEFAULT_EMULATOR_SPEED_KPH)
        return startNavigation(NaviType.EMULATOR, NavigationMode.SIMULATION)
    }

    fun pauseSimulation(): Boolean = controlSimulation(paused = true)

    fun resumeSimulation(): Boolean = controlSimulation(paused = false)

    fun setSpeechEnabled(enabled: Boolean): Boolean {
        return setSpeechMode(if (enabled) speechPreferences.resumedMode else SpeechMode.MUTED)
    }

    private fun applySpeech(engine: AMapNavi, mode: SpeechMode): Boolean = runCatching {
        if (mode == SpeechMode.MUTED) engine.stopSpeak() else {
            if (!engine.setBroadcastMode(mode.value)) return false
            engine.startSpeak()
        }
        true
    }.getOrDefault(false)

    fun setSpeechMode(mode: SpeechMode): Boolean {
        val engine = navi ?: return false
        return runCatching {
            check(applySpeech(engine, mode)) { "高德未接受播报模式" }
            speechPreferences.save(mode)
            update { copy(speechEnabled = mode != SpeechMode.MUTED, errorMessage = null) }
            true
        }.getOrElse { error ->
            update { copy(errorMessage = "语音设置失败：${error.message}") }
            false
        }
    }

    private fun controlSimulation(paused: Boolean): Boolean {
        val engine = navi ?: return false
        if (stateStore.state.value.navigationMode != NavigationMode.SIMULATION) return false
        return runCatching {
            if (paused) engine.pauseNavi() else engine.resumeNavi()
            update { copy(simulationPaused = paused, errorMessage = null) }
            true
        }.getOrElse { error ->
            update { copy(errorMessage = if (paused) "模拟导航暂停失败：${error.message}" else "模拟导航继续失败：${error.message}") }
            false
        }
    }

    private fun startNavigation(type: Int, mode: NavigationMode): Boolean {
        if (!stateStore.state.value.routePlanned) {
            update { copy(errorMessage = "请先规划路线") }
            return false
        }
        requestedNavigationMode = mode
        if (mode == NavigationMode.SIMULATION) {
            // Persist with the shared engine: stop/rebind cannot identify late simulator callbacks.
            engineState?.hasSimulatedLocation = true
            phoneLocationObservation = null
        }
        val started = plannedPaths?.let { group ->
            // Retain before handing native code the pointer, including a failed start attempt.
            if (installedPathGroups.none { it === group }) installedPathGroups.add(group)
            (navi?.startNaviWithPath(type, group) == true).also { installed ->
                // SDK replaces the native route and Java path map on successful installation.
                // Superseded wrappers may finalize instead of retaining every trip in this service.
                if (installed) installedPathGroups.removeAll { it !== group }
            }
        } ?: (navi?.naviPath?.pathid?.let { it == stateStore.state.value.acceptedPathId } == true &&
            navi?.startNavi(type) == true)
        if (started) {
            update { copy(navigationMode = mode, simulationPaused = false, errorMessage = null) }
            setSpeechMode(speechMode)
        } else {
            requestedNavigationMode = null
            update { copy(errorMessage = "导航启动请求未被 SDK 接受") }
        }
        return started
    }

    fun stopNavigation() {
        runCatching { externalLocation.reset() }.onFailure { Log.w("TesNav", "External GPS cleanup failed", it) }
        reroutingFromPathId = null
        confirmedDestination = null
        traceSdk("stop_navigation")
        routeRequests.invalidate()
        queuedRouteCalculation = null
        clearRouteTimeout()
        routeCalculationPending = false
        requestedNavigationMode = null
        navi?.stopNavi()
        clearPlannedPaths()
        val observedAtMs = System.currentTimeMillis()
        val revision = ++routeRevision
        update {
            clearRouteValues().copy(
                navigationMode = NavigationMode.IDLE,
                errorMessage = null,
                routeRevision = revision,
                routeObservedAtMs = observedAtMs,
            )
        }
        clearPublishedRoute()
        restorePhysicalLocationAfterSimulation()
    }

    fun release(): Boolean {
        runCatching { externalLocation.reset() }.onFailure { Log.w("TesNav", "External GPS cleanup failed", it) }
        reroutingFromPathId = null
        confirmedDestination = null
        traceSdk("release")
        engineGeneration += 1
        routeRequests.invalidate()
        val shared = engineState
        if (shared?.wakeup === calculationWakeup) shared?.wakeup = null
        calculationWakeup = null
        queuedRouteCalculation = null
        clearRouteTimeout()
        routeCalculationPending = false
        update { clearRouteValues() }
        navi?.stopNavi()
        clearPlannedPaths()
        navi?.stopGPS()
        engineCallbackListener?.let { navi?.removeAMapNaviListener(it) }
        engineCallbackListener = null
        engineParallelRoadListener?.let { navi?.removeParallelRoadListener(it) }
        engineParallelRoadListener = null
        navi = null
        AMapNavi.destroy()
        val destroyed = AMapNavi.isDestroyed()
        if (routeRequests.resetEngine(destroyed)) {
            installedPathGroups.clear()
            if (sharedEngineState === shared) sharedEngineState = null
        }
        engineState = null
        return destroyed
    }

    override fun onInitNaviFailure() = update {
        copy(errorMessage = "高德导航引擎初始化失败", acceptedPathId = null, guidancePathId = null, routeMatched = null)
    }
    override fun onInitNaviSuccess() = update { copy(errorMessage = null) }

    override fun onStartNavi(type: Int) {
        val expected = requestedNavigationMode ?: return
        requestedNavigationMode = null
        val mode = when (type) {
            NaviType.EMULATOR -> NavigationMode.SIMULATION
            NaviType.GPS -> NavigationMode.REALTIME
            else -> expected
        }
        update { copy(navigationMode = mode, simulationPaused = false, errorMessage = null) }
        setSpeechMode(speechMode)
    }

    override fun onLocationChange(location: AMapNaviLocation?) {
        if (location == null) return
        val mode = stateStore.state.value.navigationMode
        if (needsIndependentPlanningLocation && mode != NavigationMode.SIMULATION && mode != NavigationMode.REALTIME) {
            traceSdk("location_ignored_after_simulation") { "sourceMs=${location.time}" }
            return
        }
        // SDK failure with external mode still enabled must not be reclassified as phone GPS.
        if (externalLocation.isExternalEnabled && externalLocation.injectedFix == null) return
        traceSdk("location") { "sourceMs=${location.time} matched=${location.isMatchNaviPath} step=${location.curStepIndex} speed=${location.speed} locationType=${location.locationType} accuracyM=${location.accuracy} gpsWeak=${stateStore.state.value.gpsSignalWeak}" }
        // External SDK output cannot renew an old injected measurement or masquerade
        // as an independently recovered phone source.
        val observedAtMs = externalLocation.injectedFix?.measuredAtMs ?: location.time ?: return
        val previousSourceTime = stateStore.state.value.locationObservedAtMs
        if (!com.garan.tesnav.model.isNewNavigationSample(observedAtMs, previousSourceTime, System.currentTimeMillis())) return
        val receivedElapsedMs = android.os.SystemClock.elapsedRealtime()
        val speed = location.speed.coerceAtLeast(0f)
        if (mode != NavigationMode.SIMULATION && requestedNavigationMode != NavigationMode.SIMULATION &&
            !externalLocation.isExternalEnabled && location.coord != null) {
            phoneLocationObservation = com.garan.tesnav.model.NavigationLocationObservation(
                "phone-sdk", observedAtMs, receivedElapsedMs,
                (System.currentTimeMillis() - observedAtMs).coerceAtLeast(0),
                location.coord.latitude, location.coord.longitude, location.accuracy.toDouble(),
                speed.toDouble() / 3.6, location.bearing.toDouble(),
                com.garan.tesnav.model.NavigationCoordinateSystem.GCJ02, true)
        }
        val overspeed = overspeedEvaluator.evaluate(speed, stateStore.state.value.cameras)
        update {
            copy(
                latitude = location.coord?.latitude,
                longitude = location.coord?.longitude,
                accuracy = location.accuracy,
                bearing = location.bearing,
                locationTime = location.time,
                speedKph = speed,
                speedLimitKph = overspeed.speedLimitKph,
                isOverspeed = overspeed.isOverspeed,
                hasSpeedCameraAhead = overspeed.hasSpeedCameraAhead,
                warningLevel = overspeed.warningLevel,
                locationObservedAtMs = observedAtMs,
                locationReceivedElapsedMs = receivedElapsedMs,
                currentStepIndex = location.curStepIndex.takeIf { it >= 0 },
                currentLinkIndex = location.curLinkIndex.takeIf { it >= 0 },
                currentPointIndex = location.curPointIndex.takeIf { it >= 0 },
                routeMatched = location.isMatchNaviPath,
            )
        }
    }

    override fun onNaviInfoUpdate(info: NaviInfo?) {
        traceSdk("guidance") {
            "callbackPathId=${info?.getPathId()} step=${info?.curStep} link=${info?.curLink} " +
                "icon=${info?.iconType} distance=${info?.curStepRetainDistance} pathDistance=${info?.pathRetainDistance}"
        }
        if (info != null) {
            acceptRecalculatedPath(info)
            acceptUnannouncedRecalculatedPath(info)
        }
        val currentState = stateStore.state.value
        if (info == null || !currentState.routePlanned || currentState.routeRecalculating ||
            !routeObservationMatches(currentState.acceptedPathId, info.getPathId(), navi?.naviPath?.pathid)) return
        updateStructuredTrafficLight()
        val receivedElapsedMs = android.os.SystemClock.elapsedRealtime()
        val observedAtMs = System.currentTimeMillis()
        val stepIndex = info.curStep.takeIf { it >= 0 }
        val linkIndex = info.curLink.takeIf { it >= 0 }
        val observationKey = "${info.getPathId()}:$stepIndex:$linkIndex:${info.iconType}:${info.curStepRetainDistance}:${info.pathRetainDistance}"
        val observationChanged = observationKey != lastGuidanceObservationKey
        lastGuidanceObservationKey = observationKey
        val steps = navi?.naviPath?.steps.orEmpty()
        if (stepIndex != lastGuidanceStepIndex) {
            nextManeuverIconType = null
            lastGuidanceStepIndex = stepIndex
        }
        val currentLink = stepIndex?.let { step ->
            linkIndex?.let { link -> steps.getOrNull(step)?.links?.getOrNull(link) }
        }
        val maneuverRoadType = NavigationMappers.maneuverRoadType(
            nextRoadType = stepIndex?.let { step -> steps.getOrNull(step + 1)?.links?.firstOrNull()?.roadType },
            currentStepRoadTypes = stepIndex?.let { step -> steps.getOrNull(step)?.links?.map { it.roadType } }.orEmpty(),
            currentRoadType = currentLink?.roadType,
        )
        val followingStep = stepIndex?.let { steps.getOrNull(it + 1) }
        val followingRoadType = stepIndex?.let { step ->
            steps.getOrNull(step + 2)?.links?.firstOrNull()?.roadType
                ?: followingStep?.links?.firstOrNull()?.roadType
        }
        val followingIconType = nextManeuverIconType ?: followingStep?.iconType
        val followingDistance = followingStep?.length?.takeIf { it >= 0 }?.let { length ->
            info.curStepRetainDistance.takeIf { it >= 0 }?.plus(length)
        }
        update {
            copy(
                currentRoad = info.currentRoadName?.takeIf(String::isNotBlank),
                nextRoad = info.nextRoadName?.takeIf(String::isNotBlank),
                nextTurnType = info.iconType,
                nextTurnDistanceMeters = info.curStepRetainDistance,
                routeRemainDistanceMeters = info.pathRetainDistance,
                routeRemainTimeSeconds = info.pathRetainTime,
                remainingTrafficLightCount = info.routeRemainLightCount.takeIf { it >= 0 },
                guidanceObservedAtMs = if (observationChanged) observedAtMs else guidanceObservedAtMs,
                guidanceReceivedElapsedMs = if (observationChanged) receivedElapsedMs else guidanceReceivedElapsedMs,
                guidanceCallbackElapsedMs = receivedElapsedMs,
                guidancePathId = info.getPathId(),
                // Keyed guidance has confirmed the accepted path installed in this engine.
                unkeyedRouteFactsConfirmed = true,
                maneuver = NavigationMappers.maneuver(info.iconType, maneuverRoadType),
                nextManeuver = NavigationMappers.maneuver(followingIconType, followingRoadType),
                nextManeuverDistanceMeters = followingDistance,
                guidanceStepIndex = stepIndex,
                currentRoadClass = NavigationMappers.validRoadClass(currentLink?.roadClass),
                currentRoadType = NavigationMappers.validRoadType(currentLink?.roadType),
            )
        }
        traceSdk("guidance_accepted") {
            "callbackPathId=${info.getPathId()} step=$stepIndex link=$linkIndex icon=${info.iconType} " +
                "distance=${info.curStepRetainDistance} pathDistance=${info.pathRetainDistance} " +
                "progressChanged=$observationChanged progressObservedAtMs=${stateStore.state.value.guidanceObservedAtMs}"
        }
    }

    override fun onNextManeuverInfoUpdate(iconType: Int, iconBitmap: Bitmap?) {
        traceSdk("next_icon") { "icon=$iconType sourceId=unavailable" }
        if (!stateStore.state.value.acceptsUnkeyedRouteFacts(navi?.naviPath?.pathid)) return
        nextManeuverIconType = iconType
        val state = stateStore.state.value
        val nextRoadType = state.guidanceStepIndex?.let { step ->
            navi?.naviPath?.steps?.getOrNull(step + 2)?.links?.firstOrNull()?.roadType
        }
        update { copy(nextManeuver = NavigationMappers.maneuver(iconType, nextRoadType)) }
    }

    override fun notifyParallelRoad(status: AMapNaviParallelRoadStatus?) {
        if (!stateStore.state.value.acceptsUnkeyedRouteFacts(navi?.naviPath?.pathid)) return
        if (status == null) return
        update {
            copy(
                parallelRoadStatus = roadLayerStatus(status.getmParallelRoadStatusFlag()),
                elevatedRoadStatus = roadLayerStatus(status.getmElevatedRoadStatusFlag()),
            )
        }
    }

    override fun onNaviRouteNotify(notifyData: AMapNaviRouteNotifyData?) {
        if (!stateStore.state.value.acceptsUnkeyedRouteFacts(navi?.naviPath?.pathid)) return
        if (notifyData == null) return
        val notice = RouteNoticeState(
            type = routeNoticeType(notifyData.notifyType),
            distanceMeters = notifyData.distance.takeIf { it >= 0 },
            roadName = notifyData.roadName?.takeIf(String::isNotBlank),
            reason = notifyData.reason?.takeIf(String::isNotBlank),
            subtitle = notifyData.subTitle?.takeIf(String::isNotBlank),
            success = notifyData.isSuccess,
            observedAtMs = System.currentTimeMillis(),
        )
        Log.i(TAG, "AMap route notice type=${notice.type} distance=${notice.distanceMeters} road=${notice.roadName}")
        update { copy(routeNotice = notice) }
    }

    override fun onTrafficStatusUpdate() {
        if (!stateStore.state.value.routePlanned) return
        val worst = navi?.naviPath?.trafficStatuses.orEmpty().maxOfOrNull { it.status } ?: 0
        update { copy(trafficStatus = NavigationMappers.trafficStatus(worst)) }
    }

    override fun updateCameraInfo(infoArray: Array<out AMapNaviCameraInfo>?) {
        applyCameras(infoArray.orEmpty().map(::cameraState))
    }

    override fun updateIntervalCameraInfo(start: AMapNaviCameraInfo?, end: AMapNaviCameraInfo?, status: Int) {
        val cameras = listOfNotNull(start, end).map(::cameraState)
        if (cameras.isNotEmpty()) applyCameras(cameras)
    }

    private fun cameraState(camera: AMapNaviCameraInfo): CameraState = CameraState(
        type = NavigationMappers.cameraType(camera.cameraType),
        latitude = camera.y.takeIf { it in -90.0..90.0 },
        longitude = camera.x.takeIf { it in -180.0..180.0 },
        distanceMeters = camera.cameraDistance.takeIf { it >= 0 },
        limitSpeedKph = NavigationMappers.validSpeedLimit(camera.cameraSpeed),
        intervalRemainDistanceMeters = camera.intervalRemainDistance.takeIf { it >= 0 },
        averageSpeedKph = camera.averageSpeed.takeIf { it > 0 },
        reasonableSpeedKph = camera.reasonableSpeedInRemainDist.takeIf { it > 0 },
    )

    private fun applyCameras(cameras: List<CameraState>) {
        val overspeed = overspeedEvaluator.evaluate(stateStore.state.value.speedKph, cameras)
        update {
            copy(
                cameras = cameras,
                speedLimitKph = overspeed.speedLimitKph,
                isOverspeed = overspeed.isOverspeed,
                hasSpeedCameraAhead = overspeed.hasSpeedCameraAhead,
                warningLevel = overspeed.warningLevel,
            )
        }
    }

    override fun showLaneInfo(laneInfos: Array<out AMapLaneInfo>?, background: ByteArray?, recommended: ByteArray?) {
        traceSdk("lane_callback") {
            "kind=legacy sourceId=unavailable routeFactsConfirmed=${stateStore.state.value.unkeyedRouteFactsConfirmed} " +
                "laneInfos=${laneInfos?.size} declaredLaneCount=${laneInfos?.firstOrNull()?.laneCount} " +
                "background=${background?.toUnsignedList()?.take(16)} " +
                "foreground=${recommended?.toUnsignedList()?.take(16)}"
        }
        if (!stateStore.state.value.routePlanned || stateStore.state.value.routeRecalculating) return
        val observedAtMs = System.currentTimeMillis()
        val raw = background ?: byteArrayOf()
        val front = recommended ?: byteArrayOf()
        val currentState = stateStore.state.value
        val lanePathId = confirmedLanePath(currentState.acceptedPathId, currentState.guidancePathId,
            navi?.naviPath?.pathid, currentState.routeRecalculating)
        if (modernLaneCallbackOwnsResult(currentState.laneLastEvent, currentState.lanesObservedAtMs,
                currentState.lanesPathId, lanePathId, observedAtMs)) {
            Log.i(TAG, "AMap legacy lane callback ignored after recent modern callback")
            return
        }
        val lanes = AmapLaneNormalizer.legacy(
            laneInfos = laneInfos?.map { info ->
                LegacyLaneObservation(
                    laneCount = info.laneCount,
                    typeIds = info.laneTypeIdArray?.toList(),
                    backgroundLane = info.backgroundLane?.toList(),
                )
            },
            background = background,
            recommended = recommended,
        )
        val count = lanes.size
        Log.i(TAG, "AMap legacy lane callback count=$count background=${raw.toUnsignedList()} foreground=${front.toUnsignedList()}")
        update {
            copy(
                lanes = lanes,
                lanesObservedAtMs = observedAtMs,
                lanesPathId = lanePathId,
                laneCallbackCount = laneCallbackCount + 1,
                laneLastEvent = if (count > 0) "legacy_data" else "legacy_empty",
            )
        }
    }

    override fun showLaneInfo(laneInfo: AMapLaneInfo?) {
        traceSdk("lane_callback") {
            "kind=modern sourceId=unavailable routeFactsConfirmed=${stateStore.state.value.unkeyedRouteFactsConfirmed} " +
                "laneCount=${laneInfo?.laneCount} background=${laneInfo?.backgroundLane?.take(16)} " +
                "foreground=${laneInfo?.frontLane?.take(16)}"
        }
        if (laneInfo == null || !stateStore.state.value.routePlanned || stateStore.state.value.routeRecalculating) return
        val observedAtMs = System.currentTimeMillis()
        val currentState = stateStore.state.value
        val lanePathId = confirmedLanePath(currentState.acceptedPathId, currentState.guidancePathId,
            navi?.naviPath?.pathid, currentState.routeRecalculating)
        val lanes = AmapLaneNormalizer.modern(laneInfo.laneCount, laneInfo.backgroundLane, laneInfo.frontLane)
        val count = lanes.size
        Log.i(
            TAG,
            "AMap lane callback count=$count background=${laneInfo.backgroundLane?.toList()} " +
                "foreground=${laneInfo.frontLane?.toList()}",
        )
        update {
            copy(
                lanes = lanes,
                lanesObservedAtMs = observedAtMs,
                lanesPathId = lanePathId,
                laneCallbackCount = laneCallbackCount + 1,
                laneLastEvent = if (count > 0) "data" else "empty",
            )
        }
    }

    override fun hideLaneInfo() {
        traceSdk("lane_callback") {
            "kind=hide sourceId=unavailable routeFactsConfirmed=${stateStore.state.value.unkeyedRouteFactsConfirmed}"
        }
        val observedAtMs = System.currentTimeMillis()
        Log.i(TAG, "AMap lane callback hidden")
        update {
            copy(
                lanes = emptyList(),
                lanesObservedAtMs = observedAtMs,
                lanesPathId = null,
                laneCallbackCount = laneCallbackCount + 1,
                laneLastEvent = "hidden",
            )
        }
    }

    override fun onCalculateRouteSuccess(routeIds: IntArray?) {
        traceSdk("route_success_legacy") { "routeIds=${routeIds?.joinToString()}" }
        // Global callbacks do not own independent planning invocations.
    }
    override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
        traceSdk("route_success") { "sdkRequestId=${result?.getRouteRequestId()} routeIds=${result?.getRouteid()?.joinToString()}" }
        // Global callbacks also describe engine route installation/recalculation. Only the
        // per-invocation independent listener can complete an App planning request.
    }

    private fun routeSucceeded(path: AMapNaviPath? = plannedPaths?.mainPath, engineReroute: Boolean = false) {
        val previousState = stateStore.state.value
        if (!routeCalculationPending && !previousState.routePlanned) return
        val routeRevisionChanged = routeCalculationPending || previousState.routeRecalculating
        routeCalculationPending = false
        confirmedDestination = path?.endPoint?.let { GeoPoint(it.latitude, it.longitude) }
        val choices = if (engineReroute) emptyList() else routeChoices(path)
        val selectedRouteId = choices.firstOrNull { it.selected }?.routeId
        val observedAtMs = System.currentTimeMillis()
        val revision = if (routeRevisionChanged) ++routeRevision else routeRevision
        if (routeRevisionChanged) resetGuidanceCallbackState()
        update {
            copy(
                navigationMode = if (routePlanned) navigationMode else NavigationMode.ROUTE_PLANNED,
                simulationPaused = false,
                routePlanned = true,
                routeRemainDistanceMeters = path?.allLength,
                routeRemainTimeSeconds = path?.allTime,
                remainingTrafficLightCount = path?.trafficLightCount,
                routeTrafficLights = path?.lightList.orEmpty().map { GeoPoint(it.latitude, it.longitude) },
                routeChoices = choices,
                selectedRouteId = selectedRouteId,
                acceptedPathId = path?.pathid,
                guidancePathId = if (routeRevisionChanged) null else guidancePathId,
                lanesPathId = if (routeRevisionChanged) null else lanesPathId,
                guidanceReceivedElapsedMs = if (routeRevisionChanged) null else guidanceReceivedElapsedMs,
                guidanceCallbackElapsedMs = if (routeRevisionChanged) null else guidanceCallbackElapsedMs,
                unkeyedRouteFactsConfirmed = false,
                errorMessage = null,
                routeRevision = revision,
                routeObservedAtMs = if (routeRevisionChanged) observedAtMs else routeObservedAtMs,
                routeRecalculating = false,
                // A new route revision must receive fresh route-relative observations.
                guidanceObservedAtMs = if (routeRevisionChanged) null else guidanceObservedAtMs,
                lanesObservedAtMs = if (routeRevisionChanged) null else lanesObservedAtMs,
                lanes = if (routeRevisionChanged) emptyList() else lanes,
                currentRoad = if (routeRevisionChanged) null else currentRoad,
                nextRoad = if (routeRevisionChanged) null else nextRoad,
                nextTurnType = if (routeRevisionChanged) null else nextTurnType,
                nextTurnDistanceMeters = if (routeRevisionChanged) null else nextTurnDistanceMeters,
                currentStepIndex = if (routeRevisionChanged) null else currentStepIndex,
                currentLinkIndex = if (routeRevisionChanged) null else currentLinkIndex,
                currentPointIndex = if (routeRevisionChanged) null else currentPointIndex,
                routeMatched = if (routeRevisionChanged) null else routeMatched,
                maneuver = if (routeRevisionChanged) NavigationManeuver.UNKNOWN else maneuver,
                nextManeuver = if (routeRevisionChanged) NavigationManeuver.UNKNOWN else nextManeuver,
                nextManeuverDistanceMeters = if (routeRevisionChanged) null else nextManeuverDistanceMeters,
                guidanceStepIndex = if (routeRevisionChanged) null else guidanceStepIndex,
                currentRoadClass = if (routeRevisionChanged) null else currentRoadClass,
                currentRoadType = if (routeRevisionChanged) null else currentRoadType,
                parallelRoadStatus = if (routeRevisionChanged) RoadLayerStatus.UNKNOWN else parallelRoadStatus,
                elevatedRoadStatus = if (routeRevisionChanged) RoadLayerStatus.UNKNOWN else elevatedRoadStatus,
                routeNotice = if (routeRevisionChanged) null else routeNotice,
                laneCallbackCount = if (routeRevisionChanged) 0 else laneCallbackCount,
                laneLastEvent = if (routeRevisionChanged) null else laneLastEvent,
            )
        }
        publishRouteIfChanged(path)
    }

    private fun routeChoices(selectedPath: AMapNaviPath?): List<RouteChoice> =
        plannedRoutePaths().entries
            .sortedBy { it.key }
            .map { (routeId, path) ->
                RouteChoice(
                    routeId = routeId,
                    pathId = path.pathid,
                    label = path.labels?.trim().orEmpty(),
                    distanceMeters = path.allLength,
                    durationSeconds = path.allTime,
                    tollYuan = path.tollCost,
                    trafficLightCount = path.trafficLightCount,
                    selected = selectedPath != null && path.pathid == selectedPath.pathid,
                )
            }

    override fun onCalculateRouteFailure(errorCode: Int) {
        traceSdk("route_failure_legacy") { "error=$errorCode" }
        // Legacy failure cannot be attributed to the current request.
    }
    override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
        traceSdk("route_failure") { "sdkRequestId=${result?.getRouteRequestId()} error=${result?.errorCode}" }
        // Independent request failure is handled by its captured call token.
    }

    private fun routeFailed(message: String) {
        clearRouteTimeout()
        queuedRouteCalculation = null
        if (!routeCalculationPending && !stateStore.state.value.routeRecalculating) return
        routeCalculationPending = false
        val observedAtMs = System.currentTimeMillis()
        val revision = ++routeRevision
        update {
            clearRouteValues().copy(
                errorMessage = message,
                routeRevision = revision,
                routeObservedAtMs = observedAtMs,
            )
        }
        clearPublishedRoute()
    }

    override fun onReCalculateRouteForYaw() = beginEngineReroute("reroute_yaw")
    override fun onReCalculateRouteForTrafficJam() = beginEngineReroute("reroute_traffic")

    private fun beginEngineReroute(event: String) {
        val state = stateStore.state.value
        if (routeCalculationPending || !state.routePlanned ||
            state.navigationMode !in listOf(NavigationMode.REALTIME, NavigationMode.SIMULATION)) return
        traceSdk(event)
        if (state.routeRecalculating) return // Duplicate notifications do not extend the deadline.
        reroutingFromPathId = state.acceptedPathId
        resetGuidanceCallbackState()
        val revision = ++routeRevision
        update {
            clearRouteValues().copy(
                navigationMode = state.navigationMode,
                routePlanned = true,
                routeRecalculating = true,
                startedFromTeslaSync = state.startedFromTeslaSync,
                routeRevision = revision,
                routeObservedAtMs = System.currentTimeMillis(),
                errorMessage = null,
            )
        }
        clearPublishedRoute()
        clearRouteTimeout()
        routeTimeout = Runnable {
            if (stateStore.state.value.routeRecalculating) {
                reroutingFromPathId = null
                navi?.stopNavi()
                routeFailed("路线重算未能确认，请重新规划后启动")
            }
        }.also { callbackHandler.postDelayed(it, 30_000L) }
    }

    private fun acceptRecalculatedPath(info: NaviInfo) {
        val state = stateStore.state.value
        if (!state.routeRecalculating || routeCalculationPending ||
            state.navigationMode !in listOf(NavigationMode.REALTIME, NavigationMode.SIMULATION)) return
        val path = navi?.naviPath ?: return
        val end = path.endPoint?.let { GeoPoint(it.latitude, it.longitude) }
        if (!recalculatedRouteMatches(reroutingFromPathId, info.getPathId(), path.pathid, confirmedDestination, end)) return
        // Confirm an installed SDK path using keyed guidance, never an unkeyed global result.
        // Stop/replan/release invalidate the old-path marker; the engine listener fences generations.
        clearRouteTimeout()
        reroutingFromPathId = null
        clearPlannedPaths() // Old independent candidates must not be selectable after rerouting.
        routeSucceeded(path, engineReroute = true)
        traceSdk("reroute_accepted") { "callbackPathId=${info.getPathId()}" }
    }

    private fun acceptUnannouncedRecalculatedPath(info: NaviInfo) {
        val state = stateStore.state.value
        if (state.routeRecalculating || routeCalculationPending || !state.routePlanned ||
            state.navigationMode !in listOf(NavigationMode.REALTIME, NavigationMode.SIMULATION)) return
        val path = navi?.naviPath ?: return
        val end = path.endPoint?.let { GeoPoint(it.latitude, it.longitude) }
        if (!recalculatedRouteMatches(state.acceptedPathId, info.getPathId(), path.pathid, confirmedDestination, end)) return
        beginEngineReroute("reroute_unannounced")
        acceptRecalculatedPath(info)
    }

    private fun updateStructuredTrafficLight() {
        val countdown = structuredTrafficLightCountdown(navi?.structuredInfoInNavi) ?: return
        val observedAtMs = System.currentTimeMillis()
        update {
            val broadcast = trafficLight?.takeIf {
                observedAtMs - it.observedAtMs in 0..3_000 && it.status in 2..4
            }
            if (broadcast != null) this else copy(trafficLight = com.garan.tesnav.model.TrafficLightObservation(
                status = 0,
                direction = null,
                countdownSeconds = countdown,
                observedAtMs = observedAtMs,
            ))
        }
    }

    override fun onArriveDestination() = update {
        clearRouteTimeout()
        reroutingFromPathId = null
        copy(navigationMode = NavigationMode.ARRIVED, simulationPaused = false, errorMessage = null)
    }
    override fun onEndEmulatorNavi() {
        if (stateStore.state.value.routePlanned) {
            update { copy(navigationMode = NavigationMode.ROUTE_PLANNED, simulationPaused = false) }
        }
    }
    override fun onGpsSignalWeak(weak: Boolean) {
        phoneGpsSignalWeak = weak
        val effectiveWeak = com.garan.tesnav.model.selectedLocationWeak(externalLocation.isExternalEnabled,
            externalLocation.injectedFix != null, weak)
        traceSdk("gps_signal") { "phoneWeak=$weak external=${externalLocation.isExternalEnabled} effectiveWeak=$effectiveWeak previousWeak=${stateStore.state.value.gpsSignalWeak}" }
        update {
            copy(gpsSignalWeak = effectiveWeak, errorMessage = if (effectiveWeak) "GPS 信号弱" else errorMessage?.takeUnless { it == "GPS 信号弱" || it == "定位暂不可用" })
        }
    }
    override fun onGpsOpenStatus(enabled: Boolean) {
        traceSdk("gps_open") { "enabled=$enabled" }
        if (!enabled) update { copy(errorMessage = "系统定位开关未开启") }
    }

    private fun update(transform: com.garan.tesnav.model.NavigationState.() -> com.garan.tesnav.model.NavigationState) {
        stateStore.update(transform)
    }

    private fun publishRouteIfChanged(path: AMapNaviPath?) {
        val coordinates = path?.coordList.orEmpty()
        if (path == null || coordinates.size < 2) return
        val first = coordinates.first()
        val last = coordinates.last()
        val geometryHash = coordinates.fold(1L) { hash, point ->
            31L * (31L * hash + point.latitude.toBits()) + point.longitude.toBits()
        }
        val signature = "${stateStore.state.value.routeRevision}:${path.pathid}:${path.allLength}:${coordinates.size}:" +
            "${first.latitude}:${first.longitude}:${last.latitude}:${last.longitude}:$geometryHash"
        if (signature == publishedRouteSignature) return
        publishedRouteSignature = signature
        onRouteChanged(path)
    }

    private fun clearPublishedRoute() {
        if (publishedRouteSignature == null) return
        publishedRouteSignature = null
        onRouteChanged(null)
    }

    private fun com.garan.tesnav.model.NavigationState.clearRouteValues() = copy(
        navigationMode = NavigationMode.IDLE,
        simulationPaused = false,
        currentRoad = null,
        nextRoad = null,
        nextTurnType = null,
        nextTurnDistanceMeters = null,
        routeRemainDistanceMeters = null,
        routeRemainTimeSeconds = null,
        remainingTrafficLightCount = null,
        routeTrafficLights = emptyList(),
        trafficStatus = TrafficStatus.UNKNOWN,
        lanes = emptyList(),
        cameras = emptyList(),
        speedLimitKph = null,
        isOverspeed = false,
        hasSpeedCameraAhead = false,
        warningLevel = WarningLevel.NONE,
        routePlanned = false,
        startedFromTeslaSync = false,
        guidanceObservedAtMs = null,
        lanesObservedAtMs = null,
        currentStepIndex = null,
        currentLinkIndex = null,
        currentPointIndex = null,
        routeMatched = null,
        maneuver = NavigationManeuver.NONE,
        nextManeuver = NavigationManeuver.NONE,
        nextManeuverDistanceMeters = null,
        guidanceStepIndex = null,
        currentRoadClass = null,
        currentRoadType = null,
        parallelRoadStatus = RoadLayerStatus.UNKNOWN,
        elevatedRoadStatus = RoadLayerStatus.UNKNOWN,
        routeNotice = null,
        laneCallbackCount = 0,
        laneLastEvent = null,
        routeRecalculating = false,
        routeChoices = emptyList(),
        selectedRouteId = null,
        acceptedPathId = null,
        guidancePathId = null,
        lanesPathId = null,
        guidanceReceivedElapsedMs = null,
        guidanceCallbackElapsedMs = null,
        unkeyedRouteFactsConfirmed = false,
        navAssistControlAllowed = false,
    ).also { resetGuidanceCallbackState() }

    private fun listenerForEngine(generation: Long) = object : SimpleNaviListener() {
        override fun onInitNaviFailure() {
            if (generation == engineGeneration) this@NavigationRepository.onInitNaviFailure()
        }
        override fun onInitNaviSuccess() {
            if (generation == engineGeneration) this@NavigationRepository.onInitNaviSuccess()
        }
        override fun onStartNavi(type: Int) {
            if (generation == engineGeneration) this@NavigationRepository.onStartNavi(type)
        }
        override fun onLocationChange(location: AMapNaviLocation?) {
            if (generation == engineGeneration) this@NavigationRepository.onLocationChange(location)
        }
        override fun onNaviInfoUpdate(info: NaviInfo?) {
            if (generation == engineGeneration) this@NavigationRepository.onNaviInfoUpdate(info)
        }
        override fun onNextManeuverInfoUpdate(iconType: Int, iconBitmap: Bitmap?) {
            if (generation == engineGeneration) this@NavigationRepository.onNextManeuverInfoUpdate(iconType, iconBitmap)
        }
        override fun onNaviRouteNotify(notifyData: AMapNaviRouteNotifyData?) {
            if (generation == engineGeneration) this@NavigationRepository.onNaviRouteNotify(notifyData)
        }
        override fun onTrafficStatusUpdate() {
            if (generation == engineGeneration) this@NavigationRepository.onTrafficStatusUpdate()
        }
        override fun updateCameraInfo(infoArray: Array<out AMapNaviCameraInfo>?) {
            if (generation == engineGeneration) this@NavigationRepository.updateCameraInfo(infoArray)
        }
        override fun updateIntervalCameraInfo(start: AMapNaviCameraInfo?, end: AMapNaviCameraInfo?, status: Int) {
            if (generation == engineGeneration) this@NavigationRepository.updateIntervalCameraInfo(start, end, status)
        }
        override fun showLaneInfo(laneInfos: Array<out AMapLaneInfo>?, background: ByteArray?, recommended: ByteArray?) {
            if (generation == engineGeneration) this@NavigationRepository.showLaneInfo(laneInfos, background, recommended)
        }
        override fun showLaneInfo(laneInfo: AMapLaneInfo?) {
            if (generation == engineGeneration) this@NavigationRepository.showLaneInfo(laneInfo)
        }
        override fun hideLaneInfo() {
            if (generation == engineGeneration) this@NavigationRepository.hideLaneInfo()
        }
        override fun onCalculateRouteSuccess(routeIds: IntArray?) {
            if (generation == engineGeneration) this@NavigationRepository.onCalculateRouteSuccess(routeIds)
        }
        override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
            if (generation == engineGeneration) this@NavigationRepository.onCalculateRouteSuccess(result)
        }
        override fun onCalculateRouteFailure(errorCode: Int) {
            if (generation == engineGeneration) this@NavigationRepository.onCalculateRouteFailure(errorCode)
        }
        override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
            if (generation == engineGeneration) this@NavigationRepository.onCalculateRouteFailure(result)
        }
        override fun onReCalculateRouteForYaw() {
            if (generation == engineGeneration) this@NavigationRepository.onReCalculateRouteForYaw()
        }
        override fun onReCalculateRouteForTrafficJam() {
            if (generation == engineGeneration) this@NavigationRepository.onReCalculateRouteForTrafficJam()
        }
        override fun onArriveDestination() {
            if (generation == engineGeneration) this@NavigationRepository.onArriveDestination()
        }
        override fun onEndEmulatorNavi() {
            if (generation == engineGeneration) this@NavigationRepository.onEndEmulatorNavi()
        }
        override fun onGpsSignalWeak(weak: Boolean) {
            if (generation == engineGeneration) this@NavigationRepository.onGpsSignalWeak(weak)
        }
        override fun onGpsOpenStatus(enabled: Boolean) {
            if (generation == engineGeneration) this@NavigationRepository.onGpsOpenStatus(enabled)
        }
    }

    private inline fun traceSdk(event: String, details: () -> String = { "" }) {
        if (!com.garan.tesnav.BuildConfig.DEBUG && !Log.isLoggable(TAG, Log.DEBUG)) return
        // Logging must not change SDK callback behavior, including when a getter fails.
        runCatching {
            val line = "sdk_event=$event elapsedMs=${android.os.SystemClock.elapsedRealtime()} " +
                "repository=${System.identityHashCode(this)} engine=$engineGeneration requestContext=$diagnosticRequestSerial " +
                "revision=$routeRevision pending=$routeCalculationPending " +
                "acceptedPathId=${stateStore.state.value.acceptedPathId} currentPathId=${navi?.naviPath?.pathid} ${details()}"
            Log.d(TAG, line)
            if (com.garan.tesnav.BuildConfig.DEBUG) com.garan.tesnav.util.NavigationTrace.append(appContext.filesDir, line)
        }
    }

    private fun resetGuidanceCallbackState() {
        lastGuidanceObservationKey = null
        nextManeuverIconType = null
        lastGuidanceStepIndex = null
    }

    // One shared SDK instance can outlive a Service while a map still owns its location layer.
    private class EngineState(val engine: AMapNavi) {
        val requests = RouteRequestAssociation()
        val installedPaths = mutableListOf<AMapNaviPathGroup>()
        var wakeup: (() -> Unit)? = null
        var hasSimulatedLocation = false
    }

    private companion object {
        var sharedEngineState: EngineState? = null
        const val ENGINE_RESTART_MESSAGE = "路线规划失败：导航引擎未响应，请在系统设置中强行停止本应用后重新打开"
        const val TAG = "TesNav-AMap"
        const val DEFAULT_EMULATOR_SPEED_KPH = 120
    }

    private fun roadLayerStatus(raw: Int): RoadLayerStatus = when (raw) {
        AMapNaviParallelRoadStatus.STATUS_MAIN_ROAD -> RoadLayerStatus.MAIN
        AMapNaviParallelRoadStatus.STATUS_SIDE_ROAD -> RoadLayerStatus.SIDE
        else -> RoadLayerStatus.UNKNOWN
    }

    private fun routeNoticeType(raw: Int): RouteNoticeType = when (raw) {
        AMapNaviRouteNotifyDataType.AVOID_RESTRICT_AREA -> RouteNoticeType.RESTRICTED_AREA
        AMapNaviRouteNotifyDataType.FORBIDDEN_AREA -> RouteNoticeType.FORBIDDEN_AREA
        AMapNaviRouteNotifyDataType.ROAD_CLOSED_AREA -> RouteNoticeType.ROAD_CLOSED
        AMapNaviRouteNotifyDataType.AVOID_JAM_AREA -> RouteNoticeType.CONGESTION
        AMapNaviRouteNotifyDataType.DISPATCH -> RouteNoticeType.DISPATCH
        AMapNaviRouteNotifyDataType.CHANGE_MAIN_ROUTE -> RouteNoticeType.ROUTE_CHANGED
        AMapNaviRouteNotifyDataType.GPS_SIGNAL_WEAK -> RouteNoticeType.GPS_WEAK
        else -> RouteNoticeType.UNKNOWN
    }

    private fun ByteArray.toUnsignedList(): List<Int> = map { it.toInt() and 0xff }
}

internal fun modernLaneCallbackOwnsResult(
    lastEvent: String?, lastObservedAtMs: Long?, lastPathId: Long?, pathId: Long?, nowMs: Long,
): Boolean = (lastEvent == "data" || lastEvent == "empty") && lastPathId == pathId &&
    lastObservedAtMs != null && nowMs - lastObservedAtMs in 0L..1000L
