package com.garan.tesnav.teslable

internal data class DashboardObservation<T>(val value: T, val receivedAtMs: Long, val refreshCostMs: Long, val budgetMs: Long) {
    fun fresh(now: Long) = now >= receivedAtMs && now - receivedAtMs < budgetMs
    fun score(now: Long) = now - receivedAtMs + refreshCostMs.coerceAtLeast(0)
}
internal data class VehicleDashboardData(
    val speed: DashboardObservation<Float>? = null,
    val battery: DashboardObservation<Int>? = null,
    val range: DashboardObservation<Float>? = null,
    val gear: DashboardObservation<String>? = null,
    val arrivalBattery: DashboardObservation<Float>? = null,
    val minutesToArrival: DashboardObservation<Float>? = null,
)
internal data class DashboardSelection<T>(val observation: DashboardObservation<T>, val source: String)

/** Compare update timeliness, not measured end-to-end vehicle latency. */
internal class DashboardSourceSelector {
    private data class Choice(var source: String, var challenger: String? = null, var wins: Int = 0, var observedAt: Long? = null)
    private val choices = mutableMapOf<String, Choice>()
    fun clear() = choices.clear()
    fun <T> select(field: String, tita: DashboardObservation<T>?, official: DashboardObservation<T>?, now: Long): DashboardSelection<T>? {
        val available = listOfNotNull(tita?.takeIf { it.fresh(now) }?.let { DashboardSelection(it, "TITA") },
            official?.takeIf { it.fresh(now) }?.let { DashboardSelection(it, "原车蓝牙") })
        val best = available.minByOrNull { it.observation.score(now) } ?: run { choices.remove(field); return null }
        val choice = choices[field]
        val current = available.firstOrNull { it.source == choice?.source }
        if (current == null || choice == null) { choices[field] = Choice(best.source); return best }
        if (best.source == current.source || current.observation.score(now) - best.observation.score(now) < 150) {
            choice.challenger = null; choice.wins = 0; choice.observedAt = null; return current
        }
        if (choice.challenger != best.source) { choice.challenger = best.source; choice.wins = 0; choice.observedAt = null }
        if (choice.observedAt != best.observation.receivedAtMs) { choice.wins++; choice.observedAt = best.observation.receivedAtMs }
        if (choice.wins < 3) return current
        choices[field] = Choice(best.source)
        return best
    }
}
