package com.garan.tesnav.ui

import com.garan.tesnav.model.LaneAction
import com.garan.tesnav.model.LaneState
import com.garan.tesnav.model.NavigationMode
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.OemLanePosition
import com.garan.tesnav.model.OemVehicleLaneState

enum class LaneRecommendationSide { LEFT, RIGHT, MIXED, NONE }

data class LaneGuidanceItem(
    val index: Int,
    val symbols: String,
    val recommended: Boolean,
    val prohibited: Boolean,
    val current: Boolean = false,
)

data class LaneGuidancePresentation(
    val title: String,
    val items: List<LaneGuidanceItem>,
    val recommendationSide: LaneRecommendationSide,
    val currentLaneStatus: String? = null,
)

/** Converts AMap lane callbacks into a compact, testable navigation overlay model. */
object LaneGuidancePresenter {
    /** Use the same conservative comparison as the visible lane arrows, never infer an exact middle lane. */
    fun observationText(state: NavigationState, oem: OemVehicleLaneState): String {
        val position = when (oem.position.takeIf { oem.positionValid }) {
            OemLanePosition.LEFTMOST -> "最左车道"
            OemLanePosition.RIGHTMOST -> "最右车道"
            OemLanePosition.MIDDLE -> "中间车道（具体车道未知）"
            OemLanePosition.SINGLE -> "单车道"
            else -> "未知（未收到有效位置或回执已过期）"
        }
        val presentation = present(state, oem)
        val recommendation = displayState(state, System.currentTimeMillis()).lanes.filter(LaneState::recommended).sortedBy(LaneState::index)
            .joinToString("、") { "第${it.index + 1}车道" }.ifBlank { "暂无" }
        val status = presentation?.currentLaneStatus ?: when {
            state.navigationMode != NavigationMode.REALTIME -> "非实时导航 · 无法判定本车车道"
            state.lanes.isEmpty() -> "高德未提供车道引导 · 无法判定"
            else -> "${presentation?.title ?: "车道数据待确认"} · 无法判定"
        }
        return "车道级引导（从左向右编号）\n高德推荐：$recommendation\n原车位置：$position\n本车是否在推荐车道：$status" +
            if (presentation?.currentLaneStatus == null) decisionText(oem)?.let { "\n$it" }.orEmpty() else ""
    }

    fun present(state: NavigationState, oem: OemVehicleLaneState = OemVehicleLaneState(),
                nowWallMs: Long? = null): LaneGuidancePresentation? = presentDisplay(displayState(state, nowWallMs), oem, nowWallMs)

    private fun displayState(state: NavigationState, nowWallMs: Long?): NavigationState {
        val valid = state.navigationProvider == "AMAP_AUTO" && state.routePlanned && !state.routeRecalculating &&
            nowWallMs != null && state.guidanceObservedAtMs?.let { nowWallMs - it in 0..3_000 } == true &&
            state.autoDisplayLanesPathId != null && state.autoDisplayLanesPathId == state.acceptedPathId &&
            state.guidancePathId == state.acceptedPathId
        return if (valid) state.copy(lanes = state.autoDisplayLanes, lanesPathId = state.autoDisplayLanesPathId,
            lanesObservedAtMs = state.guidanceObservedAtMs) else state
    }

