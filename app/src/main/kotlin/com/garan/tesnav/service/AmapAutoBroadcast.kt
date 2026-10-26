package com.garan.tesnav.service

import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.TrafficLightObservation
import com.garan.tesnav.model.LaneState
import com.garan.tesnav.util.NavigationMappers
import com.google.gson.JsonParser

enum class NavigationDataSource(
    val displayName: String,
    val integrated: Boolean,
) {
    AMAP_API("高德 API", true),
    AMAP_AUTO("高德车机", true),
    TENCENT_API("腾讯 API", false),
    GOOGLE_API("Google API", false),
    ;

    val buttonText: String get() = "来源：$displayName"
    val menuText: String get() = if (integrated) displayName else "$displayName（待配置）"
}

internal object NavigationSourcePolicy {
    private val tesNavSources = listOf(NavigationDataSource.AMAP_API, NavigationDataSource.AMAP_AUTO)

    fun visibleSources(extendedSources: Boolean): List<NavigationDataSource> =
        if (extendedSources) NavigationDataSource.entries else tesNavSources

    fun isVisible(source: NavigationDataSource, extendedSources: Boolean): Boolean =
        extendedSources || source in tesNavSources

    fun canSelect(source: NavigationDataSource, extendedSources: Boolean): Boolean =
        isVisible(source, extendedSources) && source.integrated

    fun stateFor(source: NavigationDataSource, api: NavigationState, auto: NavigationState?): NavigationState =
        when (source) {
            NavigationDataSource.AMAP_API -> api
            NavigationDataSource.AMAP_AUTO -> auto ?: NavigationState()
            else -> NavigationState()
        }.copy(navigationProvider = source.name)

    fun canSearch(source: NavigationDataSource, apiMode: NavigationMode): Boolean =
        source == NavigationDataSource.AMAP_AUTO || apiMode == NavigationMode.IDLE

    fun syncOwnsNavigation(source: NavigationDataSource, api: NavigationState): Boolean =
        source == NavigationDataSource.AMAP_API && api.startedFromTeslaSync

    fun restore(value: String?, extendedSources: Boolean): NavigationDataSource =
        runCatching { NavigationDataSource.valueOf(value.orEmpty()) }
            .getOrNull()
            ?.takeIf { canSelect(it, extendedSources) }
            ?: NavigationDataSource.AMAP_API

    fun buttonText(source: NavigationDataSource, extendedSources: Boolean): String =
        if (extendedSources) {
            source.buttonText
        } else if (source == NavigationDataSource.AMAP_AUTO) {
            "来源：车机"
        } else {
            "来源：API"
        }
}

/** Latest lossless-enough snapshot of one Amap Auto broadcast interface. */
internal data class AmapAutoBroadcastSnapshot(
    val action: String?,
    val keyType: Int?,
    val extras: Map<String, String?>,
    val observedAtMs: Long,
) {
    val interfaceKey: String = "${action.orEmpty()}:${keyType ?: "unknown"}"
}

internal object AmapAutoBroadcast {
    fun capture(action: String?, extras: Map<String, Any?>, observedAtMs: Long): AmapAutoBroadcastSnapshot =
        AmapAutoBroadcastSnapshot(
            action = action,
            keyType = extras["KEY_TYPE"].asInt(),
            extras = extras.mapValues { (_, value) -> value.asStableString() },
            observedAtMs = observedAtMs,
        )

    fun retain(
        current: Map<String, AmapAutoBroadcastSnapshot>,
        snapshot: AmapAutoBroadcastSnapshot,
    ): Map<String, AmapAutoBroadcastSnapshot> = current + (snapshot.interfaceKey to snapshot)

    /** Exact KEY_TYPE=60073 scalar contract used by the pinned jihui Amap app. */
    fun trafficLight(extras: Map<String, Any?>, observedAtMs: Long): TrafficLightObservation? {
        val status = extras["trafficLightStatus"].asInt() ?: return null
        return TrafficLightObservation(
            status = status,
            direction = extras["dir"].asInt(),
            countdownSeconds = extras["redLightCountDownSeconds"].asInt()?.takeIf { it in 0..3_600 },
            observedAtMs = observedAtMs,
        )
    }

    private fun freshLocation(location: NavigationState?, wallMs: Long, elapsedMs: Long, budgetMs: Long): Boolean {
        val receivedAt = location?.locationReceivedElapsedMs ?: return false
        return budgetMs > 0 && receivedAt >= 0 && receivedAt <= elapsedMs && elapsedMs - receivedAt <= budgetMs &&
            location.accuracy?.let { it.isFinite() && it > 0f } == true &&
            location.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
            location.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true &&
            location.locationObservedAtMs?.let { it >= 0 && wallMs - it in 0..budgetMs } == true
    }

