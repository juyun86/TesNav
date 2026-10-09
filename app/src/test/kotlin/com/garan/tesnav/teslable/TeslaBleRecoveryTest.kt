package com.garan.tesnav.teslable

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TeslaBleRecoveryTest {
    @Test fun onlyKnownSessionFaultsRefreshAuthentication() {
        val refresh = setOf(5L, 6L, 15L, 17L, 26L)
        val retry = setOf(1L, 2L, 11L)
        for (code in 1L..28L) {
            val expected = when (code) {
                in refresh -> TeslaBleRecovery.Action.REFRESH_SESSION
                in retry -> TeslaBleRecovery.Action.RETRY_QUERY
                else -> TeslaBleRecovery.Action.RECONNECT
            }
            assertEquals("fault=$code", expected, TeslaBleRecovery.action(TeslaQueryFault(code), "query_reply"))
        }
        assertEquals(TeslaBleRecovery.Action.RECONNECT, TeslaBleRecovery.action(TeslaQueryFault(999), "query_reply"))
    }

    @Test fun writeOrAuthenticationFailuresNeverRetryQueriesOnAnUncertainConnection() {
        for (stage in listOf("connect", "authenticate", "query_write", "session_refresh")) {
            assertEquals(TeslaBleRecovery.Action.RECONNECT, TeslaBleRecovery.action(TeslaQueryFault(15), stage))
        }
        // Unauthenticated or malformed replies never become accepted destinations.
        assertEquals(TeslaBleRecovery.Action.RECONNECT,
            TeslaBleRecovery.action(IllegalArgumentException("invalid response"), "query_reply"))
    }

    @Test fun responseTimeoutCanRetryButWriteTimeoutMustReconnect() = runBlocking {
        val error = try {
            withTimeout(1) { delay(100) }
            error("timeout expected")
        } catch (e: TimeoutCancellationException) { e }
        assertEquals(TeslaBleRecovery.Action.RETRY_QUERY, TeslaBleRecovery.action(error, "query_reply"))
        assertEquals(TeslaBleRecovery.Action.RECONNECT, TeslaBleRecovery.action(error, "query_write"))
    }

    @Test fun repeatedFailuresBackOffAndSuccessResetsDelay() {
        assertEquals(listOf(5000L, 10000L, 20000L, 30000L, 30000L),
            (1..5).map(TeslaBleRecovery::reconnectDelayMs))
        assertEquals(5000L, TeslaBleRecovery.reconnectDelayMs(0))
        assertEquals(30000L, TeslaBleRecovery.reconnectDelayMs(Int.MAX_VALUE))
    }

    @Test fun persistentErrorsCannotLoopForeverOnOneConnection() {
        val busy = TeslaQueryFault(1)
        val expired = TeslaQueryFault(15)
        assertEquals(TeslaBleRecovery.Action.RETRY_QUERY, TeslaBleRecovery.boundedAction(busy, "query_reply", 1, 1))
        assertEquals(TeslaBleRecovery.Action.RECONNECT, TeslaBleRecovery.boundedAction(busy, "query_reply", 2, 0))
        assertEquals(TeslaBleRecovery.Action.REFRESH_SESSION, TeslaBleRecovery.boundedAction(expired, "query_reply", 2, 0))
        assertEquals(TeslaBleRecovery.Action.RECONNECT, TeslaBleRecovery.boundedAction(expired, "query_reply", 0, 1))
        // Successful query resets both budgets in the client.
        assertEquals(TeslaBleRecovery.Action.RETRY_QUERY, TeslaBleRecovery.boundedAction(busy, "query_reply", 0, 0))
    }
}
