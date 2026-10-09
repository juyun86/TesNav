package com.garan.tesnav.ui

import com.garan.tesnav.export.NavAssistDeliveryState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDeliveryPresenterTest {
    @Test fun `local qualification cannot produce reception evidence without a transport receipt`() {
        assertTrue(NavigationDeliveryPresenter.text(NavAssistDeliveryState(), 100).contains("尚无 C3 回执"))
        val attempt = NavAssistDeliveryState("session", 10, routeActive = true)
        assertFalse(NavigationDeliveryPresenter.text(attempt, 100).contains("已回执"))
        val handshake = attempt.copy(acknowledged = true, ackAtElapsedMs = 100)
        assertTrue(NavigationDeliveryPresenter.text(handshake, 100).contains("握手中"))
        assertFalse(NavigationDeliveryPresenter.text(handshake, 100).contains("有效导航"))
        val confirmed = handshake.copy(ownerConfirmed = true)
        assertTrue(NavigationDeliveryPresenter.text(confirmed, 100).contains("有效导航 序号 10"))
        assertTrue(NavigationDeliveryPresenter.text(confirmed, 3_101).contains("已过期"))
        assertFalse(NavigationDeliveryPresenter.text(confirmed.copy(routeActive = false), 100).contains("有效导航"))
    }
}
