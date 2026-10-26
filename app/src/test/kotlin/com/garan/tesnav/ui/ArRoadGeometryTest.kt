package com.garan.tesnav.ui

import com.garan.tesnav.model.*
import org.junit.Assert.*
import org.junit.Test

class ArRoadGeometryTest {
    private val json = """{"version":1,"frameId":100,"modelFrameId":99,"lagMs":50,"camera":"narrowRoad","lanes":[{"relative":0,"points":[[0.3,1],[0.4,0.7],[0.48,0.5],[0.52,0.5],[0.6,0.7],[0.7,1]]}]}"""
    @Test fun `network backlog cannot refresh stale geometry`() {
        val timing="""{"sentElapsedMs":1200,"sourceAgeMs":80}"""
        assertEquals(210L,ArRoadGeometry.ageUpperBound(timing,1000.0,5000,5300))
        assertNull(ArRoadGeometry.ageUpperBound(timing,1000.0,5000,5800))
        assertNull(ArRoadGeometry.ageUpperBound(timing,Double.NaN,5000,5300))
        assertNull(ArRoadGeometry.ageUpperBound(timing,1000.0,5400,5300))
        assertNull(ArRoadGeometry.ageUpperBound("{}",1000.0,5000,5300))
    }
    @Test fun `fresh clock samples recover drift without accepting backlog or expired synchronization`() {
        val timing = """{"sentElapsedMs":121200,"sourceAgeMs":140}"""
        // Old session anchor has accumulated 300ms clock divergence.
        assertNull(ArRoadGeometry.ageUpperBound(timing,1000.0,5000,124900))
        assertEquals(190L,ArRoadGeometry.ageUpperBound(timing,121000.0,124680,124900))
        // Small drift is covered conservatively, but a stopped clock poller is not.
        assertEquals(160L,ArRoadGeometry.ageUpperBound(timing,121000.0,124710,124900))
        assertNull(ArRoadGeometry.ageUpperBound(timing,121000.0,124680,125500))
        assertNull(ArRoadGeometry.ageUpperBound(timing,121000.0,120000,124900))
        assertNull(ArRoadGeometry.ageUpperBound(timing,121000.0,124800,124900))
    }
    @Test fun `accepts paired projected polygon`() { assertEquals(0, ArRoadGeometry.parse(json).single().relative) }
    @Test fun `separate open edge and lane paths keep identities and freshness`() {
        val paths=json.dropLast(1)+""", "edgePaths":[{"relative":1,"alpha":0.7,"points":[[0.6,1],[0.51,0.5]]}]}"""
        assertEquals(1,ArRoadGeometry.parsePaths(paths,"edgePaths").single().relative)
        assertEquals(2,ArRoadGeometry.parsePaths(paths,"edgePaths").single().points.size)
        assertTrue(ArRoadGeometry.parsePaths(json,"edgePaths").isEmpty())
        assertTrue(ArRoadGeometry.parsePaths(paths.replace("\"lagMs\":50","\"lagMs\":200"),"edgePaths").isEmpty())
        assertTrue(ArRoadGeometry.parsePaths(paths.replace("\"alpha\":0.7","\"alpha\":2"),"edgePaths").isEmpty())
        assertTrue(ArRoadGeometry.parsePaths(paths.replace("\"relative\":1","\"relative\":3"),"edgePaths").isEmpty())
    }
    @Test fun `rejects bad frame association geometry and version`() {
        for (bad in listOf("", json.replace("\"version\":1", "\"version\":2"),
            json.replace("\"modelFrameId\":99", "\"modelFrameId\":90"),
            json.replace("\"lagMs\":50", "\"lagMs\":151"), json.replace("\"lagMs\":50", "\"lagMs\":-1"),
            json.replace("\"relative\":0", "\"relative\":2"), json.replace("0.3", "9.3"))) {
            assertTrue(ArRoadGeometry.parse(bad).isEmpty())
        }
    }
    private val nav = NavigationState(navigationProvider="AMAP_AUTO", navigationMode=NavigationMode.REALTIME,
        routePlanned=true, routeMatched=true, navAssistSourceStatus="healthy", guidanceReceivedElapsedMs=1000,
        guidanceObservedAtMs=1000, lanesObservedAtMs=1000, acceptedPathId=1, guidancePathId=1, lanesPathId=1,
        lanes=(0..3).map { LaneState(it, recommended=it==2) })
    private val feedback = OemVehicleLaneState(position=OemLanePosition.RIGHTMOST, positionValid=true,
        receivedAtElapsedMs=1000)
    private fun targets(n:NavigationState=nav, o:OemVehicleLaneState=feedback, now:Long=1100) =
        ArRoadGeometry.recommendedRelative(n,o,now,now,500)
    private val observed = (-1..1).map { index -> ArRoadLane(index,listOf(
        .2f to .95f,.3f to .7f,.4f to .5f,.6f to .5f,.7f to .7f,.8f to .95f)) }
    @Test fun `road arrows follow confirmed priority and navigation intent`() {
        val recommended=ArRoadGeometry.navigationArrows(nav,feedback,observed,1100,1100,500)
        assertEquals(3,recommended.size)
        assertTrue(recommended.all { it.kind == "recommended" })
        val intent=ArRoadGeometry.navigationArrows(nav,feedback.copy(c3LaneDecision=C3LaneDecision("ready",true,"left")),observed,1100,1100,500)
        assertTrue(intent.all { it.kind == "intent" })
        assertTrue(ArRoadGeometry.navigationArrows(nav.copy(routeRecalculating=true),feedback,observed,1100,1100,500).isEmpty())
        assertTrue(ArRoadGeometry.navigationArrows(nav,feedback,observed,5000,5000,500).isEmpty())
    }
    @Test fun `unknown lane mapping stays on current surface and does not claim priority`() {
        val unknown=feedback.copy(positionValid=false)
        val cues=ArRoadGeometry.navigationArrows(nav,unknown,observed,1100,1100,500)
        assertEquals(3,cues.size)
        assertTrue(cues.none { it.kind in setOf("recommended","intent") })
        assertTrue(ArRoadGeometry.navigationArrows(nav,unknown,observed.filter { it.relative!=0 },1100,1100,500).isEmpty())
    }
    @Test fun `turn symbol only appears near maneuver and never invents a uturn path`() {
        val base=nav.copy(lanes=emptyList(),maneuver=NavigationManeuver.TURN_RIGHT,nextTurnDistanceMeters=1000)
        val far=ArRoadGeometry.navigationArrows(base,feedback,observed,1100,1100,500)
        assertTrue(far.all { it.kind=="alongRoad" })
        val near=ArRoadGeometry.navigationArrows(base.copy(nextTurnDistanceMeters=30),feedback,observed,1100,1100,500)
        assertTrue(near.all { it.kind=="direction" })
        assertNotEquals(far.map { it.points },near.map { it.points })
        assertTrue(ArRoadGeometry.navigationArrows(base.copy(nextTurnDistanceMeters=30,maneuver=NavigationManeuver.U_TURN_LEFT),feedback,observed,1100,1100,500).isEmpty())
        assertTrue(ArRoadGeometry.navigationArrows(base.copy(nextTurnDistanceMeters=30,maneuver=NavigationManeuver.UNKNOWN),feedback,observed,1100,1100,500).isEmpty())
        assertTrue(ArRoadGeometry.navigationArrows(nav.copy(lanes=nav.lanes.map { it.copy(prohibited=true) }),feedback,observed,1100,1100,500).isEmpty())
    }
    @Test fun `arrows scale with paired road geometry and mirror direction`() {
        val lane=observed[1]
        val right=ArRoadGeometry.arrowShapes(lane,1)
        val left=ArRoadGeometry.arrowShapes(lane,-1)
        assertEquals(3,right.size)
        right.zip(left).forEach { (a,b) -> a.zip(b).forEach { (p,q) ->
            assertEquals(1f,p.first+q.first,.0001f); assertEquals(p.second,q.second,.0001f)
        } }
        assertTrue(ArRoadGeometry.arrowShapes(lane.copy(points=lane.points.dropLast(1)),0).isEmpty())
    }
    @Test fun `maps adjacent recommendation using exact OEM position`() { assertEquals(setOf(-1), targets()) }
    @Test fun `does not project a distant recommendation into adjacent lane`() {
        assertTrue(targets(nav.copy(lanes=(0..3).map { LaneState(it, recommended=it==0) })).isEmpty())
    }
    @Test fun `ambiguous middle stale feedback and new route clear fill`() {
        assertTrue(targets(o=feedback.copy(position=OemLanePosition.MIDDLE)).isEmpty())
        assertTrue(targets(now=1500).isEmpty())
        assertTrue(targets(nav.copy(acceptedPathId=2)).isEmpty())
        assertTrue(targets(nav.copy(routeRecalculating=true)).isEmpty())
        assertTrue(targets(nav.copy(lanesObservedAtMs=1),now=3100).isEmpty())
    }
    @Test fun `prohibited lanes never get recommendation fill`() {
        assertTrue(targets(nav.copy(lanes=nav.lanes.map { it.copy(prohibited=true) })).isEmpty())
    }
    @Test fun `only live navigation intent gets a projected next lane`() {
        fun intent(reason:String, now:Long=1100) = ArRoadGeometry.decisionRelative(nav,
            feedback.copy(c3LaneDecision=C3LaneDecision(reason,true,"left")),now,now,500)
        assertEquals(-1,intent("heuristicSignaling"))
        assertEquals(-1,intent("ready"))
        assertNull(intent("changing"))
        for (reason in listOf("efficiency:changing","driverOverride","laneChangeCancelled","safetyBlocked","idle"))
            assertNull(intent(reason))
        assertNull(intent("changing",1500))
    }
    @Test fun `crossing cannot point into another lane after OEM position advances`() {
        val route = nav.copy(lanes=(0..2).map { LaneState(it,recommended=it==1) })
        fun intent(position:OemLanePosition, reason:String) = ArRoadGeometry.decisionRelative(route,
            feedback.copy(position=position,c3LaneDecision=C3LaneDecision(reason,true,"right")),1100,1100,500)
        assertEquals(1,intent(OemLanePosition.LEFTMOST,"ready"))
        assertNull(intent(OemLanePosition.LEFTMOST,"changing"))
        assertNull(intent(OemLanePosition.MIDDLE,"changing"))
        assertNull(intent(OemLanePosition.MIDDLE,"ready"))
        assertNull(intent(OemLanePosition.MIDDLE,"heuristicChanging"))
    }
}
