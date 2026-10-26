package com.garan.tesnav.export

import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationManeuver
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.util.NavigationMappers
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object NavAssistV2Protocol {
    const val SCHEMA_VERSION = 3
    const val MESSAGE_TYPE = "navigation_snapshot"
    const val SOURCE_PLATFORM = "android"
    const val COORDINATE_SYSTEM = "gcj02"
    const val ENDPOINT_PATH = "/v3/snapshot"
    const val MIN_INTERVAL_MS = 200L
    const val DEFAULT_VALID_FOR_MS = 1_200L
    const val MIN_VALID_FOR_MS = 100L
    const val MAX_VALID_FOR_MS = 2_000L
    const val SIGNATURE_HEADER = NavAssistV3Auth.SIGNATURE_HEADER
    const val SESSION_ID_PATTERN = "^[A-Za-z0-9._:-]+$"
    const val MAX_LOCATION_ACCURACY_M = 200f
    const val MAX_SPEED_KPH = 300f
    const val MAX_MANEUVER_DISTANCE_M = 100_000
    const val MAX_ROAD_NAME_LENGTH = 256
}

data class NavAssistV2ExportConfig(
    val baseUrl: String,
    val intervalMs: Long = NavAssistV2Protocol.MIN_INTERVAL_MS,
    val validForMs: Long = NavAssistV2Protocol.DEFAULT_VALID_FOR_MS,
) {
    fun isConfigured(): Boolean {
        if (!hasValidLifetime()) return false
        return baseUrl.isBlank() || hasValidExplicitUrl()
    }

    fun usesDiscovery(): Boolean = isConfigured() && baseUrl.isBlank()

    fun hasValidExplicitUrl(): Boolean {
        val parsedUrl = baseUrl.trim().toHttpUrlOrNull() ?: return false
        return parsedUrl.scheme in setOf("http", "https")
    }

    fun hasValidLifetime(): Boolean =
        validForMs in NavAssistV2Protocol.MIN_VALID_FOR_MS..NavAssistV2Protocol.MAX_VALID_FOR_MS
}

data class NavAssistV2Snapshot(
    val schemaVersion: Int,
    val messageType: String,
    val sessionId: String,
    val sequence: Long,
    val routeRevision: Long,
    val maneuverEventId: Long,
    val sourcePlatform: String,
    val sourceWallTimeMs: Long,
    val validForMs: Long,
    val navigationMode: String,
    val routeActive: Boolean,
    val routeMatched: Boolean?,
    val gpsWeak: Boolean,
    val coordinateSystem: String,
    val location: NavAssistV2Location?,
    val guidance: NavAssistV2Guidance?,
    val lanes: NavAssistV2Lanes?,
    val laneChangeSpeechCompletedId: String? = null,
)

data class NavAssistV2Location(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val bearingDeg: Float,
    val speedKph: Float,
    val observedAtMs: Long,
    val currentStepIndex: Int?,
    val currentLinkIndex: Int?,
    val currentPointIndex: Int?,
)

data class NavAssistV2Guidance(
    val observedAtMs: Long,
    val maneuver: String,
    val maneuverDistanceM: Int?,
    val nextManeuver: String?,
    val nextManeuverDistanceM: Int?,
    val currentRoad: String?,
    val nextRoad: String?,
    val roadClass: Int?,
    val roadType: Int?,
    val advisorySpeedMps: Float?,
    val parallelRoadStatus: String?,
    val elevatedRoadStatus: String?,
    val routeNoticeType: String?,
    val routeNoticeDistanceM: Int?,
    val routeNoticeObservedAtMs: Long?,
    val turnSignalHold: Boolean? = null,
    val turnSignalCountdownS: Int? = null,
)

data class NavAssistV2Lanes(
    val observedAtMs: Long,
    val items: List<NavAssistV2Lane>,
)

data class NavAssistV2Lane(
    val index: Int,
    val allowedActions: List<String>,
    val recommended: Boolean,
    val recommendedActions: List<String>,
    val routeAvoid: Boolean,
)

