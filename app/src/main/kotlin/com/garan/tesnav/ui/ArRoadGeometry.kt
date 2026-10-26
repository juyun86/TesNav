package com.garan.tesnav.ui

import com.google.gson.JsonParser
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.OemVehicleLaneState

internal data class ArRoadLane(val relative: Int, val points: List<Pair<Float, Float>>, val alpha: Float = 1f)
internal data class ArRoadLead(val index: Int, val x: Float, val y: Float, val distance: Float,
                              val speed: Float, val relativeSpeed: Float, val radar: Boolean)
internal data class ArRoadArrow(val points: List<Pair<Float, Float>>, val kind: String)

internal object ArRoadGeometry {
    /** Symbols mapped inside observed lane surfaces, not fabricated junction trajectories. */
    fun navigationArrows(state: NavigationState, oem: OemVehicleLaneState, lanes: List<ArRoadLane>,
                         elapsed: Long, wall: Long, budget: Long): List<ArRoadArrow> {
        if (state.navigationProvider != "AMAP_AUTO" ||
            state.navigationMode != com.garan.tesnav.model.NavigationMode.REALTIME ||
            !AutoNavigationDisplayPolicy.guidanceFresh(state, elapsed) || state.routeRecalculating ||
            state.routeMatched != true || state.navAssistSourceStatus != "healthy") return emptyList()
        val targets = recommendedRelative(state, oem, elapsed, wall, budget)
        val intent = decisionRelative(state, oem, elapsed, wall, budget)
        val display = ArLaneGuidancePresenter.presentation(state, oem, elapsed, wall, budget)
        if (display?.items?.isNotEmpty() == true && display.items.all { it.prohibited }) return emptyList()
        val mapped = lanes.filter { it.relative in targets || it.relative == intent }
        val chosen = if (mapped.isNotEmpty()) mapped else lanes.filter { it.relative == 0 }
        val side = when (display?.recommendationSide) {
            LaneRecommendationSide.LEFT -> -1
            LaneRecommendationSide.RIGHT -> 1
            else -> 0
        }
        val maneuver = state.maneuver.name
        val near = state.nextTurnDistanceMeters?.let { it in 0..50 } == true
        // A U-turn/roundabout needs actual junction geometry; the ordinary card retains it.
        if (near && (maneuver.contains("U_TURN") || maneuver in setOf("ROUNDABOUT", "DESTINATION", "NONE", "UNKNOWN"))) return emptyList()
        val turn = if (near) when {
            maneuver.contains("LEFT") -> -1
            maneuver.contains("RIGHT") -> 1
            else -> 0
        } else 0
        return chosen.flatMap { lane ->
            val kind = when {
                lane.relative == intent -> "intent"
                lane.relative in targets -> "recommended"
                side != 0 || turn != 0 -> "direction"
                else -> "alongRoad"
            }
            // Unknown absolute position: directional cue remains inside the current
            // observed lane, never claims the neighbouring lane is the route target.
            val direction = if (turn != 0) turn else if (mapped.isEmpty()) side else 0
            arrowShapes(lane, direction).map { ArRoadArrow(it, kind) }
        }
    }

    internal fun arrowShapes(lane: ArRoadLane, direction: Int): List<List<Pair<Float, Float>>> {
        val n = lane.points.size / 2
        if (n < 3 || lane.points.size % 2 != 0 || direction !in -1..1 ||
            lane.points.any { !it.first.isFinite() || !it.second.isFinite() }) return emptyList()
        // Source polygon is left near->far, right far->near at paired ground samples.
        fun point(u: Float, t: Float): Pair<Float, Float> {
            val at = t.coerceIn(0f,1f)*(n-1)
            val i = at.toInt().coerceAtMost(n-2); val f = at-i
            fun side(right: Boolean): Pair<Float, Float> {
                fun p(index: Int) = lane.points[if (right) 2*n-1-index else index]
                val a=p(i); val b=p(i+1)
                return a.first+(b.first-a.first)*f to a.second+(b.second-a.second)*f
            }
            val a=side(false); val b=side(true)
            return a.first+(b.first-a.first)*u to a.second+(b.second-a.second)*u
        }
        return listOf(.12f,.38f,.66f).map { t ->
            val shape = if (direction == 0) listOf(
                .43f to t, .57f to t, .57f to t+.08f, .78f to t+.08f,
                .5f to t+.18f, .22f to t+.08f, .43f to t+.08f)
            else listOf(.43f to t, .57f to t, .57f to t+.08f, .66f to t+.08f,
                .66f to t+.03f, .85f to t+.12f, .66f to t+.20f,
                .66f to t+.15f, .43f to t+.15f).map { (u,v) -> (if(direction<0) 1f-u else u) to v }
            shape.map { (u,v) -> point(u,v) }
        }.filter { shape -> shape.all { (x,y) -> x in 0f..1f && y in 0f..1f } }
    }
    fun parseLeads(json: String): List<ArRoadLead> = runCatching {
        val root = checkedRoot(json)
        val leads = root["leads"]?.asJsonArray ?: return emptyList()
        require(leads.size() <= 2)
        val delta = root["radarDeltaMs"].asDouble
        require(delta.isFinite() && delta in 0.0..150.0)
        leads.map { value ->
            val lead = value.asJsonObject
            fun number(key: String): Float = lead[key].asFloat.also { require(it.isFinite()) }
            val index = lead["index"].asString.toInt(); require(index in 0..1)
            val x = number("x"); val y = number("y")
            val distance = number("distanceM"); val speed = number("speedKph")
            require(x in 0f..1f && y in 0f..1f && distance > 0 && speed >= 0)
            require(lead["radar"].asJsonPrimitive.isBoolean)
            ArRoadLead(index,x,y,distance,speed,number("relativeSpeedKph"),lead["radar"].asBoolean)
        }.also { require(it.map(ArRoadLead::index).distinct().size == it.size) }
    }.getOrDefault(emptyList())

