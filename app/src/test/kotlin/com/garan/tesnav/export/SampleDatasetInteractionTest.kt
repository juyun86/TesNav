package com.garan.tesnav.export

import com.garan.tesnav.model.*
import com.garan.tesnav.util.NavigationMappers
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Re-serializes decoded C3 observations; this is not a replay of raw Android broadcasts. */
class SampleDatasetInteractionTest {
    @Test fun `recorded observations use the current App snapshot serializer`() {
        val input = System.getenv("NAVASSIST_SAMPLE_INPUT")
        val output = System.getenv("NAVASSIST_SAMPLE_OUTPUT")
        // Ordinary unit-test runs do not need the separately retained road dataset.
        assumeTrue("No external NavAssist sample input configured", !input.isNullOrBlank())
        require(!output.isNullOrBlank()) { "NAVASSIST_SAMPLE_OUTPUT must accompany the sample input" }
        val sessions = mutableMapOf<String, NavAssistV2Session>()
        var packetCount = 0
        val outputFile = File(output!!).apply { parentFile?.mkdirs() }
        JsonReader(File(input!!).bufferedReader(Charsets.UTF_8)).use { reader ->
            outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("[")
                reader.beginArray()
                while (reader.hasNext()) {
                    val item = JsonParser.parseReader(reader).asJsonObject
                    val nav = item.getAsJsonObject("nav")
                    // C3's empty/unowned initial state has no App session to recover.
                    val sessionId = nav.string("sessionId").takeUnless { it.isNullOrBlank() } ?: "sample-unowned"
                    val session = sessions.getOrPut(sessionId) { NavAssistV2Session(sessionId) }
                    val state = reconstructState(nav)
                    val snapshot = session.nextSnapshot(state, nav.long("sourceWallTimeMs") ?: 0L)
                    val body = CanonicalJson.encode(snapshot)
                    assertEquals(snapshot.sequence, JsonParser.parseString(body).asJsonObject["sequence"].asLong)
                    if (snapshot.routeActive) assertTrue(snapshot.maneuverEventId != 0L ||
                        state.maneuver in listOf(NavigationManeuver.NONE, NavigationManeuver.UNKNOWN))
                    if (packetCount > 0) writer.write(",")
                    writer.write(CanonicalJson.encode(mapOf("id" to item["id"].asString, "body" to body)))
                    packetCount += 1
                }
                reader.endArray()
                writer.write("]")
            }
        }
        assertTrue("The configured sample dataset is empty", packetCount > 0)
    }

    private fun reconstructState(nav: JsonObject): NavigationState {
        val active = nav.boolean("routeActive") ?: false
        val mode = when (nav.string("mode")) {
            "realtime" -> NavigationMode.REALTIME
            "routePlanned", "route_planned" -> NavigationMode.ROUTE_PLANNED
            "simulation" -> NavigationMode.SIMULATION
            "arrived" -> NavigationMode.ARRIVED
            else -> NavigationMode.IDLE
        }
        val revision = nav.long("routeRevision") ?: 0L
        // acceptedPathId and callback elapsed times were not retained by C3 diagnostics.
        // This synthetic common path tests the serializer, not original route qualification.
        val syntheticPath = revision.coerceAtLeast(1L)
        val maneuverValue = maneuver(nav.string("maneuver"))
        val countdown = nav.int("turnSignalCountdownS")?.takeIf { it >= 0 }
        val turnHold = if (nav.boolean("turnSignalHold") == true && countdown != null) {
            val direction = when (maneuverValue) {
                NavigationManeuver.TURN_LEFT, NavigationManeuver.SLIGHT_LEFT, NavigationManeuver.SHARP_LEFT -> 1
                NavigationManeuver.TURN_RIGHT, NavigationManeuver.SLIGHT_RIGHT, NavigationManeuver.SHARP_RIGHT -> 2
                NavigationManeuver.U_TURN_LEFT -> 3
                else -> null
            }
            // The record contains the derived hold/countdown, not a raw 60073 observation.
            direction?.let { TrafficLightObservation(2, it, countdown, nav.long("sourceWallTimeMs") ?: 0L) }
        } else null
        val noticeType = nav.string("routeNoticeType")?.let { enumKey(it) }
            ?.let { key -> RouteNoticeType.entries.firstOrNull { it.name == key } }
            ?.takeUnless { it == RouteNoticeType.NONE }
        val lanesObserved = nav.long("laneGuidanceObservedAtMs")?.takeIf { it > 0L }
        val lanes = nav.getAsJsonArray("lanes")?.map { laneElement ->
            val lane = laneElement.asJsonObject
            val allowedMask = lane.int("allowedActions") ?: 0
            val recommendedMask = lane.int("recommendedActions") ?: 0
            val recommended = lane.boolean("recommended") ?: false
            require(recommended || recommendedMask == 0) { "Unselected lane has recommended actions" }
            require(!recommended || recommendedMask != 0) { "Recommendation has no reconstructable action" }
            // Raw AMap lane codes were not recorded. Find an equivalent current mapper code.
            LaneState(index = lane.int("index") ?: -1,
                rawLaneType = laneCode(allowedMask),
                rawRecommendedLaneType = if (recommended) laneCode(recommendedMask) else null,
                prohibited = lane.boolean("routeAvoid") ?: false)
        }.orEmpty()
        return NavigationState(
            timestamp = nav.long("sourceWallTimeMs") ?: 0L,
            navigationMode = mode,
            latitude = nav.double("latitude"), longitude = nav.double("longitude"),
            accuracy = nav.float("locationAccuracyM"), bearing = nav.float("bearingDeg"),
            speedKph = nav.float("speedKph") ?: 0f,
            locationObservedAtMs = nav.long("locationObservedAtMs")?.takeIf { it > 0L },
            guidanceObservedAtMs = nav.long("guidanceObservedAtMs")?.takeIf { it > 0L },
            routePlanned = active, navAssistControlAllowed = active,
            routeMatched = nav.boolean("routeMatched"), gpsSignalWeak = nav.boolean("gpsWeak") ?: false,
            acceptedPathId = syntheticPath, guidancePathId = syntheticPath,
            lanesPathId = syntheticPath.takeIf { lanesObserved != null },
            lanesObservedAtMs = lanesObserved, lanes = lanes,
            currentStepIndex = nav.int("currentStepIndex")?.takeIf { it >= 0 },
            guidanceStepIndex = nav.int("currentStepIndex")?.takeIf { it >= 0 },
            currentLinkIndex = nav.int("currentLinkIndex")?.takeIf { it >= 0 },
            currentPointIndex = nav.int("currentPointIndex")?.takeIf { it >= 0 },
            routeRevision = revision, routeRecalculating = nav.string("mode") == "recalculating",
            maneuver = maneuverValue, nextTurnDistanceMeters = nav.int("maneuverDistanceM"),
            nextManeuver = maneuver(nav.string("nextManeuver")),
            nextManeuverDistanceMeters = nav.int("nextManeuverDistanceM"),
            currentRoad = nav.string("currentRoad"), nextRoad = nav.string("nextRoad"),
            currentRoadClass = nav.int("roadClass"), currentRoadType = nav.int("roadType"),
            parallelRoadStatus = roadLayer(nav.string("parallelRoadStatus")),
            elevatedRoadStatus = roadLayer(nav.string("elevatedRoadStatus")),
            routeNotice = noticeType?.let { RouteNoticeState(type = it,
                distanceMeters = nav.int("routeNoticeDistanceM"),
                observedAtMs = nav.long("routeNoticeObservedAtMs") ?: 0L) },
            trafficLight = turnHold,
        )
    }

    private fun enumKey(value: String): String = value.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
    private fun maneuver(value: String?): NavigationManeuver = value?.let { enumKey(it) }
        ?.let { key -> NavigationManeuver.entries.firstOrNull { it.name == key } } ?: NavigationManeuver.NONE
    private fun roadLayer(value: String?): RoadLayerStatus = when (value) {
        "main" -> RoadLayerStatus.MAIN
        "side" -> RoadLayerStatus.SIDE
        else -> RoadLayerStatus.UNKNOWN
    }
    private val actionBits = mapOf(LaneAction.STRAIGHT to 1, LaneAction.LEFT to 2, LaneAction.RIGHT to 4,
        LaneAction.U_TURN to 8, LaneAction.LEFT_U_TURN to 8, LaneAction.RIGHT_U_TURN to 16,
        LaneAction.BUS to 32, LaneAction.VARIABLE to 64, LaneAction.DEDICATED to 128,
        LaneAction.TIDAL to 256, LaneAction.UNKNOWN to 32768)
    private fun laneCode(mask: Int): Int = (0..255).firstOrNull { raw ->
        NavigationMappers.navAssistV2LaneActions(raw).fold(0) { bits, action -> bits or actionBits.getValue(action) } == mask
    } ?: error("Recorded lane mask $mask cannot be represented by the current App mapper")

    private fun JsonObject.value(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }
    private fun JsonObject.string(key: String): String? = value(key)?.asString
    private fun JsonObject.long(key: String): Long? = value(key)?.asLong
    private fun JsonObject.int(key: String): Int? = value(key)?.asDouble?.toInt()
    private fun JsonObject.double(key: String): Double? = value(key)?.asDouble
    private fun JsonObject.float(key: String): Float? = value(key)?.asFloat
    private fun JsonObject.boolean(key: String): Boolean? = value(key)?.asBoolean
}