/** Owns the session UUID and sequence; the navigation service retains it across exporter rebuilds. */
class NavAssistV2Session(
    sessionId: String = UUID.randomUUID().toString(),
    private val validForMs: Long = NavAssistV2Protocol.DEFAULT_VALID_FOR_MS,
) {
    @Volatile var sessionId: String = sessionId
        private set
    private val sequence = AtomicLong(0L)
    private val sendLock = Any()

    // Shared across exporter instances: an old in-flight send must finish before a replacement sends.
    internal fun <T> serializedSend(block: () -> T): T = synchronized(sendLock, block)

    // Keep the shared send lock: stopped exporters must drain before the new owner starts.
    internal fun switchSource() = serializedSend {
        sessionId = UUID.randomUUID().toString()
        sequence.set(0L)
    }

    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(sessionId.length <= 64) { "sessionId must not exceed 64 characters" }
        require(Regex(NavAssistV2Protocol.SESSION_ID_PATTERN).matches(sessionId)) {
            "sessionId contains unsupported characters"
        }
        require(validForMs in NavAssistV2Protocol.MIN_VALID_FOR_MS..NavAssistV2Protocol.MAX_VALID_FOR_MS) {
            "validForMs must be between ${NavAssistV2Protocol.MIN_VALID_FOR_MS} and ${NavAssistV2Protocol.MAX_VALID_FOR_MS}"
        }
    }

    fun nextSnapshot(state: NavigationState, sourceWallTimeMs: Long): NavAssistV2Snapshot {
        val nextSequence = sequence.incrementAndGet()
        return NavAssistV2Mapper.snapshot(
            state = state,
            sessionId = sessionId,
            sequence = nextSequence,
            sourceWallTimeMs = sourceWallTimeMs,
            validForMs = validForMs,
        )
    }
}

object NavAssistV2Mapper {
    private fun trafficLightTurnHold(state: NavigationState, sourceWallTimeMs: Long, validForMs: Long): Pair<Boolean?, Int?> {
        val observation = state.trafficLight ?: return null to null
        val ageMs = sourceWallTimeMs - observation.observedAtMs
        if (ageMs !in 0..validForMs || observation.status != 2) return null to null
        val directionMatches = when (state.maneuver) {
            NavigationManeuver.TURN_LEFT, NavigationManeuver.SLIGHT_LEFT, NavigationManeuver.SHARP_LEFT -> observation.direction == 1
            NavigationManeuver.U_TURN_LEFT -> observation.direction == 3
            NavigationManeuver.TURN_RIGHT, NavigationManeuver.SLIGHT_RIGHT, NavigationManeuver.SHARP_RIGHT -> observation.direction == 2
            else -> false
        }
        val countdown = observation.countdownSeconds?.takeIf { it in 0..3_600 }
        return if (directionMatches && countdown != null) true to countdown else null to null
    }

