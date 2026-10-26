package com.garan.tesnav.ui

import org.junit.Assert.*
import org.junit.Test

class EmbeddedAmapLifecycleTest {
    @Test fun landscapeDisplayFitsSquarePaneWithoutStretchingAndTouchMapsBack() {
        val fit = embeddedDisplayViewport(1795, 1528, 1795, 1528)!!
        assertEquals(1f, fit.scale, .001f)
        assertEquals(0f, fit.left, .001f)
        assertEquals(0f, fit.top, .001f)
        assertEquals(897.5f, (fit.left+fit.width/2f-fit.left)/fit.scale, .001f)
        assertEquals(764f, (fit.top+fit.height/2f-fit.top)/fit.scale, .001f)
    }

    @Test fun retainedDisplayRemainsProportionalAfterRotationAndRejectsUnmeasuredViews() {
        val fit = embeddedDisplayViewport(900, 1600, 1600, 900)!!
        assertEquals(.5625f, fit.scale, .001f)
        assertEquals(1600f/900f, fit.width/fit.height, .001f)
        assertEquals(900f, (fit.top+fit.height-fit.top)/fit.scale, .001f)
        assertNull(embeddedDisplayViewport(0, 1600, 1600, 900))
    }
    @Test fun releasingOrReplacingTheDisplayInvalidatesQueuedWorkAndLateResults() {
        assertTrue(embeddedTaskCurrent(7, 7, true, true, false))
        assertFalse(embeddedTaskCurrent(7, 8, true, true, false))
        assertFalse(embeddedTaskCurrent(8, 7, true, true, false))
    }

    @Test fun backgroundDetachedAndDisposedViewsCannotOperateTheRemoteDisplay() {
        assertFalse(embeddedTaskCurrent(7, 7, false, true, false))
        assertFalse(embeddedTaskCurrent(7, 7, true, false, false))
        assertFalse(embeddedTaskCurrent(7, 7, true, true, true))
    }
}