    private fun presentDisplay(state: NavigationState, oem: OemVehicleLaneState,
                               nowWallMs: Long?): LaneGuidancePresentation? {
        if (state.navigationMode !in setOf(NavigationMode.REALTIME, NavigationMode.SIMULATION)) return null
        val c3Status = decisionText(oem)
        if (state.navigationMode == NavigationMode.REALTIME && state.navAssistSourceStatus != "healthy") {
            val title = if (state.routeRecalculating) "路线已变化 · 请结束后重新规划" else when (state.navAssistSourceStatus) {
                "requiresExplicitStart" -> "导航数据已失效 · 请结束后重新启动"
                "budgetsUnconfirmed" -> "导航数据尚未验证 · 仅预览"
                else -> "导航数据待确认 · 仅预览"
            }
            val previewReady = state.navigationProvider == "AMAP_AUTO" && state.routePlanned &&
                state.routeMatched == true && !state.routeRecalculating && nowWallMs != null &&
                state.lanesObservedAtMs?.let { nowWallMs - it in 0..3_000 } == true &&
                state.guidanceObservedAtMs?.let { nowWallMs - it in 0..3_000 } == true &&
                state.lanesPathId != null && state.lanesPathId == state.acceptedPathId &&
                state.guidancePathId == state.acceptedPathId
            val preview = if (previewReady) state.lanes.sortedBy(LaneState::index).take(MAX_VISIBLE_LANES)
                .map { LaneGuidanceItem(it.index, symbols(it), it.recommended, it.prohibited) } else emptyList()
            return LaneGuidancePresentation(
                listOfNotNull(if (preview.isNotEmpty()) "高德车道推荐 · 仅预览" else title, c3Status).joinToString(" · "),
                preview, LaneRecommendationSide.NONE,
            )
        }
        val lanes = state.lanes.sortedBy(LaneState::index).take(MAX_VISIBLE_LANES)
        if (lanes.isEmpty()) {
            if (oem.receivedAtMs == 0L && state.laneCallbackCount == 0) return null
            val laneDiagnostic = when (state.laneLastEvent) {
                "hidden" -> "高德车道引导已隐藏（已驶过提示区）"
                "empty", "legacy_empty" -> "高德车道回调为空（当前路段无数据）"
                else -> "高德车道回调尚未触发"
            }
            return LaneGuidancePresentation(
                title = c3Status ?: if (state.laneCallbackCount > 0 || oem.receivedAtMs == 0L) {
                    laneDiagnostic
                } else {
                    "C3 选道状态未收到"
                },
                items = emptyList(),
                recommendationSide = LaneRecommendationSide.NONE,
            )
        }

        val recommendedIndices = lanes.filter(LaneState::recommended).map(LaneState::index)
        val side = recommendationSide(lanes, recommendedIndices)
        val position = when (oem.position.takeIf { oem.positionValid }) {
            OemLanePosition.LEFTMOST -> "最左车道"
            OemLanePosition.MIDDLE -> "中间车道"
            OemLanePosition.RIGHTMOST -> "最右车道"
            OemLanePosition.SINGLE -> "单车道"
            else -> "车道未知"
        }
        fun directionTitle(direction: String, allowed: Boolean): String {
            val permission = when {
                !oem.permissionValid -> "原车${direction}侧许可未知"
                allowed -> "原车报告${direction}侧许可"
                else -> "原车未报告${direction}侧许可"
            }
            return "路线建议靠$direction · 原车：$position · $permission · 等待 C3 判断"
        }
        val laneRouteConfirmed = state.unkeyedRouteFactsConfirmed ||
            (state.lanesPathId != null && state.lanesPathId == state.acceptedPathId &&
                state.guidancePathId == state.acceptedPathId)
        val title = if (!laneRouteConfirmed) {
            "高德车道提示 · 路线归属待确认，仅供参考"
        } else when (side) {
            LaneRecommendationSide.LEFT -> directionTitle("左", oem.leftAllowed)
            LaneRecommendationSide.RIGHT -> directionTitle("右", oem.rightAllowed)
            LaneRecommendationSide.MIXED -> "高德变道引导 · 推荐车道已标绿"
            LaneRecommendationSide.NONE -> "高德车道引导"
        }
        val comparisonReady = state.navigationMode == NavigationMode.REALTIME &&
            laneRouteConfirmed && state.routeMatched == true && !state.routeRecalculating &&
            oem.positionValid && state.lanes.size == lanes.size &&
            lanes.indices.all { lanes[it].index == it }
        val possibleIndices = if (comparisonReady) when (oem.position) {
            OemLanePosition.LEFTMOST -> listOf(0)
            OemLanePosition.RIGHTMOST -> listOf(lanes.lastIndex)
            OemLanePosition.SINGLE -> if (lanes.size == 1) listOf(0) else emptyList()
            OemLanePosition.MIDDLE -> if (lanes.size >= 3) (1 until lanes.lastIndex).toList() else emptyList()
            OemLanePosition.UNKNOWN -> emptyList()
        } else emptyList()
        val currentLaneStatus = when {
            state.navigationMode != NavigationMode.REALTIME -> "模拟导航 · 无法判定本车车道"
            !laneRouteConfirmed || state.routeMatched != true || state.routeRecalculating ->
                "路线与车道提示未对齐 · 无法判定本车车道"
            !oem.positionValid -> "原车车道位置未知 · 无法判定"
            lanes.none(LaneState::recommended) -> "高德未推荐车道 · 无法判定"
            state.lanes.size != lanes.size || lanes.indices.any { lanes[it].index != it } ->
                "高德车道编号不完整 · 无法判定"
            else -> when {
                possibleIndices.isEmpty() -> "原车位置与高德车道数无法对应 · 无法判定"
                possibleIndices.all { lanes[it].recommended } -> "原车报告：在推荐车道"
                possibleIndices.none { lanes[it].recommended } -> "原车报告：不在推荐车道"
                else -> "原车只报告中间车道 · 无法区分具体车道"
            }
        }
        val currentIndex = possibleIndices.singleOrNull()
        return LaneGuidancePresentation(
            title = title,
            items = lanes.map { lane ->
                LaneGuidanceItem(
                    index = lane.index,
                    symbols = symbols(lane),
                    recommended = lane.recommended,
                    prohibited = lane.prohibited,
                    current = lane.index == currentIndex,
                )
            },
            recommendationSide = side,
            currentLaneStatus = listOfNotNull(currentLaneStatus, c3Status).joinToString("\n"),
        )
    }

