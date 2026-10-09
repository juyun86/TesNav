package com.garan.tesnav.ui

import org.junit.Assert.*
import org.junit.Test

class SpeedColorSettingsTest {
    private val orange = 0xffff9800.toInt()
    private val red = 0xffff5252.toInt()
    private val rules = listOf(SpeedColorRule(120f, red), SpeedColorRule(80f, orange))
    @Test fun absoluteThresholdsWorkWithoutNavigationAndReturnOnSpeedDrop() {
        val config = SpeedColorConfiguration(true, rules)
        assertEquals(-1, config.colorFor(79.9f))
        assertEquals(orange, config.colorFor(80f))
        assertEquals(orange, config.colorFor(119.9f))
        assertEquals(red, config.colorFor(120f))
        assertEquals(orange, config.colorFor(90f))
    }
    @Test fun absentInvalidDisabledAndEmptyMappingsStayWhite() {
        val config = SpeedColorConfiguration(true, rules)
        for (speed in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f)) assertEquals(-1, config.colorFor(speed))
        assertEquals(-1, SpeedColorConfiguration(false, rules).colorFor(130f))
        assertEquals(-1, SpeedColorConfiguration(true, emptyList()).colorFor(130f))
    }
    @Test fun settingsRoundTripAndMalformedRulesCannotBecomeColors() {
        assertEquals(rules.sortedBy { it.speedKph }, SpeedColorSettings.parse(SpeedColorSettings.encode(rules)))
        assertTrue(SpeedColorSettings.parse("NaN:ff000000;-1:ff000000;351:ff000000;80:broken;80:100000000").isEmpty())
        assertEquals(listOf(SpeedColorRule(80f, red)), SpeedColorSettings.parse("80:ffff9800;80:ffff5252"))
    }
}
