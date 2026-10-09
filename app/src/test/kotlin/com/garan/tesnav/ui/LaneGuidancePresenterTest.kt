package com.garan.tesnav.ui

import com.garan.tesnav.model.LaneAction
import com.garan.tesnav.model.LaneState
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.OemLanePosition
import com.garan.tesnav.model.OemVehicleLaneState
import com.garan.tesnav.model.C3LaneDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneGuidancePresenterTest {
    @Test
    fun `unconfirmed source hides directional guidance even with permissive OEM feedback`() {
        for (status in listOf("unconfirmed", "budgetsUnconfirmed", "requiresExplicitStart")) {
            val result = LaneGuidancePresenter.present(
                NavigationState(navigationMode = NavigationMode.REALTIME, navAssistSourceStatus = status,
                    lanes = listOf(lane(0, true, LaneAction.LEFT))),
                OemVehicleLaneState(permissionValid = true, leftAllowed = true),
            )!!
            assertTrue(result.items.isEmpty())
            assertEquals(LaneRecommendationSide.NONE, result.recommendationSide)
            assertTrue(result.title.contains(if (status == "requiresExplicitStart") "重新启动" else "仅预览"))
        }
    }

    @Test
    fun `fresh keyed Auto recommendations remain visible as preview before source admission`() {
        val state = NavigationState(navigationProvider = "AMAP_AUTO", navigationMode = NavigationMode.REALTIME,
            routePlanned = true, routeMatched = true, navAssistSourceStatus = "awaitingLocation",
            acceptedPathId = 7, guidancePathId = 7, lanesPathId = 7,
            lanesObservedAtMs = 1_000, guidanceObservedAtMs = 1_000,
            lanes = listOf(lane(0, true, LaneAction.LEFT), lane(1, false, LaneAction.STRAIGHT)))
        val result = LaneGuidancePresenter.present(state, nowWallMs = 1_100)!!
        assertEquals(2, result.items.size)
        assertTrue(result.items.first().recommended)
        assertTrue(result.items.none { it.current })
        assertEquals(LaneRecommendationSide.NONE, result.recommendationSide)
        assertTrue(result.title.contains("仅预览"))
        assertNull(result.currentLaneStatus)
        for (invalid in listOf(state.copy(lanesPathId = 8), state.copy(routeRecalculating = true),
            state.copy(routeMatched = false), state.copy(lanesObservedAtMs = 4_001),
            state.copy(guidanceObservedAtMs = 4_001), state.copy(navigationProvider = "AMAP_API"))) {
            assertTrue(LaneGuidancePresenter.present(invalid, nowWallMs = 1_100)!!.items.isEmpty())
        }
        assertTrue(LaneGuidancePresenter.present(state, nowWallMs = 4_001)!!.items.isEmpty())
        assertTrue(LaneGuidancePresenter.present(state.copy(guidanceObservedAtMs = 1), nowWallMs = 3_100)!!.items.isEmpty())
        assertTrue(LaneGuidancePresenter.present(state)!!.items.isEmpty())
    }

    @Test
    fun `hides lane guidance outside active navigation`() {
        assertNull(
            LaneGuidancePresenter.present(
                NavigationState(
                    navigationMode = NavigationMode.ROUTE_PLANNED,
                    lanes = listOf(lane(0, recommended = true, LaneAction.LEFT)),
                ),
            ),
        )
    }

    @Test
    fun `left edge recommendation produces guarded left guidance`() {
        val presentation = LaneGuidancePresenter.present(
            NavigationState(
                navAssistSourceStatus = "healthy", navigationMode = NavigationMode.REALTIME,
                unkeyedRouteFactsConfirmed = true,
                lanes = listOf(
                    lane(0, recommended = true, LaneAction.LEFT),
                    lane(1, recommended = false, LaneAction.STRAIGHT),
                    lane(2, recommended = false, LaneAction.RIGHT),
                ),
            ),
            OemVehicleLaneState(
                position = OemLanePosition.MIDDLE,
                positionValid = true,
                leftAllowed = true,
                permissionValid = true,
            ),
        )!!

        assertEquals(LaneRecommendationSide.LEFT, presentation.recommendationSide)
        assertTrue(presentation.title.contains("中间车道"))
        assertTrue(presentation.title.contains("路线建议靠左"))
        assertTrue(presentation.title.contains("原车报告左侧许可"))
        assertTrue(presentation.title.contains("等待 C3 判断"))
        assertFalse(presentation.title.contains("可向左变道"))
        assertEquals("←", presentation.items.first().symbols)
    }

    @Test
    fun `missing OEM permission is reported without deciding C3 outcome`() {
        val presentation = LaneGuidancePresenter.present(
            NavigationState(
                navAssistSourceStatus = "healthy", navigationMode = NavigationMode.REALTIME,
                unkeyedRouteFactsConfirmed = true,
                lanes = listOf(
                    lane(0, recommended = true, LaneAction.LEFT),
                    lane(1, recommended = false, LaneAction.STRAIGHT),
                ),
            ),
            OemVehicleLaneState(
                position = OemLanePosition.MIDDLE,
                positionValid = true,
                leftAllowed = false,
                permissionValid = true,
            ),
        )!!

        assertTrue(presentation.title.contains("原车未报告左侧许可"))
        assertTrue(presentation.title.contains("等待 C3 判断"))
    }

    @Test
    fun `left and right permission states never claim execution or route completion`() {
        for (left in listOf(true, false)) {
            val direction = if (left) "左" else "右"
            for (valid in listOf(true, false)) {
                for (allowed in listOf(true, false)) {
                    val presentation = LaneGuidancePresenter.present(
                        NavigationState(
                            navAssistSourceStatus = "healthy", navigationMode = NavigationMode.REALTIME,
                            unkeyedRouteFactsConfirmed = true,
                            lanes = listOf(
                                lane(0, recommended = left, LaneAction.STRAIGHT),
                                lane(1, recommended = !left, LaneAction.STRAIGHT),
                            ),
                        ),
                        OemVehicleLaneState(
                            position = if (left) OemLanePosition.LEFTMOST else OemLanePosition.RIGHTMOST,
                            positionValid = true,
                            permissionValid = valid,
                            leftAllowed = allowed,
                            rightAllowed = allowed,
                            laneChangeState = 3,
                        ),
                    )!!
                    val expected = when {
                        !valid -> "原车${direction}侧许可未知"
                        allowed -> "原车报告${direction}侧许可"
                        else -> "原车未报告${direction}侧许可"
                    }
                    assertTrue(presentation.title.contains(expected))
                    assertTrue(presentation.title.contains("等待 C3 判断"))
                    for (claim in listOf("可向", "安全门控通过", "已在左侧", "已在右侧", "已完成")) {
                        assertFalse(presentation.title, presentation.title.contains(claim))
                    }
                }
            }
        }
    }

    @Test
    fun `C3 decision replaces local capability guess without map lanes`() {
        val presentation = LaneGuidancePresenter.present(
            NavigationState(navAssistSourceStatus = "healthy", navigationMode = NavigationMode.REALTIME),
            OemVehicleLaneState(
                position = OemLanePosition.MIDDLE, positionValid = true,
                permissionValid = true, leftAllowed = true, rightAllowed = true,
                vehicleStateValid = true, radarValid = true, blindspotValid = true,
                lateralActive = true, leadPresent = true, egoSpeedKph = 60f,
                leadDistanceM = 35f, leadRelativeSpeedKph = -10f, receivedAtMs = 1L,
                c3LaneDecision = C3LaneDecision("efficiencySafetyBlocked", false, "none"),
            ),
        )!!
        assertEquals("C3 超车：盲区或原车安全条件阻止", presentation.title)
        assertEquals(LaneRecommendationSide.NONE, presentation.recommendationSide)
        assertTrue(presentation.items.isEmpty())
    }

    @Test
    fun `missing C3 decision is reported as unknown`() {
        val presentation = LaneGuidancePresenter.present(
            NavigationState(navAssistSourceStatus = "healthy", navigationMode = NavigationMode.REALTIME),
            OemVehicleLaneState(receivedAtMs = 1L),
        )!!
        assertEquals("C3 选道状态未收到", presentation.title)
    }

    @Test
    fun `right edge recommendation uses recommended actions for its arrow`() {
        val presentation = LaneGuidancePresenter.present(
            NavigationState(
                navigationMode = NavigationMode.SIMULATION,
                lanes = listOf(
                    lane(0, recommended = false, LaneAction.STRAIGHT),
                    LaneState(
                        index = 1,
                        allowedActions = listOf(LaneAction.STRAIGHT),
                        recommended = true,
                        recommendedActions = listOf(LaneAction.STRAIGHT, LaneAction.RIGHT),
                    ),
                ),
            ),
        )!!

        assertEquals(LaneRecommendationSide.RIGHT, presentation.recommendationSide)
        assertEquals("↑→", presentation.items.last().symbols)
    }

    @Test
    fun `multiple recommended lanes do not claim a direction`() {
        val presentation = LaneGuidancePresenter.present(
            NavigationState(
                navAssistSourceStatus = "healthy", navigationMode = NavigationMode.REALTIME,
                unkeyedRouteFactsConfirmed = true,
                lanes = listOf(
                    lane(0, recommended = true, LaneAction.STRAIGHT),
                    lane(1, recommended = true, LaneAction.STRAIGHT),
                ),
            ),
        )!!

        assertEquals(LaneRecommendationSide.MIXED, presentation.recommendationSide)
        assertTrue(presentation.title.contains("推荐车道已标绿"))
    }

    @Test
    fun `edge position reports whether this car is in the recommended lane`() {
        val state = NavigationState(
            navAssistSourceStatus = "healthy", navigationMode = NavigationMode.REALTIME,
            unkeyedRouteFactsConfirmed = true, routeMatched = true,
            lanes = (0..3).map { lane(it, it == 3, LaneAction.STRAIGHT) },
        )
        val left = LaneGuidancePresenter.present(
            state, OemVehicleLaneState(position = OemLanePosition.LEFTMOST, positionValid = true),
        )!!
        val right = LaneGuidancePresenter.present(
            state, OemVehicleLaneState(position = OemLanePosition.RIGHTMOST, positionValid = true),
        )!!
        assertEquals("原车报告：不在推荐车道", left.currentLaneStatus)
        assertEquals("原车报告：在推荐车道", right.currentLaneStatus)
        assertEquals(listOf(true, false, false, false), left.items.map { it.current })
        assertEquals(listOf(false, false, false, true), right.items.map { it.current })
    }

    @Test
    fun `middle position is decisive only when all possible middle lanes agree`() {
        val state = NavigationState(
            navAssistSourceStatus = "healthy", navigationMode = NavigationMode.REALTIME,
            unkeyedRouteFactsConfirmed = true, routeMatched = true,
        )
        val middle = OemVehicleLaneState(position = OemLanePosition.MIDDLE, positionValid = true)
        assertEquals("原车报告：在推荐车道", LaneGuidancePresenter.present(
            state.copy(lanes = (0..3).map { lane(it, it in 1..2, LaneAction.STRAIGHT) }), middle,
        )!!.currentLaneStatus)
        assertEquals("原车报告：不在推荐车道", LaneGuidancePresenter.present(
            state.copy(lanes = (0..3).map { lane(it, it == 3, LaneAction.STRAIGHT) }), middle,
        )!!.currentLaneStatus)
        assertEquals("原车只报告中间车道 · 无法区分具体车道", LaneGuidancePresenter.present(
            state.copy(lanes = (0..3).map { lane(it, it == 1, LaneAction.STRAIGHT) }), middle,
        )!!.currentLaneStatus)
    }

    @Test
    fun `unmatched route or unknown position never claims recommended lane membership`() {
        val state = NavigationState(
            navAssistSourceStatus = "healthy", navigationMode = NavigationMode.REALTIME,
            unkeyedRouteFactsConfirmed = true, routeMatched = true,
            lanes = listOf(lane(0, true, LaneAction.LEFT), lane(1, false, LaneAction.STRAIGHT)),
        )
        val left = OemVehicleLaneState(position = OemLanePosition.LEFTMOST, positionValid = true)
        val unmatched = LaneGuidancePresenter.present(state.copy(routeMatched = false), left)!!
        val invalid = LaneGuidancePresenter.present(state, left.copy(positionValid = false))!!
        assertTrue(unmatched.currentLaneStatus!!.contains("无法判定"))
        assertTrue(invalid.currentLaneStatus!!.contains("无法判定"))
        assertFalse(unmatched.items.any { it.current })
        assertFalse(invalid.items.any { it.current })
        assertTrue(LaneGuidancePresenter.present(state.copy(navigationMode = NavigationMode.SIMULATION), left)!!
            .currentLaneStatus!!.contains("无法判定"))
    }

    @Test
    fun `unkeyed callback remains visible without claiming an actionable route recommendation`() {
        val presentation = LaneGuidancePresenter.present(
            NavigationState(
                navAssistSourceStatus = "healthy", navigationMode = NavigationMode.REALTIME,
                lanes = listOf(lane(0, recommended = true, LaneAction.LEFT), lane(1, false, LaneAction.STRAIGHT)),
            ),
        )!!
        assertEquals(2, presentation.items.size)
        assertTrue(presentation.title.contains("仅供参考"))
        assertFalse(presentation.title.contains("路线建议靠"))
    }

    @Test fun `navigation and overtaking phases survive with and without lane recommendations`() {
        val state = NavigationState(navigationMode = NavigationMode.REALTIME, navAssistSourceStatus = "healthy")
        for ((reason, expected) in mapOf(
            "heuristicSignaling" to "已请求打灯",
            "heuristicReady" to "等待模型启动",
            "heuristicChanging" to "报告执行中",
            "efficiency:heuristicChanging" to "报告执行中",
            "laneChangeComplete" to "变道完成",
            "laneChangeCancelled" to "已取消",
            "oem399LeftDenied" to "否决左侧",
            "heuristicWaitingCrossing" to "等待允许跨越",
        )) {
            val oem = OemVehicleLaneState(receivedAtMs = 1L,
                c3LaneDecision = C3LaneDecision(reason, true, "left"))
            val noLanes = LaneGuidancePresenter.present(state, oem)!!
            assertTrue("$reason: ${noLanes.title}", noLanes.title.contains(expected))
            assertEquals(reason.startsWith("efficiency"), noLanes.title.contains("C3 超车"))
            val lanes = LaneGuidancePresenter.present(state.copy(
                lanes = listOf(lane(0, true, LaneAction.LEFT)), unkeyedRouteFactsConfirmed = true), oem)!!
            assertTrue(lanes.currentLaneStatus!!.contains(expected))
        }
    }

    @Test fun `observation dialog distinguishes membership ambiguity and expired feedback`() {
        val state = NavigationState(
            navAssistSourceStatus = "healthy", navigationMode = NavigationMode.REALTIME,
            unkeyedRouteFactsConfirmed = true, routeMatched = true,
            lanes = (0..3).map { lane(it, it == 1, LaneAction.STRAIGHT) },
        )
        val left = OemVehicleLaneState(position = OemLanePosition.LEFTMOST, positionValid = true)
        assertTrue(LaneGuidancePresenter.observationText(state, left)
            .contains("本车是否在推荐车道：原车报告：不在推荐车道"))
        assertTrue(LaneGuidancePresenter.observationText(state.copy(lanes = listOf(
            lane(0, true, LaneAction.LEFT), lane(1, false, LaneAction.STRAIGHT))), left)
            .contains("本车是否在推荐车道：原车报告：在推荐车道"))
        assertTrue(LaneGuidancePresenter.observationText(state, left.copy(position = OemLanePosition.MIDDLE))
            .contains("无法区分具体车道"))
        val expired = left.copy(receivedAtElapsedMs = 10L).forDisplay(510L, 500L)
        val expiredText = LaneGuidancePresenter.observationText(state, expired)
        assertTrue(expiredText.contains("回执已过期"))
        assertTrue(expiredText.contains("原车车道位置未知 · 无法判定"))
        assertTrue(LaneGuidancePresenter.observationText(state.copy(routeMatched = false), left)
            .contains("路线与车道提示未对齐 · 无法判定"))
    }

    private fun lane(index: Int, recommended: Boolean, vararg actions: LaneAction) = LaneState(
        index = index,
        allowedActions = actions.toList(),
        recommended = recommended,
        recommendedActions = if (recommended) actions.toList() else emptyList(),
    )
}