    fun snapshot(
        state: NavigationState,
        sessionId: String,
        sequence: Long,
        sourceWallTimeMs: Long,
        validForMs: Long,
    ): NavAssistV2Snapshot {
        val maneuver = NavigationMappers.maneuverWireValue(state.maneuver)
        val (turnSignalHold, turnSignalCountdownS) = trafficLightTurnHold(state, sourceWallTimeMs, validForMs)
        val location = location(state)
        val guidance = state.guidanceObservedAtMs?.let { observedAtMs ->
            NavAssistV2Guidance(
                observedAtMs = observedAtMs,
                maneuver = maneuver,
                maneuverDistanceM = state.nextTurnDistanceMeters?.takeIf {
                    it in 0..NavAssistV2Protocol.MAX_MANEUVER_DISTANCE_M
                },
                nextManeuver = state.nextManeuver
                    .takeUnless { it == com.garan.tesnav.model.NavigationManeuver.NONE }
                    ?.let(NavigationMappers::maneuverWireValue),
                nextManeuverDistanceM = state.nextManeuverDistanceMeters?.takeIf {
                    state.nextManeuver != com.garan.tesnav.model.NavigationManeuver.NONE &&
                        it in 0..NavAssistV2Protocol.MAX_MANEUVER_DISTANCE_M
                },
                currentRoad = validRoadName(state.currentRoad),
                nextRoad = validRoadName(state.nextRoad),
                roadClass = NavigationMappers.validRoadClass(state.currentRoadClass),
                roadType = NavigationMappers.validRoadType(state.currentRoadType),
                // Camera enforcement limits are not advisory corner speeds.
                advisorySpeedMps = null,
                parallelRoadStatus = state.parallelRoadStatus.wireValue(),
                elevatedRoadStatus = state.elevatedRoadStatus.wireValue(),
                routeNoticeType = state.routeNotice?.type?.wireValue(),
                routeNoticeDistanceM = state.routeNotice?.distanceMeters?.takeIf {
                    it in 0..NavAssistV2Protocol.MAX_MANEUVER_DISTANCE_M
                },
                routeNoticeObservedAtMs = state.routeNotice?.observedAtMs?.takeIf { it > 0L },
                turnSignalHold = turnSignalHold,
                turnSignalCountdownS = turnSignalCountdownS,
            )
        }
        val routeActive = state.navAssistControlAllowed && state.routePlanned &&
            !state.routeRecalculating &&
            state.navigationMode == NavigationMode.REALTIME &&
            state.routeMatched == true &&
            location != null &&
            guidance != null
        val stepIndex = state.guidanceStepIndex ?: state.currentStepIndex
        // Lane selection is an action even when the SDK has no turn icon. Only
        // route-bound exported lane facts may provide this stable step identity.
        val routeLanes = if (state.lanesPathId != null && state.lanesPathId == state.acceptedPathId &&
            state.guidancePathId == state.acceptedPathId && !state.routeRecalculating) lanes(state) else null
        val eventAction = when {
            maneuver != "none" && maneuver != "unknown" -> maneuver
            maneuver == "none" && routeLanes?.items?.any { it.recommended || it.routeAvoid } == true -> "lane_guidance"
            else -> null
        }
        val maneuverEventId = if (
            routeActive && stepIndex != null && eventAction != null
        ) {
            StableManeuverEventId.fromKey("$sessionId:${state.routeRevision}:$stepIndex:$eventAction")
        } else {
            0L
        }

        return NavAssistV2Snapshot(
            schemaVersion = NavAssistV2Protocol.SCHEMA_VERSION,
            messageType = NavAssistV2Protocol.MESSAGE_TYPE,
            sessionId = sessionId,
            sequence = sequence,
            routeRevision = state.routeRevision.coerceAtLeast(0L),
            maneuverEventId = maneuverEventId,
            sourcePlatform = NavAssistV2Protocol.SOURCE_PLATFORM,
            sourceWallTimeMs = sourceWallTimeMs,
            validForMs = validForMs,
            navigationMode = navigationMode(state),
            routeActive = routeActive,
            routeMatched = if (location != null) state.routeMatched else null,
            gpsWeak = state.gpsSignalWeak,
            coordinateSystem = NavAssistV2Protocol.COORDINATE_SYSTEM,
            location = location,
            guidance = guidance,
            // Only lane callbacks observed after keyed guidance confirmed the
            // currently installed path can become C3 route facts.
            lanes = routeLanes,
        )
    }

    fun navigationMode(state: NavigationState): String = if (state.routeRecalculating) {
        "recalculating"
    } else {
        when (state.navigationMode) {
            NavigationMode.IDLE -> "idle"
            NavigationMode.ROUTE_PLANNED -> "route_planned"
            NavigationMode.REALTIME -> "realtime"
            NavigationMode.SIMULATION -> "simulation"
            NavigationMode.ARRIVED -> "arrived"
        }
    }

    private fun location(state: NavigationState): NavAssistV2Location? {
        val observedAtMs = state.locationObservedAtMs ?: return null
        val latitude = state.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val longitude = state.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        val accuracyM = state.accuracy?.takeIf {
            it.isFinite() && it in 0f..NavAssistV2Protocol.MAX_LOCATION_ACCURACY_M
        } ?: return null
        val bearingDeg = state.bearing?.takeIf { it.isFinite() && it in 0f..360f } ?: return null
        val speedKph = state.speedKph.takeIf {
            it.isFinite() && it in 0f..NavAssistV2Protocol.MAX_SPEED_KPH
        } ?: return null
        return NavAssistV2Location(
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            bearingDeg = bearingDeg,
            speedKph = speedKph,
            observedAtMs = observedAtMs,
            currentStepIndex = state.currentStepIndex?.takeIf { it >= 0 },
            currentLinkIndex = state.currentLinkIndex?.takeIf { it >= 0 },
            currentPointIndex = state.currentPointIndex?.takeIf { it >= 0 },
        )
    }

