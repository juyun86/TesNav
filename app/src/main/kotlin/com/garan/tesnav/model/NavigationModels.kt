package com.garan.tesnav.model

import kotlin.jvm.Transient

enum class NavigationMode { IDLE, ROUTE_PLANNED, SIMULATION, REALTIME, ARRIVED }
enum class TrafficStatus { UNKNOWN, SMOOTH, SLOW, CONGESTED, SEVERELY_CONGESTED }
enum class WarningLevel { NONE, WARNING, CRITICAL }
enum class RoadLayerStatus { UNKNOWN, MAIN, SIDE }
enum class RouteNoticeType {
    NONE,
    RESTRICTED_AREA,
    FORBIDDEN_AREA,
    ROAD_CLOSED,
    CONGESTION,
    DISPATCH,
    ROUTE_CHANGED,
    GPS_WEAK,
    UNKNOWN,
}
enum class LaneAction { STRAIGHT, LEFT, RIGHT, U_TURN, LEFT_U_TURN, RIGHT_U_TURN, BUS, VARIABLE, DEDICATED, TIDAL, UNKNOWN }
enum class CameraType { SPEED, SURVEILLANCE, TRAFFIC_LIGHT, VIOLATION, BUS_LANE, EMERGENCY, BICYCLE, INTERVAL_START, INTERVAL_END, FLOW_SPEED, ETC, UNKNOWN }
enum class NavigationManeuver {
    NONE,
    STRAIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    U_TURN_LEFT,
    U_TURN_RIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    MERGE_LEFT,
    MERGE_RIGHT,
    EXIT_LEFT,
    EXIT_RIGHT,
    RAMP_LEFT,
    RAMP_RIGHT,
    ROUNDABOUT,
    DESTINATION,
    UNKNOWN,
}

data class GeoPoint(val latitude: Double, val longitude: Double)

data class RouteChoice(
    val routeId: Int,
    val pathId: Long,
    val label: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val tollYuan: Int,
    val trafficLightCount: Int,
    val selected: Boolean,
)

data class LaneState(
    val index: Int,
    val allowedActions: List<LaneAction> = emptyList(),
    val recommended: Boolean = false,
    val rawLaneType: Int = -1,
    val recommendedActions: List<LaneAction> = emptyList(),
    @Transient val rawRecommendedLaneType: Int? = null,
    @Transient val prohibited: Boolean = false,
)

data class CameraState(
    val type: CameraType = CameraType.UNKNOWN,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val distanceMeters: Int? = null,
    val limitSpeedKph: Int? = null,
    val intervalLengthMeters: Int? = null,
    val intervalRemainDistanceMeters: Int? = null,
    val averageSpeedKph: Int? = null,
    val reasonableSpeedKph: Int? = null,
)

data class RouteNoticeState(
    val type: RouteNoticeType = RouteNoticeType.NONE,
    val distanceMeters: Int? = null,
    val roadName: String? = null,
    val reason: String? = null,
    val subtitle: String? = null,
    val success: Boolean = false,
    val observedAtMs: Long = 0L,
)

data class TrafficLightObservation(
    val status: Int,
    val direction: Int?,
    val countdownSeconds: Int?,
    val observedAtMs: Long,
)

/** Schema-compatible latest-state snapshot consumed by Comma. */
data class NavigationState(
    val timestamp: Long = System.currentTimeMillis(),
    val navigationMode: NavigationMode = NavigationMode.IDLE,
    val simulationPaused: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val bearing: Float? = null,
    val locationTime: Long? = null,
    val speedKph: Float = 0f,
    val currentRoad: String? = null,
    val nextRoad: String? = null,
    val nextTurnType: Int? = null,
    val nextTurnDistanceMeters: Int? = null,
    val routeRemainDistanceMeters: Int? = null,
    val routeRemainTimeSeconds: Int? = null,
    val remainingTrafficLightCount: Int? = null,
    val routeTrafficLights: List<GeoPoint> = emptyList(),
    val trafficStatus: TrafficStatus = TrafficStatus.UNKNOWN,
    val lanes: List<LaneState> = emptyList(),
    val cameras: List<CameraState> = emptyList(),
    val speedLimitKph: Int? = null,
    val isOverspeed: Boolean = false,
    val hasSpeedCameraAhead: Boolean = false,
    val warningLevel: WarningLevel = WarningLevel.NONE,
    val routePlanned: Boolean = false,
    val gpsSignalWeak: Boolean = false,
    val errorMessage: String? = null,
    val startedFromTeslaSync: Boolean = false,

    // NavAssist v2-only observations. They are transient so the legacy v1 Gson
    // payload remains schema compatible with existing receivers.
    @Transient val navigationProvider: String = "AMAP_API",
    @Transient val locationObservedAtMs: Long? = null,
    @Transient val locationReceivedElapsedMs: Long? = null,
    @Transient val locationSourceStatus: String = "none",
    @Transient val guidanceReceivedElapsedMs: Long? = null,
    // Matching SDK callback delivery, separate from changed guidance/progress above.
    @Transient val guidanceCallbackElapsedMs: Long? = null,
    @Transient val acceptedPathId: Long? = null,
    @Transient val guidancePathId: Long? = null,
    @Transient val lanesPathId: Long? = null,
    @Transient val unkeyedRouteFactsConfirmed: Boolean = false,
    @Transient val navAssistControlAllowed: Boolean = false,
    @Transient val navAssistSourceStatus: String = "unconfirmed",
    @Transient val guidanceObservedAtMs: Long? = null,
    @Transient val lanesObservedAtMs: Long? = null,
    @Transient val autoDisplayLanes: List<LaneState> = emptyList(),
    @Transient val autoDisplayLanesPathId: Long? = null,
    @Transient val routeObservedAtMs: Long? = null,
    @Transient val currentStepIndex: Int? = null,
    @Transient val currentLinkIndex: Int? = null,
    @Transient val currentPointIndex: Int? = null,
    @Transient val routeMatched: Boolean? = null,
    @Transient val maneuver: NavigationManeuver = NavigationManeuver.NONE,
    @Transient val nextManeuver: NavigationManeuver = NavigationManeuver.NONE,
    @Transient val nextManeuverDistanceMeters: Int? = null,
    @Transient val guidanceStepIndex: Int? = null,
    @Transient val currentRoadClass: Int? = null,
    @Transient val currentRoadType: Int? = null,
    @Transient val parallelRoadStatus: RoadLayerStatus = RoadLayerStatus.UNKNOWN,
    @Transient val elevatedRoadStatus: RoadLayerStatus = RoadLayerStatus.UNKNOWN,
    @Transient val routeNotice: RouteNoticeState? = null,
    @Transient val trafficLight: TrafficLightObservation? = null,
    @Transient val routeRevision: Long = 0L,
    @Transient val routeRecalculating: Boolean = false,
    @Transient val routeChoices: List<RouteChoice> = emptyList(),
    @Transient val selectedRouteId: Int? = null,
    @Transient val speechEnabled: Boolean = true,
    @Transient val laneCallbackCount: Int = 0,
    @Transient val laneLastEvent: String? = null,
)
