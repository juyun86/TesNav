package com.garan.tesnav.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.garan.tesnav.export.NavAssistV2Mapper
import com.garan.tesnav.model.NavigationSourceGate
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.NavigationManeuver
import com.garan.tesnav.model.LaneAction

class AmapAutoBroadcastTest {
    @Test fun `Auto guidance uses jihui ICON namespace and highway forks without changing SDK mapping`() {
        for ((icon, expected) in mapOf(9 to NavigationManeuver.U_TURN_RIGHT,
            10 to NavigationManeuver.KEEP_LEFT, 11 to NavigationManeuver.KEEP_RIGHT,
            12 to NavigationManeuver.ROUNDABOUT, 13 to NavigationManeuver.ROUNDABOUT,
            4 to NavigationManeuver.MERGE_LEFT, 5 to NavigationManeuver.MERGE_RIGHT)) {
            assertEquals(expected, auto(overrides = mapOf("ROAD_TYPE" to 6, "ICON" to icon, "NEW_ICON" to 2)).maneuver)
        }
        assertEquals(NavigationManeuver.SLIGHT_RIGHT,
            auto(overrides = mapOf("ROAD_TYPE" to 1, "ICON" to 5)).maneuver)
        assertEquals(NavigationManeuver.ROUNDABOUT, com.garan.tesnav.util.NavigationMappers.maneuver(11))
        assertEquals(NavigationManeuver.STRAIGHT, com.garan.tesnav.util.NavigationMappers.maneuver(9))
    }

    @Test fun `fresh C3 vehicle observation is primary without renewing its timestamp`() {
        val vehicle = NavigationState(latitude = 31.2, longitude = 121.5, accuracy = 2f,
            bearing = 90f, locationObservedAtMs = 9_700L, locationReceivedElapsedMs = 900L,
            locationSourceStatus = "vehicle-can")
        val extras = mapOf("ROUTE_ALL_DIS" to 1000, "SEG_REMAIN_DIS" to 80, "ICON" to 2,
            "CAR_LATITUDE" to 32.0, "CAR_LONGITUDE" to 122.0, "CAR_ACCURACY" to 4f)
        val selected = AmapAutoBroadcast.navigation(null, vehicle, extras, 10_000L, 1_000L, 3_000L)
        assertEquals(31.2, selected.latitude!!, 0.0)
        assertEquals("vehicle-can", selected.locationSourceStatus)
        assertEquals(9_700L, selected.locationObservedAtMs)
        assertEquals(900L, selected.locationReceivedElapsedMs)
        val expired = AmapAutoBroadcast.navigation(selected, vehicle, extras, 13_001L, 4_001L, 3_000L)
        assertEquals(32.0, expired.latitude!!, 0.0)
        assertEquals("amap_auto", expired.locationSourceStatus)
    }

    @Test fun `independent position keeps source fresh between Auto broadcasts without changing guidance`() {
        val guidance = auto(wall = 10_000, elapsed = 1_000, overrides = mapOf("CUR_SPEED" to 80))
        // At 3.069 s the old selected position fails exactly as in the short-lamp trace.
        val before = NavigationSourceGate(3_000, 6_000).also { it.arm(900) }
        assertFalse(before.prepare(guidance, 4_069, 13_069).navAssistControlAllowed)
        assertEquals("sourceStalled", before.reason)
        val fix = guidance.copy(latitude = 31.201, locationTime = 12_700,
            locationObservedAtMs = 12_700, locationReceivedElapsedMs = 3_800,
            locationSourceStatus = "vehicle-can")
        val refreshed = AmapAutoBroadcast.refreshLocation(guidance, fix, 13_069, 4_069, 3_000)!!
        // All non-location fields, including control flags, must be identical.
        assertEquals(guidance.copy(latitude = fix.latitude, locationTime = fix.locationTime,
            locationObservedAtMs = fix.locationObservedAtMs,
            locationReceivedElapsedMs = fix.locationReceivedElapsedMs,
            locationSourceStatus = fix.locationSourceStatus), refreshed)
        val after = NavigationSourceGate(3_000, 6_000).also { it.arm(900) }
        assertTrue(after.prepare(refreshed, 4_069, 13_069).navAssistControlAllowed)
        assertEquals("healthy", after.reason)
        val laterFix = fix.copy(locationObservedAtMs = 16_500, locationReceivedElapsedMs = 7_500)
        val oldGuidance = AmapAutoBroadcast.refreshLocation(refreshed, laterFix, 16_901, 7_901, 3_000)!!
        assertFalse(after.prepare(oldGuidance, 7_901, 16_901).navAssistControlAllowed)
        assertEquals("progressUnconfirmed", after.reason)
    }

