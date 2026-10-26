package com.garan.tesnav.ui

import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.OemVehicleLaneState

/** Screen-space HUD only: lane indices are not camera-space lane geometry. */
internal object ArLaneGuidancePresenter {
    fun presentation(state: NavigationState, oem: OemVehicleLaneState, elapsedMs: Long,
                     wallMs: Long, feedbackBudgetMs: Long): LaneGuidancePresentation? {
        if (state.navigationProvider != "AMAP_AUTO" || state.navigationMode != NavigationMode.REALTIME ||
            !AutoNavigationDisplayPolicy.guidanceFresh(state, elapsedMs) || state.routeRecalculating ||
            state.routeMatched != true || state.navAssistSourceStatus != "healthy") return null
        val display = state.autoDisplayLanesPathId != null && state.autoDisplayLanesPathId == state.acceptedPathId &&
            state.guidancePathId == state.acceptedPathId &&
            state.guidanceObservedAtMs?.let { wallMs-it in 0..3_000 } == true
        val regular = (state.unkeyedRouteFactsConfirmed || (state.lanesPathId != null &&
            state.lanesPathId == state.acceptedPathId && state.guidancePathId == state.acceptedPathId)) &&
            state.lanesObservedAtMs?.let { wallMs-it in 0..3_000 } == true
        if (!display && !regular) return null
        val result = LaneGuidancePresenter.present(state, oem.forDisplay(elapsedMs, feedbackBudgetMs), wallMs) ?: return null
        val count = (if (display) state.autoDisplayLanes else state.lanes).size
        return result.takeIf { count in 1..16 && it.items.size == count &&
            it.items.indices.all { index -> it.items[index].index == index } }
    }
    fun text(state: NavigationState, oem: OemVehicleLaneState,
             elapsedMs: Long, wallMs: Long, feedbackBudgetMs: Long): String? {
        if (state.navigationProvider != "AMAP_AUTO" || state.navigationMode != NavigationMode.REALTIME ||
            !state.routePlanned) return null
        if (!AutoNavigationDisplayPolicy.guidanceFresh(state, elapsedMs) ||
            state.routeRecalculating || state.routeMatched != true || state.navAssistSourceStatus != "healthy")
            return "特会开选道 · 等待有效路线数据"

        val feedback = oem.forDisplay(elapsedMs, feedbackBudgetMs)
        val presentation = presentation(state, feedback, elapsedMs, wallMs, feedbackBudgetMs)
        val items = presentation?.items.orEmpty()
        val complete = items.isNotEmpty()
        val recommended = if (complete) items.filter { it.recommended && !it.prohibited } else emptyList()
        val recommendation = if (recommended.isEmpty()) "高德推荐：待确认" else
            "高德推荐：" + recommended.joinToString("、") { "${it.index + 1}" } + "车道（左起）"
        val current = if (complete) items.singleOrNull { it.current } else null
        val position = current?.let { "本车：第${it.index + 1}车道" } ?: "本车具体车道未确认"
        val side = if (complete) when (presentation?.recommendationSide) {
            LaneRecommendationSide.LEFT -> " · 路线建议靠左"
            LaneRecommendationSide.RIGHT -> " · 路线建议靠右"
            else -> ""
        } else ""
        // Actual C3 decision remains separately labelled, including blocked/cancelled states.
        // A navigation recommendation never implies that a lane change is permitted.
        val decision = LaneGuidancePresenter.decisionText(feedback) ?: "C3 选道：等待有效回执"
        return "$recommendation\n$position$side\n$decision"
    }
}
