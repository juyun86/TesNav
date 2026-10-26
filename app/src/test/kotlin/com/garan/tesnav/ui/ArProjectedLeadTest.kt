package com.garan.tesnav.ui

import org.junit.Assert.*
import org.junit.Test

class ArProjectedLeadTest {
    private val json = """{"version":1,"frameId":100,"modelFrameId":99,"lagMs":50,"camera":"narrowRoad",
        "radarDeltaMs":40,"leads":[{"index":0,"x":0.4,"y":0.6,"distanceM":35.4,"speedKph":80,"relativeSpeedKph":-12.5,"radar":true}]}"""
    @Test fun `position and metrics come from same projected lead`() {
        val lead = ArRoadGeometry.parseLeads(json).single()
        assertEquals(.4f,lead.x); assertTrue(lead.radar)
        assertTrue(ArLeadPresenter.text(lead).contains("35.4 m · 80.0 km/h"))
        assertTrue(ArLeadPresenter.text(lead).contains("接近 12.5"))
    }
    @Test fun `bad association and offscreen targets disappear`() {
        for (bad in listOf(json.replace("\"radarDeltaMs\":40","\"radarDeltaMs\":151"),
            json.replace("\"modelFrameId\":99","\"modelFrameId\":80"),
            json.replace("\"x\":0.4","\"x\":1.4"),json.replace("\"distanceM\":35.4","\"distanceM\":-1"),
            json.replace("\"speedKph\":80","\"speedKph\":-1"))) assertTrue(ArRoadGeometry.parseLeads(bad).isEmpty())
        assertTrue(ArRoadGeometry.parseLeads("{}").isEmpty())
    }
    @Test fun `zero speed and opening direction are retained`() {
        val lead = ArRoadGeometry.parseLeads(json).single()
        assertTrue(ArLeadPresenter.text(lead.copy(speed=0f,relativeSpeed=0f)).contains("0.0 km/h"))
        assertTrue(ArLeadPresenter.text(lead.copy(relativeSpeed=3f)).contains("远离 3.0"))
    }
}
