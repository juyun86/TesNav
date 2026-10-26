package com.garan.tesnav.ui

import com.garan.tesnav.model.*
import org.junit.Assert.*
import org.junit.Test

class ArLaneGuidancePresenterTest {
    private val state = NavigationState(navigationProvider = "AMAP_AUTO",
        navigationMode = NavigationMode.REALTIME, routePlanned = true, routeMatched = true,
        navAssistSourceStatus = "healthy", acceptedPathId = 7, guidancePathId = 7, lanesPathId = 7,
        guidanceReceivedElapsedMs = 1_000, guidanceObservedAtMs = 1_000, lanesObservedAtMs = 1_000,
        lanes = (0..3).map { LaneState(it, recommended = it == 0) })
    private val feedback = OemVehicleLaneState(position = OemLanePosition.RIGHTMOST,
        positionValid = true, receivedAtMs = 1_000, receivedAtElapsedMs = 1_000,
        c3LaneDecision = C3LaneDecision("heuristicWaitingCrossing", false, "left"))
    private fun text(s: NavigationState = state, o: OemVehicleLaneState = feedback,
                     now: Long = 1_100) = ArLaneGuidancePresenter.text(s, o, now, now, 500)

    @Test fun `shows recommendation position and actual blocked decision separately`() {
        val result = text()!!
        assertTrue(result.contains("高德推荐：1车道"))
        assertTrue(result.contains("本车：第4车道"))
        assertTrue(result.contains("路线建议靠左"))
        assertTrue(result.contains("向左 · 等待允许跨越"))
        assertFalse(result.contains("可以变道"))
    }

    @Test fun `ambiguous middle does not guess a lane`() {
        val result = text(o = feedback.copy(position = OemLanePosition.MIDDLE))!!
        assertTrue(result.contains("本车具体车道未确认"))
        assertTrue(result.contains("路线建议靠左"))
        assertFalse(result.contains("本车：第"))
    }

    @Test fun `expired feedback removes both position and decision`() {
        val result = text(now = 1_500)!!
        assertTrue(result.contains("本车具体车道未确认"))
        assertTrue(result.contains("等待有效回执"))
        assertFalse(result.contains("等待允许跨越"))
    }

    @Test fun `expired route or changed route never retains directional overlay`() {
        for (invalid in listOf(state.copy(routeMatched = false), state.copy(routeRecalculating = true),
            state.copy(navAssistSourceStatus = "unconfirmed"), state.copy(guidanceReceivedElapsedMs = 5_000))) {
            assertEquals("特会开选道 · 等待有效路线数据", text(invalid))
        }
        assertEquals("特会开选道 · 等待有效路线数据", text(now = 4_001))
    }

    @Test fun `stale or mismatched lane data does not mark a lane`() {
        for (invalid in listOf(state.copy(lanesPathId = 8), state.copy(lanesObservedAtMs = -5_000),
            state.copy(lanesObservedAtMs = 5_000), state.copy(lanes = listOf(LaneState(2, recommended = true))))) {
            val result = text(invalid)!!
            assertTrue(result.contains("高德推荐：待确认"))
            assertFalse(result.contains("本车：第"))
            assertFalse(result.contains("路线建议靠"))
        }
    }

    @Test fun `fresh keyed display lanes replace old control lanes`() {
        val result = text(state.copy(autoDisplayLanesPathId = 7,
            autoDisplayLanes = (0..2).map { LaneState(it, recommended = it == 2) }))!!
        assertTrue(result.contains("高德推荐：3车道"))
        assertTrue(result.contains("本车：第3车道"))
        assertTrue(result.contains("路线建议靠右"))
    }

    @Test fun `no overlay on API idle preview simulation or ended route`() {
        for (inactive in listOf(state.copy(navigationProvider = "AMAP_API"),
            state.copy(navigationMode = NavigationMode.IDLE), state.copy(navigationMode = NavigationMode.SIMULATION),
            state.copy(navigationMode = NavigationMode.ROUTE_PLANNED), state.copy(routePlanned = false))) {
            assertNull(text(inactive))
        }
    }
}
