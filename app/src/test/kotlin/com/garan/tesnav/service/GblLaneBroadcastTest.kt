package com.garan.tesnav.service
import com.garan.tesnav.model.LaneAction
import org.junit.Assert.*
import org.junit.Test
class GblLaneBroadcastTest {
    @Test fun wireOmitsOnlyFalseRouteAvoidDefault() {
        val input = com.google.gson.JsonParser.parseString("""{"lanes":{"items":[{"routeAvoid":false},{"routeAvoid":true}]},"routeActive":false}""")
        val wire = com.garan.tesnav.export.CanonicalJson.encode(input)
        assertEquals("""{"lanes":{"items":[{},{"routeAvoid":true}]},"routeActive":false}""", wire)
    }
    private fun raw(vararg lanes: Pair<Int, Int>): String = """{"tesnav_lane_encoding":"gbl_lane_action_v1","drive_way_enabled":true,"drive_way_size":${lanes.size},"drive_way_info":[${lanes.joinToString(",") { "{\"gbl_back\":${it.first},\"gbl_front\":${it.second}}" }}]}"""
    @Test fun fourLanesPreserveOrderAndOnlyRightIsRecommended() {
        val lanes = GblLaneBroadcast.parse(raw(1 to 255, 0 to 255, 0 to 255, 3 to 3))!!
        assertEquals(listOf(0,1,2,3), lanes.map { it.index })
        assertEquals(listOf(false,false,false,true), lanes.map { it.recommended })
        assertEquals(listOf(LaneAction.LEFT), lanes[0].allowedActions)
        assertEquals(listOf(LaneAction.RIGHT), lanes[3].recommendedActions)
    }
    @Test fun mixedLaneKeepsAllowedAndRecommendedDirectionsSeparate() {
        val lane = GblLaneBroadcast.parse(raw(2 to 1))!!.single()
        assertEquals(listOf(LaneAction.STRAIGHT, LaneAction.LEFT), lane.allowedActions)
        assertEquals(listOf(LaneAction.LEFT), lane.recommendedActions)
        assertEquals(1, lane.rawRecommendedLaneType)
    }
    @Test fun unknownSpecialAndConflictingFrontNeverRecommend() {
        val lanes = GblLaneBroadcast.parse(raw(1 to 3, 254 to 1, 21 to 21, 23 to 0, 0 to 255, 15 to 15))!!
        assertTrue(lanes.none { it.recommended })
    }
    @Test fun hiddenClearsAndOtherEncodingRemainsLegacy() {
        assertTrue(GblLaneBroadcast.parse("""{"tesnav_lane_encoding":"gbl_lane_action_v1","drive_way_enabled":false}""")!!.isEmpty())
        assertNull(GblLaneBroadcast.parse("{}"))
        assertNull(GblLaneBroadcast.parse(null))
    }
    @Test(expected=IllegalArgumentException::class) fun countMismatchFailsClosed() {
        GblLaneBroadcast.parse(raw(0 to 0).replace("\"drive_way_size\":1", "\"drive_way_size\":2"))
    }
    @Test fun nativeReceiverKeepsFreshnessAndClearingAndDisplayRecommendation() {
        fun nav(previous: com.garan.tesnav.model.NavigationState? = null, wall: Long = 10000, elapsed: Long = 1000, icon: Int = 2) =
            AmapAutoBroadcast.navigation(previous, null, mapOf("ROUTE_ALL_DIS" to 1000, "SEG_REMAIN_DIS" to 80, "ICON" to icon,
                "CAR_LATITUDE" to 31.2, "CAR_LONGITUDE" to 121.5, "CAR_ACCURACY" to 4), wall, elapsed, 3000)
        val active = nav()
        val state = AmapAutoBroadcast.driveWay(active, raw(2 to 1, 0 to 255), 10100,1100,3000)!!
        assertEquals(listOf(true,false), state.autoDisplayLanes.map { it.recommended })
        assertEquals(listOf(LaneAction.LEFT), state.lanes[0].recommendedActions)
        val wire = com.garan.tesnav.export.NavAssistV2Mapper.snapshot(state,"gbl",1,10100,500).lanes!!
        assertEquals(listOf("LEFT"), wire.items[0].recommendedActions)
        assertTrue(nav(state,13101,4101).lanes.isEmpty())
        assertTrue(nav(state,10200,1200,3).lanes.isEmpty())
        assertTrue(AmapAutoBroadcast.driveWay(active,raw(2 to 1),14000,5000,3000)!!.lanes.isEmpty())
        assertTrue(AmapAutoBroadcast.driveWay(state,"""{"tesnav_lane_encoding":"gbl_lane_action_v1","drive_way_enabled":false}""",10200,1200,3000)!!.autoDisplayLanes.isEmpty())
    }
}
