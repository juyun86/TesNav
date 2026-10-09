package com.garan.tesnav.ui

import com.garan.tesnav.service.AmapAutoBroadcastSnapshot
import org.junit.Assert.*
import org.junit.Test

class AmapAutoDisplayPresenterTest {
    private fun snapshot(action: String = "AUTONAVI_STANDARD_BROADCAST_SEND", type: Int = 10001,
                         values: Map<String, String?> = mapOf("CUR_SPEED" to "50", "LIMITED_SPEED" to "60")) =
        AmapAutoBroadcastSnapshot(action, type, values, 1000L)

    @Test fun `compact display excludes requests stale observations and invalid numbers`() {
        val nav = snapshot(values = mapOf("CUR_SPEED" to "50", "LIMITED_SPEED" to "60", "CAMERA_DIST" to "100",
            "CAMERA_TYPE" to "999", "routeRemainTrafficLightNum" to "0"))
        val data = mapOf(nav.interfaceKey to nav)
        assertTrue(AmapAutoDisplayPresenter.compact(data, 4000L).contains("限速 60 km/h"))
        assertTrue(AmapAutoDisplayPresenter.compact(data, 4000L).contains("类型 999"))
        assertTrue(AmapAutoDisplayPresenter.compact(data, 4000L).contains("剩余红绿灯 0 个"))
        assertEquals("", AmapAutoDisplayPresenter.compact(data, 4001L))
        assertEquals("", AmapAutoDisplayPresenter.compact(data, 999L))
        val request = snapshot(action = "AUTONAVI_STANDARD_BROADCAST_RECV")
        assertEquals("", AmapAutoDisplayPresenter.compact(mapOf(request.interfaceKey to request), 1000L))
        val invalid = snapshot(values = mapOf("CUR_SPEED" to "NaN", "LIMITED_SPEED" to "-1", "CAMERA_DIST" to "0"))
        assertEquals("", AmapAutoDisplayPresenter.compact(mapOf(invalid.interfaceKey to invalid), 1000L))
    }

    @Test fun `details keep all unknown interface fields label known fields and mark history`() {
        val known = snapshot()
        val unknown = snapshot(type = 98765, values = mapOf("futureField" to "value", "missingField" to null,
            "hugeField" to "a".repeat(2048)))
        val text = AmapAutoDisplayPresenter.details(mapOf(known.interfaceKey to known, unknown.interfaceKey to unknown))
        assertTrue(text.contains("不代表仍然有效"))
        assertTrue(text.contains("道路限速(km/h) [LIMITED_SPEED]：60"))
        assertTrue(text.contains("接口 98765"))
        assertTrue(text.contains("futureField：value"))
        assertTrue(text.contains("missingField：未提供"))
        assertTrue(text.contains("长字段仅显示前1024字符"))
    }

    @Test fun `same type input and output are distinct and empty state is explicit`() {
        val output = snapshot()
        val input = snapshot(action = "AUTONAVI_STANDARD_BROADCAST_RECV")
        val text = AmapAutoDisplayPresenter.details(mapOf(output.interfaceKey to output, input.interfaceKey to input))
        assertTrue(text.contains("车机输出"))
        assertTrue(text.contains("不是导航输出"))
        assertTrue(AmapAutoDisplayPresenter.details(emptyMap()).contains("尚未收到"))
    }
}
