package com.garan.tesnav.export

/** Latest completed transport attempt, separate from local source qualification. */
data class NavAssistDeliveryState(
    val sessionId: String? = null,
    val sequence: Long? = null,
    val acknowledged: Boolean = false,
    val ackAtElapsedMs: Long? = null,
    val ownerConfirmed: Boolean = false,
    val routeActive: Boolean = false,
)
