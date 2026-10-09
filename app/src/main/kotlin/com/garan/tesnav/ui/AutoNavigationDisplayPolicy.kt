package com.garan.tesnav.ui

import com.garan.tesnav.model.NavigationState

internal object AutoNavigationDisplayPolicy {
    fun guidanceFresh(state: NavigationState, elapsedMs: Long): Boolean = state.routePlanned &&
        state.guidanceReceivedElapsedMs?.let { elapsedMs - it in 0..3_000 } == true

    fun locationFresh(state: NavigationState, elapsedMs: Long, wallMs: Long): Boolean =
        state.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
            state.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true &&
            (state.locationReceivedElapsedMs?.let { elapsedMs - it in 0..3_000 }
                ?: state.locationObservedAtMs?.let { wallMs - it in 0..3_000 }) == true
}
