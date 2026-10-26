package com.garan.tesnav.service

import org.junit.Assert.*
import org.junit.Test

class TeslaDestinationRecoveryTest {
    @Test fun cooldownExpiresAtItsBoundary() {
        assertFalse(teslaDestinationRetryAllowed(true, 1, 5000, 4999))
        assertTrue(teslaDestinationRetryAllowed(true, 1, 5000, 5000))
        assertTrue(teslaDestinationRetryAllowed(true, 1, 5000, 5001))
    }

    @Test fun threeRetriesThenWaitForAnExplicitReset() {
        for (failures in 1..3) assertTrue(teslaDestinationRetryAllowed(true, failures, 0, 100000))
        assertFalse(teslaDestinationRetryAllowed(true, 4, 0, 100000))
        assertFalse(teslaDestinationRetryAllowed(true, Int.MAX_VALUE, 0, Long.MAX_VALUE))
    }

    @Test fun disabledSyncAndUnusedBudgetCannotReplayATarget() {
        assertFalse(teslaDestinationRetryAllowed(false, 1, 0, 100000))
        assertFalse(teslaDestinationRetryAllowed(true, 0, 0, 100000))
        assertFalse(teslaDestinationRetryAllowed(true, -1, 0, 100000))
    }
}
