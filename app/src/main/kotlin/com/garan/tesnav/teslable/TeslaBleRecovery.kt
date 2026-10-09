package com.garan.tesnav.teslable

import kotlinx.coroutines.TimeoutCancellationException

internal class TeslaQueryFault(val code: Long) : IllegalArgumentException("原车协议拒绝查询 ($code)")

/** Bounded recovery for the single read-only request; never broadens authentication. */
internal object TeslaBleRecovery {
    enum class Action { RETRY_QUERY, REFRESH_SESSION, RECONNECT }

    fun action(error: Exception, stage: String): Action = when {
        stage != "query_reply" -> Action.RECONNECT
        error is TeslaQueryFault && error.code in setOf(5L, 6L, 15L, 17L, 26L) -> Action.REFRESH_SESSION
        error is TeslaQueryFault && error.code in setOf(1L, 2L, 11L) -> Action.RETRY_QUERY
        error is TimeoutCancellationException -> Action.RETRY_QUERY
        else -> Action.RECONNECT
    }

    fun boundedAction(error: Exception, stage: String, queryRetries: Int, sessionRefreshes: Int): Action =
        when (val candidate = action(error, stage)) {
            Action.RETRY_QUERY -> if (queryRetries < 2) candidate else Action.RECONNECT
            Action.REFRESH_SESSION -> if (sessionRefreshes < 1) candidate else Action.RECONNECT
            Action.RECONNECT -> candidate
        }

    fun reconnectDelayMs(consecutiveFailures: Int): Long = when (consecutiveFailures) {
        0, 1 -> 5_000L
        2 -> 10_000L
        3 -> 20_000L
        else -> 30_000L
    }
}
