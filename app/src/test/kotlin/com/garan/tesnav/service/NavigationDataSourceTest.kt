package com.garan.tesnav.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDataSourceTest {
    @Test fun `HA toggle cannot disarm Auto or manually started API navigation`() {
        val synced = com.garan.tesnav.model.NavigationState(startedFromTeslaSync = true)
        assertFalse(NavigationSourcePolicy.syncOwnsNavigation(NavigationDataSource.AMAP_AUTO, synced))
        assertFalse(NavigationSourcePolicy.syncOwnsNavigation(NavigationDataSource.AMAP_API,
            synced.copy(startedFromTeslaSync = false)))
        assertTrue(NavigationSourcePolicy.syncOwnsNavigation(NavigationDataSource.AMAP_API, synced))
    }
    @Test fun `selected consumers use Auto while hidden API navigation stays independent`() {
        val api = com.garan.tesnav.model.NavigationState(
            navigationMode = com.garan.tesnav.model.NavigationMode.REALTIME, nextRoad = "API旧路线")
        val auto = api.copy(nextRoad = "车机路线", navigationProvider = NavigationDataSource.AMAP_AUTO.name)
        assertEquals(auto, NavigationSourcePolicy.stateFor(NavigationDataSource.AMAP_AUTO, api, auto))
        assertEquals(api, NavigationSourcePolicy.stateFor(NavigationDataSource.AMAP_API, api, auto))
        assertTrue(NavigationSourcePolicy.canSearch(NavigationDataSource.AMAP_AUTO, api.navigationMode))
        assertFalse(NavigationSourcePolicy.canSearch(NavigationDataSource.AMAP_API, api.navigationMode))
        val idle = api.copy(navigationMode = com.garan.tesnav.model.NavigationMode.IDLE)
        assertEquals(auto, NavigationSourcePolicy.stateFor(NavigationDataSource.AMAP_AUTO, idle, auto))
        assertTrue(NavigationSourcePolicy.canSearch(NavigationDataSource.AMAP_AUTO, idle.navigationMode))
    }
    @Test fun `keeps working sources separate from reserved provider interfaces`() {
        assertEquals("来源：高德 API", NavigationDataSource.AMAP_API.buttonText)
        assertEquals("来源：高德车机", NavigationDataSource.AMAP_AUTO.buttonText)
        assertTrue(NavigationDataSource.AMAP_API.integrated)
        assertTrue(NavigationDataSource.AMAP_AUTO.integrated)
        assertFalse(NavigationDataSource.TENCENT_API.integrated)
        assertFalse(NavigationDataSource.GOOGLE_API.integrated)
        assertEquals("腾讯 API（待配置）", NavigationDataSource.TENCENT_API.menuText)
    }

    @Test fun `TesNav exposes only its two existing Amap sources`() {
        assertEquals(
            listOf(NavigationDataSource.AMAP_API, NavigationDataSource.AMAP_AUTO),
            NavigationSourcePolicy.visibleSources(extendedSources = false),
        )
        assertEquals("来源：API", NavigationSourcePolicy.buttonText(NavigationDataSource.AMAP_API, false))
        assertEquals("来源：车机", NavigationSourcePolicy.buttonText(NavigationDataSource.AMAP_AUTO, false))
        assertFalse(NavigationSourcePolicy.isVisible(NavigationDataSource.TENCENT_API, false))
        assertFalse(NavigationSourcePolicy.canSelect(NavigationDataSource.GOOGLE_API, false))
    }

    @Test fun `Tenengkai exposes provider extensions without granting unconfigured sources`() {
        assertEquals(NavigationDataSource.entries, NavigationSourcePolicy.visibleSources(extendedSources = true))
        assertTrue(NavigationSourcePolicy.isVisible(NavigationDataSource.TENCENT_API, true))
        assertFalse(NavigationSourcePolicy.canSelect(NavigationDataSource.TENCENT_API, true))
        assertFalse(NavigationSourcePolicy.canSelect(NavigationDataSource.GOOGLE_API, true))
    }

    @Test fun `persisted extension source cannot enter TesNav or bypass integration state`() {
        assertEquals(
            NavigationDataSource.AMAP_API,
            NavigationSourcePolicy.restore(NavigationDataSource.TENCENT_API.name, extendedSources = false),
        )
        assertEquals(
            NavigationDataSource.AMAP_API,
            NavigationSourcePolicy.restore(NavigationDataSource.GOOGLE_API.name, extendedSources = true),
        )
        assertEquals(
            NavigationDataSource.AMAP_AUTO,
            NavigationSourcePolicy.restore(NavigationDataSource.AMAP_AUTO.name, extendedSources = false),
        )
    }
}