    /** Short-lived request/response clock sample; never accumulate drift over a video session. */
    fun ageUpperBound(json: String, epoch: Double, requestStart: Long, now: Long): Long? = runCatching {
        require(json.length <= 16_384 && epoch.isFinite() && epoch > 0 && now-requestStart in 0..3_000)
        val root = JsonParser.parseString(json).asJsonObject
        val sent = root["sentElapsedMs"].asDouble; val sourceAge = root["sourceAgeMs"].asDouble
        require(sent.isFinite() && sourceAge.isFinite() && sourceAge in 0.0..500.0)
        // Reserve 30ms for rate drift during this bounded 3s sample lifetime.
        // Source age is a lower bound even when the two clock rates differ.
        val transport = now-requestStart-(sent-epoch)
        require(transport >= -30.0)
        val upper = sourceAge + (transport + 30.0).coerceAtLeast(0.0)
        require(upper.isFinite() && upper in 0.0..500.0)
        kotlin.math.ceil(upper).toLong()
    }.getOrNull()

    /** Immediate navigation intent, not the final absolute target or permission to cross. */
    fun decisionRelative(state: NavigationState, oem: OemVehicleLaneState,
                         elapsed: Long, wall: Long, budget: Long): Int? {
        val displayed = ArLaneGuidancePresenter.presentation(state, oem, elapsed, wall, budget) ?: return null
        val current = displayed.items.singleOrNull { it.current } ?: return null
        val decision = oem.forDisplay(elapsed, budget).c3LaneDecision ?: return null
        if (decision.reason.startsWith("efficiency")) return null
        val reason = decision.reason.removePrefix("heuristic").replaceFirstChar { it.lowercase() }
        // During crossing the OEM current lane can already have advanced. Without an
        // action target ID, adding direction again would point into the following lane.
        if (reason !in setOf("signaling", "ready")) return null
        if (current.recommended) return null
        val relative = when (decision.direction) { "left" -> -1; "right" -> 1; else -> return null }
        return relative.takeIf { displayed.items.any { lane -> lane.index == current.index+relative && !lane.prohibited } }
    }

    fun parse(json: String): List<ArRoadLane> = parsePaths(json, "lanes")

    private fun checkedRoot(json: String): com.google.gson.JsonObject {
        require(json.length <= 16_384)
        val root = JsonParser.parseString(json).asJsonObject
        require(root["version"].asInt == 1)
        require(root["camera"].asString in setOf("narrowRoad", "wideRoad"))
        val lag = root["lagMs"].asDouble
        require(lag.isFinite() && lag in 0.0..150.0)
        val frame = root["frameId"].asLong; val model = root["modelFrameId"].asLong
        require(frame in 0..0xffffffffL && model in 0..0xffffffffL && kotlin.math.abs(frame-model) <= 3)
        return root
    }

    fun parsePaths(json: String, field: String): List<ArRoadLane> = runCatching {
        require(field in setOf("lanes", "linePaths", "edgePaths"))
        val root = checkedRoot(json)
        val lanes = root[field]?.asJsonArray ?: return emptyList()
        val indices = when (field) { "linePaths" -> 0..3; "edgePaths" -> 0..1; else -> -1..1 }
        require(lanes.size() <= indices.count())
        lanes.map { entry ->
            val obj = entry.asJsonObject
            val relative = obj["relative"].asInt
            require(relative in indices)
            val points = obj["points"].asJsonArray
            require(points.size() in (if (field == "lanes") 6 else 2)..64)
            val alpha = obj["alpha"]?.asFloat ?: 1f
            require(alpha.isFinite() && alpha in 0f..1f)
            ArRoadLane(relative, points.map { point ->
                val xy = point.asJsonArray; require(xy.size() == 2)
                val x = xy[0].asFloat; val y = xy[1].asFloat
                require(x.isFinite() && y.isFinite() && x in -4f..4f && y in -4f..4f)
                x to y
            }, alpha)
        }.also { require(it.map(ArRoadLane::relative).distinct().size == it.size) }
    }.getOrDefault(emptyList())

    /** Only map adjacent geometry when the existing presenter uniquely locates the car.
     * Multiple recommendations stay multiple; this does not invent a C3 target index. */
    fun recommendedRelative(state: NavigationState, oem: OemVehicleLaneState,
                            elapsed: Long, wall: Long, budget: Long): Set<Int> {
        val displayed = ArLaneGuidancePresenter.presentation(state, oem, elapsed, wall, budget) ?: return emptySet()
        val current = displayed.items.singleOrNull { it.current } ?: return emptySet()
        return displayed.items.filter { it.recommended && !it.prohibited }
            .map { it.index-current.index }.filter { it in -1..1 }.toSet()
    }
}
