package com.garan.tesnav.export

import com.garan.tesnav.model.GeoPoint

internal data class NavAssistModelStep(
    val start: Int,
    val end: Int,
    val icon: Int,
    val first: List<Double>,
    val last: List<Double>,
    val roadType: Int? = null,
)

/** Original API geometry; independent of the simplified legacy display route. */
internal data class NavAssistModelRoute(
    val pathId: Long,
    val routeRevision: Long,
    val points: List<GeoPoint>,
    val amapSteps: List<NavAssistModelStep> = emptyList(),
) {
    fun body(snapshot: NavAssistV2Snapshot, sourceWallTimeMs: Long, includeSteps: Boolean = false): String? {
        if (!snapshot.routeActive || snapshot.routeMatched != true || snapshot.gpsWeak ||
            snapshot.navigationMode != "realtime" || snapshot.coordinateSystem != "gcj02" ||
            snapshot.routeRevision != routeRevision || points.size !in 2..MAX_POINTS ||
            points.any { !it.latitude.isFinite() || !it.longitude.isFinite() ||
                it.latitude !in -90.0..90.0 || it.longitude !in -180.0..180.0 }) return null
        val values = mutableMapOf<String, Any>(
            "schemaVersion" to 3, "messageType" to "navigation_route",
            "sessionId" to snapshot.sessionId, "sequence" to snapshot.sequence,
            "routeRevision" to routeRevision, "sourceWallTimeMs" to sourceWallTimeMs,
            "coordinateSystem" to snapshot.coordinateSystem, "points" to points,
        )
        if (includeSteps) {
            if (amapSteps.isEmpty()) return null
            values["amapSteps"] = amapSteps
        }
        val body = CanonicalJson.encode(values)
        return body.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
    }

    companion object {
        const val PATH = "/v3/route"
        const val MAX_POINTS = 20_000
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