    @Test fun `location refresh never retimes repeated observations or creates a route`() {
        val previous = auto()
        val fix = previous.copy(locationTime = 10_500, locationObservedAtMs = 10_500,
            locationReceivedElapsedMs = 1_600, locationSourceStatus = "phone-gps")
        val first = AmapAutoBroadcast.refreshLocation(previous, fix, 10_600, 1_700, 3_000)!!
        assertEquals(first, AmapAutoBroadcast.refreshLocation(first, fix, 11_000, 2_100, 3_000))
        assertEquals(first, AmapAutoBroadcast.refreshLocation(first, fix, 13_501, 4_100, 3_000))
        val gate = NavigationSourceGate(3_000, 6_000).also { it.arm(900) }
        assertFalse(gate.prepare(first, 4_100, 13_501).navAssistControlAllowed)
        assertEquals("sourceClockInvalid", gate.reason)
        assertNull(AmapAutoBroadcast.refreshLocation(null, fix, 10_600, 1_700, 3_000))
        val idle = NavigationState()
        val refreshedIdle = AmapAutoBroadcast.refreshLocation(idle, fix, 10_600, 1_700, 3_000)!!
        assertFalse(refreshedIdle.routePlanned)
        assertFalse(refreshedIdle.navAssistControlAllowed)
        assertNull(refreshedIdle.acceptedPathId)
    }

    @Test fun `location refresh rejects invalid and expired observations with both original clocks`() {
        val previous = auto()
        val fix = previous.copy(locationObservedAtMs = 10_500, locationReceivedElapsedMs = 1_500)
        for (invalid in listOf(fix.copy(locationObservedAtMs = 7_599),
            fix.copy(locationReceivedElapsedMs = -1), fix.copy(locationReceivedElapsedMs = 1_701),
            fix.copy(locationObservedAtMs = 10_601), fix.copy(locationObservedAtMs = null),
            fix.copy(locationReceivedElapsedMs = null), fix.copy(latitude = Double.NaN),
            fix.copy(longitude = null), fix.copy(accuracy = 0f), fix.copy(accuracy = Float.NaN))) {
            assertEquals(previous, AmapAutoBroadcast.refreshLocation(previous, invalid, 10_600, 1_700, 3_000))
        }
        assertEquals(previous, AmapAutoBroadcast.refreshLocation(previous, fix, 10_600, 1_700, 0))
        val phone = fix.copy(locationSourceStatus = "phone-gps", bearing = null)
        val refreshed = AmapAutoBroadcast.refreshLocation(previous, phone, 10_600, 1_700, 3_000)!!
        assertEquals("phone-gps", refreshed.locationSourceStatus)
        assertEquals(previous.bearing, refreshed.bearing) // Retain Auto's low-speed direction fallback.
    }

    private fun auto(previous: NavigationState? = null, fallback: NavigationState? = null,
                     wall: Long = 10_000L, elapsed: Long = 1_000L,
                     overrides: Map<String, Any?> = emptyMap()): NavigationState = AmapAutoBroadcast.navigation(
        previous, fallback, mapOf("ROUTE_ALL_DIS" to 1_000, "SEG_REMAIN_DIS" to 80,
            "ICON" to 2, "NEXT_ROAD_NAME" to "甲路", "CUR_SPEED" to 0,
            "CAR_LATITUDE" to 31.2, "CAR_LONGITUDE" to 121.5, "CAR_ACCURACY" to 4,
            "CAR_DIRECTION" to 90) + overrides, wall, elapsed, 3_000L,
    )

    @Test fun `Auto road category never becomes an SDK ramp or exit`() {
        for (category in listOf(0, 1, 6)) {
            val state = auto(overrides = mapOf("ROAD_TYPE" to category))
            assertEquals(category, state.currentRoadClass)
            assertNull(state.currentRoadType)
            assertEquals(NavigationManeuver.TURN_LEFT, state.maneuver)
            val wire = NavAssistV2Mapper.snapshot(state, "road", 1, 10_000, 500)
            assertEquals(category, wire.guidance?.roadClass)
            assertNull(wire.guidance?.roadType)
        }
        assertNull(auto(overrides = mapOf("ROAD_TYPE" to 9)).currentRoadClass)
        assertEquals(NavigationManeuver.RAMP_LEFT,
            com.garan.tesnav.util.NavigationMappers.maneuver(2, 6)) // Existing SDK contract retained.
    }