    /** Position callbacks cannot renew guidance, route identity or control permission. */
    fun refreshLocation(previous: NavigationState?, location: NavigationState?,
                        wallMs: Long, elapsedMs: Long, budgetMs: Long): NavigationState? {
        if (previous == null || !freshLocation(location, wallMs, elapsedMs, budgetMs)) return previous
        return previous.copy(
            latitude = location!!.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            bearing = location.bearing ?: previous.bearing,
            locationTime = location.locationTime,
            locationObservedAtMs = location.locationObservedAtMs,
            locationReceivedElapsedMs = location.locationReceivedElapsedMs,
            locationSourceStatus = location.locationSourceStatus,
        )
    }

    fun navigation(
        previous: NavigationState?,
        locationFallback: NavigationState?,
        extras: Map<String, Any?>,
        observedAtMs: Long,
        receivedElapsedMs: Long,
        sourceBudgetMs: Long,
    ): NavigationState {
        // jihui c523c47 consumes ICON. Its namespace differs from SDK IconType.
        val icon = extras["ICON"].asInt()?.takeIf { it > 0 } ?: extras["NEW_ICON"].asInt()
        // Auto ROAD_TYPE is a road category (jihui raw 0=highway, 6=expressway),
        // not SDK RoadType (6=ramp). Only the confirmed common categories map to RoadClass.
        val roadClass = extras["ROAD_TYPE"].asInt()?.takeIf { it in setOf(0, 1, 6) }
        val routeDistance = extras["ROUTE_REMAIN_DIS"].asInt()?.takeIf { it >= 0 }
        val turnDistance = extras["SEG_REMAIN_DIS"].asInt()?.takeIf { it >= 0 }
        val routeActive = extras["ROUTE_ALL_DIS"].asInt()?.let { it > 0 } == true ||
            routeDistance?.let { it > 0 } == true ||
            (icon?.let { it > 0 } == true && turnDistance?.let { it > 0 } == true)
        val speed = extras["CUR_SPEED"].asFloat()?.coerceAtLeast(0f) ?: previous?.speedKph ?: 0f
        val speedLimit = extras["LIMITED_SPEED"].asInt()?.takeIf { it > 0 }
        val broadcastLatitude = extras["CAR_LATITUDE"].asDouble()?.takeIf { it in -90.0..90.0 }
        val broadcastLongitude = extras["CAR_LONGITUDE"].asDouble()?.takeIf { it in -180.0..180.0 }
        val broadcastAccuracy = (extras["CAR_ACCURACY"] ?: extras["GPS_ACCURACY"] ?: extras["ACCURACY"])
            .asFloat()?.takeIf { it.isFinite() && it > 0f }
        val fallbackFresh = freshLocation(locationFallback, observedAtMs, receivedElapsedMs, sourceBudgetMs)
        // A position and accuracy are one observation. Never stamp retained values with
        // this navigation callback's time, nor splice a partial broadcast with old GPS.
        val location = when {
            fallbackFresh && locationFallback?.locationSourceStatus == "vehicle-can" -> locationFallback
            broadcastLatitude != null && broadcastLongitude != null && broadcastAccuracy != null -> NavigationState(
                latitude = broadcastLatitude, longitude = broadcastLongitude, accuracy = broadcastAccuracy,
                bearing = extras["CAR_DIRECTION"].asFloat(), locationTime = observedAtMs,
                locationObservedAtMs = observedAtMs, locationReceivedElapsedMs = receivedElapsedMs,
                locationSourceStatus = "amap_auto",
            )
            fallbackFresh -> locationFallback
            else -> previous
        }
        val maneuver = when (icon) {
            1 -> com.garan.tesnav.model.NavigationManeuver.STRAIGHT
            9 -> com.garan.tesnav.model.NavigationManeuver.U_TURN_RIGHT
            10 -> com.garan.tesnav.model.NavigationManeuver.KEEP_LEFT
            11 -> com.garan.tesnav.model.NavigationManeuver.KEEP_RIGHT
            12, 13 -> com.garan.tesnav.model.NavigationManeuver.ROUNDABOUT
            // Auto's highway left/right-front guidance is a fork, not an SDK link RoadType.
            4 -> if (roadClass in setOf(0, 6)) com.garan.tesnav.model.NavigationManeuver.MERGE_LEFT
                else com.garan.tesnav.model.NavigationManeuver.SLIGHT_LEFT
            5 -> if (roadClass in setOf(0, 6)) com.garan.tesnav.model.NavigationManeuver.MERGE_RIGHT
                else com.garan.tesnav.model.NavigationManeuver.SLIGHT_RIGHT
            else -> NavigationMappers.maneuver(icon)
        }
        val previousActive = previous?.routePlanned == true && previous.navigationMode == NavigationMode.REALTIME
        val newRoute = routeActive && !previousActive
        val maneuverChanged = previousActive && routeActive &&
            (previous?.maneuver != maneuver || previous.nextRoad != (extras["NEXT_ROAD_NAME"].asText() ?: previous.nextRoad))
        val distanceReset = previousActive && routeActive && turnDistance != null &&
            previous?.nextTurnDistanceMeters?.let { turnDistance > it + 50 } == true
        val stepIndex = when {
            !routeActive -> null
            newRoute -> 0
            maneuverChanged || distanceReset -> (previous?.currentStepIndex ?: 0) + 1
            else -> previous?.currentStepIndex ?: 0
        }
        val pathId = when {
            !routeActive -> null
            newRoute -> observedAtMs.coerceAtLeast(1L)
            else -> previous?.acceptedPathId ?: observedAtMs.coerceAtLeast(1L)
        }
        val routeRevision = if (newRoute) (previous?.routeRevision ?: 0L) + 1L else previous?.routeRevision ?: 0L

        return NavigationState(
            navigationProvider = NavigationDataSource.AMAP_AUTO.name,
            navigationMode = if (routeActive) NavigationMode.REALTIME else NavigationMode.IDLE,
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracy = location?.accuracy,
            bearing = location?.bearing ?: extras["CAR_DIRECTION"].asFloat(),
            locationTime = location?.locationTime,
            speedKph = speed,
            currentRoad = extras["CUR_ROAD_NAME"].asText() ?: previous?.currentRoad,
            nextRoad = extras["NEXT_ROAD_NAME"].asText() ?: previous?.nextRoad,
            nextTurnType = icon,
            nextTurnDistanceMeters = turnDistance,
            routeRemainDistanceMeters = routeDistance,
            routeRemainTimeSeconds = extras["ROUTE_REMAIN_TIME"].asInt()?.takeIf { it >= 0 },
            remainingTrafficLightCount = extras["routeRemainTrafficLightNum"].asInt()?.takeIf { it >= 0 },
            speedLimitKph = speedLimit,
            isOverspeed = speedLimit?.let { speed > it } ?: false,
            routePlanned = routeActive,
            locationObservedAtMs = location?.locationObservedAtMs,
            locationReceivedElapsedMs = location?.locationReceivedElapsedMs,
            locationSourceStatus = location?.locationSourceStatus ?: "none",
            guidanceReceivedElapsedMs = receivedElapsedMs,
            guidanceCallbackElapsedMs = receivedElapsedMs,
            acceptedPathId = pathId,
            guidancePathId = pathId,
            navAssistControlAllowed = false,
            navAssistSourceStatus = "amap_auto_pending",
            guidanceObservedAtMs = observedAtMs,
            routeObservedAtMs = observedAtMs,
            routeMatched = routeActive,
            maneuver = maneuver,
            currentStepIndex = stepIndex,
            guidanceStepIndex = stepIndex,
            currentRoadClass = roadClass,
            // Auto has no confirmed SDK-equivalent link RoadType field.
            currentRoadType = null,
            trafficLight = previous?.trafficLight,
            routeRevision = routeRevision,
            lanes = previous?.lanes.orEmpty().takeIf {
                routeActive && !newRoute && !maneuverChanged && !distanceReset &&
                    previous?.lanesPathId == pathId &&
                    previous?.lanesObservedAtMs?.let { observedAtMs - it in 0..sourceBudgetMs } == true
            }.orEmpty(),
            lanesPathId = previous?.lanesPathId?.takeIf {
                routeActive && !newRoute && !maneuverChanged && !distanceReset && it == pathId &&
                    previous.lanesObservedAtMs?.let { stamp -> observedAtMs - stamp in 0..sourceBudgetMs } == true
            },
            lanesObservedAtMs = previous?.lanesObservedAtMs,
            autoDisplayLanes = previous?.autoDisplayLanes.orEmpty().takeIf {
                routeActive && !newRoute && !maneuverChanged && !distanceReset && previous?.autoDisplayLanesPathId == pathId
            }.orEmpty(),
            autoDisplayLanesPathId = previous?.autoDisplayLanesPathId?.takeIf {
                routeActive && !newRoute && !maneuverChanged && !distanceReset && it == pathId
            },
            laneCallbackCount = previous?.laneCallbackCount ?: 0,
            laneLastEvent = previous?.laneLastEvent,
            speechEnabled = previous?.speechEnabled ?: true,
        )
    }

