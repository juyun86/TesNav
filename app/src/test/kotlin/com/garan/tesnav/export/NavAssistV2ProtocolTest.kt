package com.garan.tesnav.export

import com.garan.tesnav.model.LaneAction
import com.garan.tesnav.model.LaneState
import com.garan.tesnav.model.NavigationManeuver
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.RoadLayerStatus
import com.garan.tesnav.model.RouteNoticeState
import com.garan.tesnav.model.RouteNoticeType
import com.garan.tesnav.model.TrafficLightObservation
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavAssistV2ProtocolTest {
    @Test fun `route bound lane selection has a stable event without a turn icon`() {
        val base = activeState().copy(maneuver = NavigationManeuver.NONE)
        val first = NavAssistV2Mapper.snapshot(base, "lane-route", 1, 4_000, 500)
        val repeated = NavAssistV2Mapper.snapshot(base, "lane-route", 2, 4_100, 500)
        assertTrue(first.maneuverEventId > 0L)
        assertEquals(first.maneuverEventId, repeated.maneuverEventId)
        assertEquals("none", first.guidance?.maneuver)
        for (unqualified in listOf(base.copy(lanes = emptyList()), base.copy(lanesPathId = null),
            base.copy(navAssistControlAllowed = false), base.copy(maneuver = NavigationManeuver.UNKNOWN))) {
            assertEquals(0L, NavAssistV2Mapper.snapshot(unqualified, "lane-route", 3, 4_200, 500).maneuverEventId)
        }
    }

    @Test
    fun `fresh matching red countdown asks C3 to hold the current turn signal`() {
        val snapshot = NavAssistV2Mapper.snapshot(
            activeState().copy(trafficLight = TrafficLightObservation(2, 2, 37, 3_900L)),
            "test-session", 1L, 4_000L, 500L,
        )

        assertEquals(true, snapshot.guidance?.turnSignalHold)
        assertEquals(37, snapshot.guidance?.turnSignalCountdownS)
    }

    @Test
    fun `display-only stale green or wrong-direction countdown cannot hold a turn signal`() {
        val base = activeState()
        for (observation in listOf(
            TrafficLightObservation(0, null, 10, 3_900L),
            TrafficLightObservation(3, 2, 10, 3_900L),
            TrafficLightObservation(2, 1, 10, 3_900L),
            TrafficLightObservation(2, 2, 10, 3_499L),
        )) {
            val guidance = NavAssistV2Mapper.snapshot(
                base.copy(trafficLight = observation), "test-session", 1L, 4_000L, 500L,
            ).guidance
            assertNull(guidance?.turnSignalHold)
            assertNull(guidance?.turnSignalCountdownS)
        }
    }

    @Test
    fun `unconfirmed source exports inactive without replacing the route event identity`() {
        val session = NavAssistV2Session(sessionId = "guarded-session")
        val ready = activeState()
        val first = session.nextSnapshot(ready, 4_000)
        val blocked = session.nextSnapshot(ready.copy(navAssistControlAllowed = false), 4_200)
        val rearmed = session.nextSnapshot(ready, 4_400)
        assertTrue(first.routeActive)
        assertFalse(blocked.routeActive)
        assertEquals(0L, blocked.maneuverEventId)
        assertEquals(first.sessionId, blocked.sessionId)
        assertEquals(first.routeRevision, blocked.routeRevision)
        assertEquals(first.maneuverEventId, rearmed.maneuverEventId)
        assertEquals(3L, rearmed.sequence)
    }

    @Test fun `source switch resets sequence and route revision domain without reusing events`() {
        val session = NavAssistV2Session("api-source")
        val api = session.nextSnapshot(activeState().copy(routeRevision = 5), 4_000)
        session.switchSource()
        val auto = session.nextSnapshot(activeState().copy(routeRevision = 1), 4_100)
        assertTrue(api.sessionId != auto.sessionId)
        assertEquals(1L, auto.sequence)
        assertEquals(1L, auto.routeRevision)
        assertTrue(api.maneuverEventId != auto.maneuverEventId)
        val sameSource = session.nextSnapshot(activeState().copy(routeRevision = 1), 4_200)
        assertEquals(auto.sessionId, sameSource.sessionId)
        assertEquals(auto.maneuverEventId, sameSource.maneuverEventId)
        session.switchSource()
        val back = session.nextSnapshot(activeState().copy(routeRevision = 5), 4_300)
        assertTrue(back.sessionId != api.sessionId && back.sessionId != auto.sessionId)
    }

    @Test
    fun `whole snapshots match shared contract fixtures`() {
        val states = linkedMapOf(
            "idle" to NavigationState(),
            "planned" to activeState().copy(navigationMode = NavigationMode.ROUTE_PLANNED),
            "realtime" to activeState(),
            "simulation" to activeState().copy(navigationMode = NavigationMode.SIMULATION),
            "recalculating" to activeState().copy(routeRecalculating = true),
            "arrived" to activeState().copy(navigationMode = NavigationMode.ARRIVED),
            "unmatched" to activeState().copy(routeMatched = false),
        )
        val snapshots = states.mapValues { (_, state) ->
            NavAssistV2Mapper.snapshot(state, "contract-session", 9L, 4_000L, 500L)
        }
        val expected = requireNotNull(javaClass.getResourceAsStream("/navassist/android-snapshots.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertEquals(expected.trim(), CanonicalJson.encode(snapshots))
    }

    @Test
    fun `default snapshot lifetime tolerates normal LAN jitter`() {
        assertEquals(1_200L, NavAssistV2Protocol.DEFAULT_VALID_FOR_MS)
        assertEquals(1_200L, NavAssistV2ExportConfig(baseUrl = "").validForMs)
    }

    @Test
    fun `canonical JSON recursively sorts keys and omits nulls`() {
        val value = CanonicalFixture(
            z = 2,
            nested = NestedFixture(z = 4, a = 3),
            a = 1,
            absent = null,
        )

        assertEquals(
            "{\"a\":1,\"nested\":{\"a\":3,\"z\":4},\"z\":2}",
            CanonicalJson.encode(value),
        )
    }

    @Test
    fun `HMAC signs the exact UTF-8 body as lowercase hex`() {
        val signature = HmacSha256.signLowerHex("{\"a\":1,\"b\":\"two\"}", "secret")

        assertEquals(
            "0de740ad4f63bf5d8d217729e3ff9dc3aadda131e050cdfa861987c4e2f14666",
            signature,
        )
        assertEquals(signature.lowercase(), signature)
    }

    @Test
    fun `session sequence increments while maneuver event remains stable`() {
        val session = NavAssistV2Session(sessionId = "test-session", validForMs = 500L)
        val state = activeState()

        val first = session.nextSnapshot(state, sourceWallTimeMs = 10_000L)
        val second = session.nextSnapshot(state, sourceWallTimeMs = 10_200L)

        assertEquals(1L, first.sequence)
        assertEquals(2L, second.sequence)
        assertTrue(first.maneuverEventId > 0L)
        assertEquals(first.maneuverEventId, second.maneuverEventId)
        assertEquals(10_000L, first.sourceWallTimeMs)
        assertEquals(10_200L, second.sourceWallTimeMs)
    }

    @Test
    fun `snapshot preserves independent observation times and maps fields`() {
        val snapshot = NavAssistV2Mapper.snapshot(
            state = activeState(),
            sessionId = "test-session",
            sequence = 9L,
            sourceWallTimeMs = 4_000L,
            validForMs = 500L,
        )

        assertEquals(3, snapshot.schemaVersion)
        assertEquals("navigation_snapshot", snapshot.messageType)
        assertEquals("android", snapshot.sourcePlatform)
        assertEquals("gcj02", snapshot.coordinateSystem)
        assertEquals("realtime", snapshot.navigationMode)
        assertTrue(snapshot.routeActive)
        assertEquals(true, snapshot.routeMatched)
        assertEquals(1_000L, snapshot.location?.observedAtMs)
        assertEquals(2_000L, snapshot.guidance?.observedAtMs)
        assertEquals(3_000L, snapshot.lanes?.observedAtMs)
        assertEquals(4, snapshot.location?.currentStepIndex)
        assertEquals("turn_right", snapshot.guidance?.maneuver)
        assertEquals("turn_left", snapshot.guidance?.nextManeuver)
        assertEquals(480, snapshot.guidance?.nextManeuverDistanceM)
        assertEquals(6, snapshot.guidance?.roadType)
        assertEquals("main", snapshot.guidance?.parallelRoadStatus)
        assertEquals("side", snapshot.guidance?.elevatedRoadStatus)
        assertEquals("road_closed", snapshot.guidance?.routeNoticeType)
        assertEquals(350, snapshot.guidance?.routeNoticeDistanceM)
        assertNull(snapshot.guidance?.advisorySpeedMps)
        assertEquals(listOf(0, 1), snapshot.lanes?.items?.map { it.index })
        assertEquals(listOf("STRAIGHT", "RIGHT"), snapshot.lanes?.items?.last()?.allowedActions)
        assertTrue(snapshot.lanes?.items?.first()?.routeAvoid == true)

        val body = CanonicalJson.encode(snapshot)
        assertFalse(body.contains("advisorySpeedMps"))
        assertTrue(body.contains("nextManeuver"))
    }

    @Test
    fun `unkeyed lane callback stays in App state without entering C3 snapshot`() {
        val state = activeState().copy(lanesPathId = null)
        val snapshot = NavAssistV2Mapper.snapshot(state, "test-session", 9L, 4_000L, 500L)
        assertTrue(state.lanes.isNotEmpty())
        assertTrue(snapshot.routeActive)
        assertNull(snapshot.lanes)
    }

    @Test
    fun `lane callback is exported only for its confirmed current path`() {
        val bound = activeState().copy(acceptedPathId = 42L, guidancePathId = 42L, lanesPathId = 42L)
        assertEquals(2, NavAssistV2Mapper.snapshot(bound, "test-session", 9L, 4_000L, 500L).lanes?.items?.size)
        assertNull(NavAssistV2Mapper.snapshot(bound.copy(acceptedPathId = 43L), "test-session", 9L, 4_000L, 500L).lanes)
        assertNull(NavAssistV2Mapper.snapshot(bound.copy(routeRecalculating = true), "test-session", 9L, 4_000L, 500L).lanes)
    }

    @Test
    fun `recalculating route is fail closed and has no maneuver event`() {
        val snapshot = NavAssistV2Mapper.snapshot(
            state = activeState().copy(routeRecalculating = true),
            sessionId = "test-session",
            sequence = 1L,
            sourceWallTimeMs = 4_000L,
            validForMs = 500L,
        )

        assertEquals("recalculating", snapshot.navigationMode)
        assertFalse(snapshot.routeActive)
        assertEquals(0L, snapshot.maneuverEventId)
    }

    @Test
    fun `simulation is visible as a mode but never exported as a control-active route`() {
        val snapshot = NavAssistV2Mapper.snapshot(
            state = activeState().copy(navigationMode = NavigationMode.SIMULATION),
            sessionId = "test-session",
            sequence = 1L,
            sourceWallTimeMs = 4_000L,
            validForMs = 500L,
        )

        assertEquals("simulation", snapshot.navigationMode)
        assertFalse(snapshot.routeActive)
        assertEquals(0L, snapshot.maneuverEventId)
    }

    @Test
    fun `active route requires matched complete location and guidance`() {
        val incompleteLocation = NavAssistV2Mapper.snapshot(
            state = activeState().copy(latitude = null),
            sessionId = "test-session",
            sequence = 1L,
            sourceWallTimeMs = 4_000L,
            validForMs = 500L,
        )
        val unmatched = NavAssistV2Mapper.snapshot(
            state = activeState().copy(routeMatched = false),
            sessionId = "test-session",
            sequence = 2L,
            sourceWallTimeMs = 4_200L,
            validForMs = 500L,
        )

        assertNull(incompleteLocation.location)
        assertNull(incompleteLocation.routeMatched)
        assertFalse(incompleteLocation.routeActive)
        assertEquals(0L, incompleteLocation.maneuverEventId)
        assertFalse(unmatched.routeActive)
        assertEquals(0L, unmatched.maneuverEventId)
    }

    @Test
    fun `GPS weak flag is diagnostic and does not deactivate matched realtime route`() {
        val snapshot = NavAssistV2Mapper.snapshot(
            state = activeState().copy(gpsSignalWeak = true),
            sessionId = "test-session",
            sequence = 1L,
            sourceWallTimeMs = 4_000L,
            validForMs = 500L,
        )

        assertTrue(snapshot.gpsWeak)
        assertTrue(snapshot.routeActive)
        assertTrue(snapshot.maneuverEventId > 0L)
    }

    @Test
    fun `out of contract numeric and text fields are omitted or invalidate location`() {
        val snapshot = NavAssistV2Mapper.snapshot(
            state = activeState().copy(
                accuracy = 201f,
                nextTurnDistanceMeters = 100_001,
                currentRoad = "路".repeat(257),
            ),
            sessionId = "test-session",
            sequence = 1L,
            sourceWallTimeMs = 4_000L,
            validForMs = 500L,
        )

        assertNull(snapshot.location)
        assertFalse(snapshot.routeActive)
        assertNull(snapshot.guidance?.maneuverDistanceM)
        assertNull(snapshot.guidance?.currentRoad)
    }

    @Test
    fun `v2-only NavigationState fields do not change legacy v1 JSON`() {
        val json = Gson().toJson(NavigationWireEnvelope(state = activeState()))

        assertTrue(json.contains("\"schemaVersion\":1"))
        assertFalse(json.contains("locationObservedAtMs"))
        assertFalse(json.contains("guidanceObservedAtMs"))
        assertFalse(json.contains("routeRevision"))
        assertFalse(json.contains("maneuver"))
        assertFalse(json.contains("rawRecommendedLaneType"))
    }

    @Test
    fun `v3 exporter supports automatic discovery without a manual token`() {
        assertTrue(NavAssistV2ExportConfig(baseUrl = "").isConfigured())
        assertTrue(NavAssistV2ExportConfig(baseUrl = "").usesDiscovery())
        assertFalse(NavAssistV2ExportConfig(baseUrl = "not-a-url").isConfigured())
        assertFalse(NavAssistV2ExportConfig(baseUrl = "ftp://c3xl.local").isConfigured())
        assertFalse(NavAssistV2ExportConfig(baseUrl = "not-a-url").usesDiscovery())
        assertFalse(
            NavAssistV2ExportConfig(baseUrl = "http://c3xl.local", validForMs = 99L).isConfigured(),
        )
        assertFalse(
            NavAssistV2ExportConfig(baseUrl = "http://c3xl.local", validForMs = 2_001L).isConfigured(),
        )
        assertTrue(NavAssistV2ExportConfig(baseUrl = "http://c3xl.local").isConfigured())
        assertFalse(NavAssistV2ExportConfig(baseUrl = "http://c3xl.local").usesDiscovery())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `session rejects characters outside the shared contract`() {
        NavAssistV2Session(sessionId = "contains space")
    }

    @Test
    fun `HTTP exporter resolves the fixed snapshot endpoint`() {
        val exporter = HttpNavAssistV2Exporter(
            config = NavAssistV2ExportConfig(
                baseUrl = "http://c3xl.local:7766/ignored-base-path",
            ),
            stateProvider = { null },
            identity = AndroidKeystoreNavAssistIdentity.generatedForTest(),
            endpointDiscovery = NavAssistV2EndpointDiscovery { NavAssistV2DiscoveryResult.NotFound },
            pinnedDeviceProvider = { null },
        )

        try {
            assertEquals(
                "http://c3xl.local:7766/v3/snapshot",
                exporter.snapshotEndpoint("http://c3xl.local:7766/ignored-base-path").toString(),
            )
            assertEquals(
                "http://192.168.53.232:7766/v3/snapshot",
                exporter.discoveryEndpoint("192.168.53.232").toString(),
            )
        } finally {
            exporter.stop()
        }
    }

    private fun activeState() = NavigationState(
        navAssistControlAllowed = true,
        navigationMode = NavigationMode.REALTIME,
        latitude = 31.2304,
        longitude = 121.4737,
        accuracy = 1.5f,
        bearing = 90f,
        speedKph = 30f,
        currentRoad = "测试主路",
        nextRoad = "测试匝道",
        nextTurnDistanceMeters = 120,
        lanes = listOf(
            LaneState(
                index = 1,
                allowedActions = listOf(LaneAction.STRAIGHT, LaneAction.RIGHT),
                recommended = true,
                rawLaneType = 4,
                recommendedActions = listOf(LaneAction.RIGHT),
                rawRecommendedLaneType = 3,
            ),
            LaneState(
                index = 0,
                allowedActions = listOf(LaneAction.STRAIGHT),
                rawLaneType = 0,
                prohibited = true,
            ),
        ),
        routePlanned = true,
        locationObservedAtMs = 1_000L,
        guidanceObservedAtMs = 2_000L,
        lanesObservedAtMs = 3_000L,
        unkeyedRouteFactsConfirmed = true,
        acceptedPathId = 42L,
        guidancePathId = 42L,
        lanesPathId = 42L,
        currentStepIndex = 4,
        currentLinkIndex = 2,
        currentPointIndex = 8,
        routeMatched = true,
        maneuver = NavigationManeuver.TURN_RIGHT,
        nextManeuver = NavigationManeuver.TURN_LEFT,
        nextManeuverDistanceMeters = 480,
        guidanceStepIndex = 4,
        currentRoadClass = 0,
        currentRoadType = 6,
        parallelRoadStatus = RoadLayerStatus.MAIN,
        elevatedRoadStatus = RoadLayerStatus.SIDE,
        routeNotice = RouteNoticeState(
            type = RouteNoticeType.ROAD_CLOSED,
            distanceMeters = 350,
            observedAtMs = 2_100L,
        ),
        routeRevision = 7L,
    )

    private data class CanonicalFixture(
        val z: Int,
        val nested: NestedFixture,
        val a: Int,
        val absent: String?,
    )

    private data class NestedFixture(val z: Int, val a: Int)
}