    @Test fun `stale retained GPS cannot borrow fresh navigation timestamps`() {
        val previous = auto(wall = 1_000, elapsed = 1_000)
        val state = auto(previous, previous, wall = 11_000, elapsed = 11_000,
            overrides = mapOf("CAR_ACCURACY" to null))
        assertEquals(previous.locationObservedAtMs, state.locationObservedAtMs)
        assertEquals(previous.locationReceivedElapsedMs, state.locationReceivedElapsedMs)
        val gate = NavigationSourceGate(3_000, 6_000)
        gate.arm(900)
        val prepared = gate.prepare(state, 11_000, 11_000)
        assertFalse(prepared.navAssistControlAllowed)
        assertFalse(NavAssistV2Mapper.snapshot(prepared, "stale", 1, 11_000, 500).routeActive)
    }

    @Test fun `partial coordinates never splice with old coordinates and accuracy`() {
        val previous = auto(wall = 1_000, elapsed = 1_000)
        val state = auto(previous, wall = 11_000, elapsed = 11_000,
            overrides = mapOf("CAR_LATITUDE" to 32.0, "CAR_LONGITUDE" to null))
        assertEquals(previous.latitude, state.latitude)
        assertEquals(previous.accuracy, state.accuracy)
        assertEquals(previous.locationObservedAtMs, state.locationObservedAtMs)
        val missing = auto(overrides = mapOf("CAR_LONGITUDE" to null))
        assertNull(missing.latitude)
        assertNull(missing.locationObservedAtMs)
    }

    @Test fun `fresh reception cannot hide old measurement time in fallback GPS`() {
        val fallback = NavigationState(latitude = 31.2, longitude = 121.5, accuracy = 4f,
            locationObservedAtMs = 1_000L, locationReceivedElapsedMs = 1_000L)
        val state = auto(fallback = fallback, overrides = mapOf("CAR_ACCURACY" to null))
        assertNull(state.accuracy)
        assertNull(state.locationObservedAtMs)
    }

    private val driveWay = """{"drive_way_enabled":true,"drive_way_size":3,"drive_way_info":[
        {"drive_way_lane_Back_icon":"15","trafficLaneAdvised":false},
        {"drive_way_lane_Back_icon":"18","trafficLaneAdvised":true},
        {"drive_way_lane_Back_icon":"32","trafficLaneAdvised":false}]}"""

    @Test fun `live Auto gray gray right icons display route lane without changing control recommendation`() {
        val raw = """{"drive_way_enabled":true,"drive_way_size":3,"drive_way_info":[
            {"drive_way_lane_Back_icon":"1","trafficLaneAdvised":false},
            {"drive_way_lane_Back_icon":"0","trafficLaneAdvised":false},
            {"drive_way_lane_Back_icon":"33","trafficLaneAdvised":false}]}"""
        val first = AmapAutoBroadcast.driveWay(auto(), raw, 10_100, 1_100, 3_000)!!
        assertEquals(listOf(false, false, true), first.autoDisplayLanes.map { it.recommended })
        assertTrue(first.lanes.none { it.recommended })
        val held = auto(previous = first, wall = 15_000, elapsed = 6_000)
        assertTrue(held.lanes.isEmpty())
        assertEquals(3, held.autoDisplayLanes.size)
        assertTrue(AmapAutoBroadcast.driveWay(held, raw.replace("true", "false"), 15_100, 6_100, 3_000)!!.autoDisplayLanes.isEmpty())
        assertTrue(auto(previous = first, overrides = mapOf("ICON" to 3)).autoDisplayLanes.isEmpty())
    }