    fun decisionText(oem: OemVehicleLaneState): String? {
        val decision = oem.c3LaneDecision ?: return null
        val efficiency = decision.reason.startsWith("efficiency")
        val raw = decision.reason.removePrefix("efficiency:").removePrefix("efficiency").removePrefix("heuristic")
        val reason = raw.replaceFirstChar { it.lowercase() }
        val stage = when (reason) {
            "signaling" -> "已请求打灯 · 等待实体灯和跨线许可"
            "ready" -> "启动条件已就绪 · 等待模型启动"
            "changing" -> "C3 报告执行中"
            "laneChangeComplete" -> "C3 已确认变道完成"
            "laneChangeObserved" -> "变道观测已结束"
            "forkCompleted" -> "C3 已确认最终分叉完成"
            "laneChangeCancelled", "driverOverride" -> "司机接管或动作已取消"
            "laneChangeTimeout" -> "执行超时 · 已取消"
            "laneChangeCompletionUnconfirmed" -> "完成未确认 · 已停止重试"
            "laneChangeAnnouncementFailed" -> "播报未确认 · 动作已取消"
            "crossingWaitTimeout" -> "等待跨线许可超时 · 已取消"
            "edgeConfirmed", "alreadyInRecommendedLane" -> "已在推荐侧 · 保持当前车道"
            "waitingCrossing", "crossingLost" -> "等待允许跨越的车道线"
            "neighborUnknown", "neighborSpaceUnknown" -> "邻道空间未确认"
            "neighborObservationHold", "observing" -> "正在观测邻道空间"
            "neighborObservationPaused" -> "邻道观测暂停"
            "stabilizingLaneAlignment", "benefitStabilizing" -> "正在确认车道或收益"
            "navigationPriority", "turnApproachHandoff", "turnApproach", "turnApproachGrace" -> "导航转弯优先"
            "turnCompletion" -> "转弯结束 · 等待落位"
            "oem399LeftDenied" -> "原车否决左侧变道"
            "oem399RightDenied" -> "原车否决右侧变道"
            "oem399Stale", "permissionLost" -> "原车变道许可失效"
            "safetyBlocked" -> "盲区或原车安全条件阻止"
            "gapLost" -> "邻道距离或接近时间不足"
            "targetUnknown" -> "邻道目标不明"
            "noConfirmedBenefit", "benefitLost" -> "超车收益不足或已消失"
            "laneGeometryUnknown", "topologyTransition" -> "车道几何或目标归属待确认"
            "request" -> "已提出候选 · 等待变道协调"
            "cooldown" -> "等待再次评估"
            "changeLimit" -> "已达到本事件变道次数上限"
            "blockedEvent", "routeChanged" -> "事件已终止或路线已变化"
            "directionMismatch" -> "模型方向与请求不一致 · 已取消"
            "noRecommendedLane" -> "当前没有推荐车道"
            "health" -> "控制或输入资格未满足"
            "unavailable" -> "超车资格未满足"
            "idle" -> "当前没有选道动作"
            "visualNoLeftNeighbor" -> "视觉未确认左侧邻道"
            "visualNoRightNeighbor" -> "视觉未确认右侧邻道"
            "radarLeftTargetUnsafe" -> "左侧雷达目标不安全"
            "radarRightTargetUnsafe" -> "右侧雷达目标不安全"
            else -> "C3 反馈：${decision.reason}"
        }
        val direction = when (decision.direction) { "left" -> "向左 · "; "right" -> "向右 · "; else -> "" }
        val category = when {
            efficiency -> "超车"
            decision.reason.startsWith("heuristic") || reason in setOf("signaling", "ready", "changing",
                "edgeConfirmed", "waitingCrossing", "alreadyInRecommendedLane", "noRecommendedLane",
                "turnApproach", "turnApproachGrace", "turnApproachHandoff", "turnCompletion", "forkCompleted") -> "导航选道"
            else -> "变道"
        }
        val observed = if (oem.vehicleStateValid) when (oem.laneChangeState) {
            2 -> " · 模型正在启动变道"
            3 -> " · 模型正在结束变道"
            else -> ""
        } else ""
        return "C3 $category：$direction$stage$observed"
    }