    private fun lanes(state: NavigationState): NavAssistV2Lanes? =
        state.lanesObservedAtMs?.let { observedAtMs ->
            NavAssistV2Lanes(
                observedAtMs = observedAtMs,
                items = state.lanes
                    .asSequence()
                    .filter { it.index in 0..31 }
                    .sortedBy { it.index }
                    .take(16)
                    .map { lane ->
                        val allowedActions = NavigationMappers.navAssistV2LaneActions(lane.rawLaneType)
                        val recommendedActions = lane.rawRecommendedLaneType
                            ?.let(NavigationMappers::navAssistV2LaneActions)
                            .orEmpty()
                        NavAssistV2Lane(
                            index = lane.index,
                            allowedActions = allowedActions.map { it.name }.distinct().take(16),
                            recommended = recommendedActions.isNotEmpty(),
                            recommendedActions = recommendedActions.map { it.name }.distinct().take(16),
                            // AMap's F/255-style foreground value means this lane is not selected
                            // for the current route. It is not a legal or physical crossing veto.
                            routeAvoid = lane.prohibited,
                        )
                    }
                    .toList(),
            )
        }

    private fun validRoadName(value: String?): String? = value?.takeIf {
        it.isNotBlank() && it.length <= NavAssistV2Protocol.MAX_ROAD_NAME_LENGTH
    }

    private fun com.garan.tesnav.model.RoadLayerStatus.wireValue(): String? = when (this) {
        com.garan.tesnav.model.RoadLayerStatus.UNKNOWN -> null
        com.garan.tesnav.model.RoadLayerStatus.MAIN -> "main"
        com.garan.tesnav.model.RoadLayerStatus.SIDE -> "side"
    }

    private fun com.garan.tesnav.model.RouteNoticeType.wireValue(): String? = when (this) {
        com.garan.tesnav.model.RouteNoticeType.NONE -> null
        com.garan.tesnav.model.RouteNoticeType.RESTRICTED_AREA -> "restricted_area"
        com.garan.tesnav.model.RouteNoticeType.FORBIDDEN_AREA -> "forbidden_area"
        com.garan.tesnav.model.RouteNoticeType.ROAD_CLOSED -> "road_closed"
        com.garan.tesnav.model.RouteNoticeType.CONGESTION -> "congestion"
        com.garan.tesnav.model.RouteNoticeType.DISPATCH -> "dispatch"
        com.garan.tesnav.model.RouteNoticeType.ROUTE_CHANGED -> "route_changed"
        com.garan.tesnav.model.RouteNoticeType.GPS_WEAK -> "gps_weak"
        com.garan.tesnav.model.RouteNoticeType.UNKNOWN -> "unknown"
    }
}

/** Stable positive int64 ID; zero is reserved for "no active maneuver event". */
object StableManeuverEventId {
    fun fromKey(key: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8))
        var value = 0L
        for (index in 0 until Long.SIZE_BYTES) {
            value = (value shl Byte.SIZE_BITS) or (digest[index].toLong() and 0xffL)
        }
        val nonNegative = value and Long.MAX_VALUE
        return if (nonNegative == 0L) 1L else nonNegative
    }
}

/** Deterministic, compact JSON: object keys are recursively sorted and nulls are omitted. */
object CanonicalJson {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun encode(value: Any): String = gson.toJson(sort(gson.toJsonTree(value)))

    private fun sort(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().apply {
            element.asJsonObject.entrySet()
                .sortedBy { it.key }
                .forEach { (key, value) ->
                    // Older C3 receivers omit this optional false default.
                    if (key != "routeAvoid" || value != com.google.gson.JsonPrimitive(false)) add(key, sort(value))
                }
        }
        element.isJsonArray -> JsonArray().apply {
            element.asJsonArray.forEach { add(sort(it)) }
        }
        else -> element
    }
}

object HmacSha256 {
    fun signLowerHex(body: String, token: String): String {
        require(token.isNotEmpty()) { "HMAC token must not be empty" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)).joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