    @Test fun `13012 recommendation normalizes Auto icons and exports keyed lane facts`() {
        val state = AmapAutoBroadcast.driveWay(auto(), driveWay, 10_100, 1_100, 3_000)!!
        assertEquals(listOf(LaneAction.RIGHT), state.lanes[1].recommendedActions)
        assertEquals(listOf(LaneAction.STRAIGHT), state.lanes[2].allowedActions)
        assertEquals(listOf(false, true, false), state.lanes.map { it.recommended })
        assertEquals(state.acceptedPathId, state.lanesPathId)
        val wire = NavAssistV2Mapper.snapshot(state, "lane", 1, 10_100, 500).lanes!!
        assertEquals(listOf("RIGHT"), wire.items[1].recommendedActions)
        assertEquals(listOf("STRAIGHT"), wire.items[2].allowedActions)
        val continued = auto(state, wall = 10_200, elapsed = 1_200)
        assertEquals(state.lanes, continued.lanes)
        assertTrue(auto(state, wall = 10_200, elapsed = 1_200,
            overrides = mapOf("ICON" to 3)).lanes.isEmpty())
        assertTrue(auto(state, wall = 13_101, elapsed = 4_101).lanes.isEmpty())
    }

    @Test fun `13012 unkeyed stale disabled malformed or unknown cannot recommend a lane`() {
        assertNull(AmapAutoBroadcast.driveWay(null, driveWay, 10_100, 1_100, 3_000))
        assertTrue(AmapAutoBroadcast.driveWay(auto(), driveWay, 14_000, 5_000, 3_000)!!.lanes.isEmpty())
        val state = AmapAutoBroadcast.driveWay(auto(), driveWay, 10_100, 1_100, 3_000)!!
        for (raw in listOf("broken", driveWay.replace("true", "false"),
            driveWay.replace("\"drive_way_size\":3", "\"drive_way_size\":2"))) {
            assertTrue(AmapAutoBroadcast.driveWay(state, raw, 10_200, 1_200, 3_000)!!.lanes.isEmpty())
        }
        val unknown = AmapAutoBroadcast.driveWay(auto(), driveWay.replace("\"18\"", "\"999\""), 10_100, 1_100, 3_000)!!
        assertFalse(unknown.lanes[1].recommended)
    }
    @Test fun `retains every extra for each Amap Auto interface`() {
        val navigation = AmapAutoBroadcast.capture(
            action = "AUTONAVI_STANDARD_BROADCAST_SEND",
            extras = mapOf(
                "KEY_TYPE" to 10001,
                "CUR_ROAD_NAME" to "测试路",
                "ICON" to 3,
                "EXTRA_DRIVE_WAY" to intArrayOf(1, 2, 3),
            ),
            observedAtMs = 100L,
        )
        val traffic = AmapAutoBroadcast.capture(
            action = "AUTONAVI_STANDARD_BROADCAST_SEND",
            extras = mapOf("KEY_TYPE" to "60073", "trafficLightStatus" to 2),
            observedAtMs = 200L,
        )
        val retained = AmapAutoBroadcast.retain(AmapAutoBroadcast.retain(emptyMap(), navigation), traffic)

        assertEquals(2, retained.size)
        assertEquals("测试路", retained[navigation.interfaceKey]?.extras?.get("CUR_ROAD_NAME"))
        assertEquals("[1, 2, 3]", retained[navigation.interfaceKey]?.extras?.get("EXTRA_DRIVE_WAY"))
        assertEquals(60073, retained[traffic.interfaceKey]?.keyType)
    }

    @Test fun `maps the pinned jihui 60073 scalar contract`() {
        val observation = AmapAutoBroadcast.trafficLight(
            mapOf("trafficLightStatus" to "2", "dir" to 2, "redLightCountDownSeconds" to "37"),
            observedAtMs = 123L,
        )

        assertEquals(2, observation?.status)
        assertEquals(2, observation?.direction)
        assertEquals(37, observation?.countdownSeconds)
        assertEquals(123L, observation?.observedAtMs)
        assertNull(AmapAutoBroadcast.trafficLight(mapOf("dir" to 2), 123L))
    }