    /** 13012 Auto uses icon colors/semantics distinct from SDK LaneAction. */
    fun driveWay(previous: NavigationState?, raw: String?, observedAtMs: Long,
                 receivedElapsedMs: Long, sourceBudgetMs: Long): NavigationState? {
        if (previous == null) return null // An unkeyed lane callback cannot install route facts.
        val parsed = runCatching {
            require(raw != null && raw.length <= 32_768)
            if (GblLaneBroadcast.isNative(raw)) return@runCatching requireNotNull(GblLaneBroadcast.parse(raw))
            val obj = JsonParser.parseString(raw).asJsonObject
            if (!obj["drive_way_enabled"].asBoolean) return@runCatching emptyList<LaneState>()
            val count = obj["drive_way_size"].asInt
            require(count in 1..16)
            val info = obj["drive_way_info"].asJsonArray
            require(info.size() == count)
            info.mapIndexed { index, item ->
                val lane = item.asJsonObject
                val autoIcon = lane["drive_way_lane_Back_icon"].asInt
                val sdkIcon = AUTO_LANE_TO_SDK[autoIcon] ?: -1
                val allowed = NavigationMappers.navAssistV2LaneActions(sdkIcon)
                val advised = lane["trafficLaneAdvised"]?.asBoolean == true && sdkIcon >= 0
                LaneState(index, allowed, advised, sdkIcon,
                    if (advised) allowed else emptyList(), sdkIcon.takeIf { advised },
                    autoIcon in AUTO_AVOID_LANES)
            }
        }.getOrNull()
        val keyed = previous.routePlanned && previous.navigationMode == NavigationMode.REALTIME &&
            previous.acceptedPathId != null && previous.acceptedPathId == previous.guidancePathId &&
            previous.currentStepIndex != null && previous.currentStepIndex == previous.guidanceStepIndex &&
            previous.guidanceCallbackElapsedMs?.let { receivedElapsedMs - it in 0..sourceBudgetMs } == true &&
            previous.guidanceObservedAtMs?.let { observedAtMs - it in 0..sourceBudgetMs } == true
        val lanes = parsed.orEmpty().takeIf { keyed }.orEmpty()
        return previous.copy(
            lanes = lanes, lanesObservedAtMs = observedAtMs,
            // This Auto version marks route-compatible directions in the icon itself.
            // Keep this display projection separate from the exported lane facts.
            autoDisplayLanes = parsed.orEmpty().takeIf { keyed }.orEmpty().map { lane ->
                lane.copy(recommended = lane.recommended ||
                    (!GblLaneBroadcast.isNative(raw) && !lane.prohibited && lane.rawLaneType >= 0 && lane.rawLaneType != 23))
            },
            autoDisplayLanesPathId = previous.acceptedPathId.takeIf { keyed && parsed != null },
            lanesPathId = previous.acceptedPathId.takeIf { keyed && parsed != null },
            laneCallbackCount = previous.laneCallbackCount + 1,
            laneLastEvent = when { parsed == null -> "invalid"; lanes.isEmpty() -> "hidden"; else -> "auto" },
        )
    }