    private fun recommendationSide(lanes: List<LaneState>, recommendedIndices: List<Int>): LaneRecommendationSide {
        if (recommendedIndices.isEmpty()) return LaneRecommendationSide.NONE
        val firstIndex = lanes.first().index
        val lastIndex = lanes.last().index
        val touchesLeft = firstIndex in recommendedIndices
        val touchesRight = lastIndex in recommendedIndices
        return when {
            touchesLeft && !touchesRight -> LaneRecommendationSide.LEFT
            touchesRight && !touchesLeft -> LaneRecommendationSide.RIGHT
            else -> LaneRecommendationSide.MIXED
        }
    }

    private fun symbols(lane: LaneState): String {
        val actions = lane.recommendedActions.ifEmpty { lane.allowedActions }
        if (actions.isEmpty()) return "·"
        return actions.distinct().joinToString(separator = "") { action ->
            when (action) {
                LaneAction.STRAIGHT -> "↑"
                LaneAction.LEFT -> "←"
                LaneAction.RIGHT -> "→"
                LaneAction.U_TURN, LaneAction.LEFT_U_TURN -> "↶"
                LaneAction.RIGHT_U_TURN -> "↷"
                LaneAction.BUS -> "公交"
                LaneAction.VARIABLE -> "可变"
                LaneAction.DEDICATED -> "专用"
                LaneAction.TIDAL -> "潮汐"
                LaneAction.UNKNOWN -> "×"
            }
        }
    }

    private const val MAX_VISIBLE_LANES = 16
}
