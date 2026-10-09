package com.garan.tesnav.ui

import com.garan.tesnav.export.NavAssistDeliveryState

internal object NavigationDeliveryPresenter {
    fun text(delivery: NavAssistDeliveryState, nowElapsedMs: Long): String {
        if (delivery.sequence == null) return "尚未发送 · 尚无 C3 回执"
        val identity = "序号 ${delivery.sequence}"
        if (!delivery.acknowledged) return "发送尝试 $identity · 本次未收到 C3 回执"
        val fresh = delivery.ackAtElapsedMs?.let { nowElapsedMs - it in 0..3_000 } == true
        if (!fresh) return "最近回执 $identity 已过期"
        if (!delivery.ownerConfirmed) return "C3 已回执 $identity · 所有者握手中"
        return if (delivery.routeActive) "C3 已回执有效导航 $identity"
        else "C3 已回执状态包 $identity · 导航尚未获本地资格或正在握手"
    }
}