    // Semantic translation from fixed jihui driveWayIconMap, never identity-casting Auto IDs.
    private val AUTO_LANE_TO_SDK = mapOf(
        15 to 0, 16 to 1, 17 to 2, 18 to 3, 19 to 4, 20 to 5, 24 to 9, 26 to 11,
        43 to 1, 44 to 5, 71 to 0, 72 to 1, 30 to 0, 31 to 1, 32 to 0, 33 to 3,
        34 to 1, 35 to 3, 36 to 0, 37 to 1, 38 to 3, 39 to 0, 40 to 5, 62 to 23,
    )
    private val AUTO_AVOID_LANES = setOf(0, 1, 2, 3, 4, 5, 9, 11, 49, 54, 89)

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> toInt()
        else -> toString().toIntOrNull()
    }

    private fun Any?.asFloat(): Float? = when (this) {
        is Number -> toFloat()
        else -> toString().toFloatOrNull()
    }

    private fun Any?.asDouble(): Double? = when (this) {
        is Number -> toDouble()
        else -> toString().toDoubleOrNull()
    }

    private fun Any?.asText(): String? = toString().trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun Any?.asStableString(): String? = when (this) {
        null -> null
        is ByteArray -> contentToString()
        is ShortArray -> contentToString()
        is IntArray -> contentToString()
        is LongArray -> contentToString()
        is FloatArray -> contentToString()
        is DoubleArray -> contentToString()
        is BooleanArray -> contentToString()
        is CharArray -> contentToString()
        is Array<*> -> contentDeepToString()
        else -> toString()
    }
}
