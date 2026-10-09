package com.garan.tesnav.ui

import com.garan.tesnav.model.NavigationState
import org.junit.Assert.*
import org.junit.Test

class AutoNavigationDisplayPolicyTest {
    @Test fun guidanceExpiresEvenWithoutAnotherBroadcast() {
        val state = NavigationState(routePlanned = true, guidanceReceivedElapsedMs = 100)
        assertTrue(AutoNavigationDisplayPolicy.guidanceFresh(state, 3100))
        assertFalse(AutoNavigationDisplayPolicy.guidanceFresh(state, 3101))
        assertFalse(AutoNavigationDisplayPolicy.guidanceFresh(state, 99))
        assertFalse(AutoNavigationDisplayPolicy.guidanceFresh(state.copy(routePlanned = false), 100))
    }
    @Test fun monotonicLocationAgeOverridesWallClockChanges() {
        val state = NavigationState(latitude = 32.0, longitude = 119.0,
            locationReceivedElapsedMs = 100, locationObservedAtMs = 1000)
        assertTrue(AutoNavigationDisplayPolicy.locationFresh(state, 200, -9000))
        assertFalse(AutoNavigationDisplayPolicy.locationFresh(state, 3101, 1000))
        assertFalse(AutoNavigationDisplayPolicy.locationFresh(state.copy(latitude = Double.NaN), 200, 1000))
        assertFalse(AutoNavigationDisplayPolicy.locationFresh(state.copy(longitude = 181.0), 200, 1000))
    }
    @Test fun missingLocationNeverClaimsACurrentVehicle() {
        assertFalse(AutoNavigationDisplayPolicy.locationFresh(NavigationState(), 0, 0))
        val state = NavigationState(latitude = 32.0, longitude = 119.0, locationObservedAtMs = 100)
        assertTrue(AutoNavigationDisplayPolicy.locationFresh(state, 100, 3100))
        assertFalse(AutoNavigationDisplayPolicy.locationFresh(state, 100, 3101))
    }
    @Test fun oldGuidanceCannotRemainAsLiveSummary() {
        val state = NavigationState(routePlanned = true, guidanceReceivedElapsedMs = 100L,
            routeRemainDistanceMeters = 112, routeRemainTimeSeconds = 30)
        assertTrue(AutoNavigationDisplayPolicy.guidanceFresh(state, 3100))
        assertFalse(AutoNavigationDisplayPolicy.guidanceFresh(state, 3101))
        assertFalse(AutoNavigationDisplayPolicy.guidanceFresh(state, 99))
        assertFalse(AutoNavigationDisplayPolicy.guidanceFresh(state.copy(routePlanned = false), 200))
        assertFalse(AutoNavigationDisplayPolicy.guidanceFresh(state.copy(guidanceReceivedElapsedMs = null), 200))
    }
}