    @Test fun `maps the pinned jihui 10001 navigation interface for the shared control gate`() {
        val state = AmapAutoBroadcast.navigation(
            previous = null,
            locationFallback = com.garan.tesnav.model.NavigationState(
                latitude = 31.2,
                longitude = 121.5,
                accuracy = 4f,
                bearing = null,
                locationObservedAtMs = 990L,
                locationReceivedElapsedMs = 490L,
            ),
            extras = mapOf(
                "KEY_TYPE" to 10001,
                "ROUTE_ALL_DIS" to 12_000,
                "ROUTE_REMAIN_DIS" to 8_000,
                "ROUTE_REMAIN_TIME" to 900,
                "SEG_REMAIN_DIS" to 120,
                "ICON" to 3,
                "ROAD_TYPE" to 9,
                "CUR_ROAD_NAME" to "当前路",
                "NEXT_ROAD_NAME" to "下一路",
                "CAR_DIRECTION" to 90,
                "CUR_SPEED" to 42,
                "LIMITED_SPEED" to 40,
                "routeRemainTrafficLightNum" to 5,
            ),
            observedAtMs = 1_000L,
            receivedElapsedMs = 500L,
            sourceBudgetMs = 2_000L,
        )

        assertTrue(state.routePlanned)
        assertEquals("当前路", state.currentRoad)
        assertEquals("下一路", state.nextRoad)
        assertEquals(120, state.nextTurnDistanceMeters)
        assertEquals(8_000, state.routeRemainDistanceMeters)
        assertEquals(5, state.remainingTrafficLightCount)
        assertTrue(state.isOverspeed)
        assertFalse(state.navAssistControlAllowed)
        assertEquals("amap_auto_pending", state.navAssistSourceStatus)
        assertEquals(4f, state.accuracy)
        assertEquals(90f, state.bearing)
        assertEquals(state.acceptedPathId, state.guidancePathId)
        assertEquals(0, state.currentStepIndex)
        assertEquals(state.currentStepIndex, state.guidanceStepIndex)
        assertEquals(1L, state.routeRevision)
    }

    @Test fun `advances the Amap Auto maneuver identity without changing the route identity`() {
        val first = AmapAutoBroadcast.navigation(
            previous = null,
            locationFallback = null,
            extras = mapOf("ROUTE_ALL_DIS" to 1_000, "SEG_REMAIN_DIS" to 80, "ICON" to 2,
                "NEXT_ROAD_NAME" to "甲路", "CAR_LATITUDE" to 31.2, "CAR_LONGITUDE" to 121.5,
                "CAR_DIRECTION" to 90, "CAR_ACCURACY" to 5),
            observedAtMs = 1_000L,
            receivedElapsedMs = 500L,
            sourceBudgetMs = 2_000L,
        )
        val next = AmapAutoBroadcast.navigation(
            previous = first,
            locationFallback = null,
            extras = mapOf("ROUTE_ALL_DIS" to 1_000, "SEG_REMAIN_DIS" to 200, "ICON" to 3,
                "NEXT_ROAD_NAME" to "乙路", "CAR_LATITUDE" to 31.2, "CAR_LONGITUDE" to 121.5,
                "CAR_DIRECTION" to 90, "CAR_ACCURACY" to 5),
            observedAtMs = 2_000L,
            receivedElapsedMs = 1_500L,
            sourceBudgetMs = 2_000L,
        )

        assertEquals(first.acceptedPathId, next.acceptedPathId)
        assertEquals(first.routeRevision, next.routeRevision)
        assertEquals(1, next.currentStepIndex)
    }

    @Test fun `Amap Auto receives the same control gate authority as the API source`() {
        val state = AmapAutoBroadcast.navigation(
            previous = null,
            locationFallback = com.garan.tesnav.model.NavigationState(
                latitude = 31.2,
                longitude = 121.5,
                accuracy = 4f,
                bearing = null,
                locationObservedAtMs = 9_900L,
                locationReceivedElapsedMs = 900L,
            ),
            extras = mapOf("ROUTE_ALL_DIS" to 1_000, "SEG_REMAIN_DIS" to 80, "ICON" to 2,
                "NEXT_ROAD_NAME" to "甲路", "CUR_SPEED" to 20, "CAR_DIRECTION" to 90),
            observedAtMs = 10_000L,
            receivedElapsedMs = 1_000L,
            sourceBudgetMs = 2_000L,
        )
        val gate = NavigationSourceGate(sourceBudgetMs = 2_000L, progressBudgetMs = 2_000L)
        gate.arm(900L)
        val prepared = gate.prepare(state, nowElapsedMs = 1_000L, nowWallMs = 10_000L)
        val snapshot = NavAssistV2Mapper.snapshot(prepared, "auto-session", 1L, 10_000L, 500L)

        assertTrue(prepared.navAssistControlAllowed)
        assertTrue(snapshot.routeActive)
        assertTrue(snapshot.maneuverEventId != 0L)
    }
}
