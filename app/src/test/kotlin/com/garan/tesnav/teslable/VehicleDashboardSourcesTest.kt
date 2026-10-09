package com.garan.tesnav.teslable
import org.junit.Assert.*
import org.junit.Test
class VehicleDashboardSourcesTest {
    private fun sample(t: Long, cost: Long = 100) = DashboardObservation(30f, t, cost, 2000)
    @Test fun deadPrimaryFallsBackAndDoesNotKeepItsValue() {
        val s = DashboardSourceSelector()
        assertEquals("TITA", s.select("speed", sample(1000), null, 1000)!!.source)
        assertEquals("原车蓝牙", s.select("speed", sample(1000), sample(2900), 3100)!!.source)
        assertNull(s.select<Float>("speed", sample(1000), sample(2900), 5000))
    }
    @Test fun switchingRequiresThreeDifferentFresherSamples() {
        val s = DashboardSourceSelector(); val official = sample(1000, 500)
        s.select("speed", null, official, 1000)
        assertEquals("原车蓝牙", s.select("speed", sample(1100), official, 1100)!!.source)
        repeat(8) { assertEquals("原车蓝牙", s.select("speed", sample(1100), official, 1110)!!.source) }
        assertEquals("原车蓝牙", s.select("speed", sample(1200), official, 1200)!!.source)
        assertEquals("TITA", s.select("speed", sample(1300), official, 1300)!!.source)
    }
    @Test fun smallDifferenceDoesNotOscillateAndFieldsChooseIndependently() {
        val s = DashboardSourceSelector()
        s.select("speed", null, sample(1000), 1000)
        assertEquals("原车蓝牙", s.select("speed", sample(1050), sample(1000), 1050)!!.source)
        assertEquals("TITA", s.select("battery", sample(1050), null, 1050)!!.source)
        assertNull(s.select<Float>("range", sample(1200), null, 1100))
    }
    private fun response(drive: ByteArray, charge: ByteArray? = null) = TeslaWire.b(1, byteArrayOf()) +
        TeslaWire.b(2, TeslaWire.b(5, drive) + (charge?.let { TeslaWire.b(3, it) } ?: byteArrayOf()))
    @Test fun parsesOptionalOfficialFieldsAndConvertsMilesWithoutInventingZero() {
        val drive = TeslaWire.f(106, 10.5f.toRawBits()) + TeslaWire.v(102, 9) + TeslaWire.b(1, TeslaWire.b(2, byteArrayOf()))
        val charge = TeslaWire.v(114, 67) + TeslaWire.f(111, 100f.toRawBits())
        val d = TeslaNavigationProtocol.dashboard(response(drive, charge), 1000, 250, VehicleDashboardData())
        assertEquals(16.898112f, d.speed!!.value, 0.001f); assertEquals("P", d.gear!!.value)
        assertEquals(67, d.battery!!.value); assertEquals(160.9344f, d.range!!.value, 0.001f)
        val next = TeslaNavigationProtocol.dashboard(response(byteArrayOf()), 2000, 100, d)
        assertNull(next.speed); assertNull(next.gear); assertEquals(1000L, next.battery!!.receivedAtMs)
    }
    @Test fun absentOrInvalidChargeFieldsClearOnlyWhenChargeIsPresent() {
        val bad = TeslaWire.v(114, 101) + TeslaWire.f(111, Float.NaN.toRawBits())
        val d = TeslaNavigationProtocol.dashboard(response(TeslaWire.v(102, 0), bad), 1000, 100, VehicleDashboardData())
        assertEquals(0f, d.speed!!.value, 0f); assertNull(d.battery); assertNull(d.range)
    }
    @Test fun combinedQueryIsStillOnlyReadRequestsAndDefaultIsUnchanged() {
        val vehicleAction = TeslaWire.bytes(TeslaNavigationProtocol.readDriveAndChargeState, 2)!!
        val request = TeslaWire.bytes(vehicleAction, 1)!!
        assertEquals(setOf(2, 4), TeslaWire.fields(request).map { it.number }.toSet())
        assertEquals(listOf(4), TeslaWire.fields(TeslaWire.bytes(TeslaWire.bytes(TeslaNavigationProtocol.readDriveState, 2)!!, 1)!!).map { it.number })
    }
    private fun routeDrive(energy: Float? = 18f, minutes: Float? = 30.5f, active: Boolean = true): ByteArray {
        val coords = TeslaWire.f(1, 31f.toRawBits()) + TeslaWire.f(2, 121f.toRawBits())
        return (if (active) TeslaWire.b(12, coords) else byteArrayOf()) +
            (energy?.let { TeslaWire.f(11, it.toRawBits()) } ?: byteArrayOf()) +
            (minutes?.let { TeslaWire.f(8, it.toRawBits()) } ?: byteArrayOf())
    }
    @Test fun readsOriginalRouteArrivalEstimatesAndExpires() {
        val d = TeslaNavigationProtocol.dashboard(response(routeDrive()), 1000, 100, VehicleDashboardData())
        assertEquals(18f, d.arrivalBattery!!.value, 0f)
        assertEquals(30.5f, d.minutesToArrival!!.value, 0f)
        assertTrue(d.arrivalBattery!!.fresh(3999))
        assertFalse(d.arrivalBattery!!.fresh(4000))
        assertFalse(d.minutesToArrival!!.fresh(4000))
        val stopped = TeslaNavigationProtocol.dashboard(response(routeDrive(active = false)), 2000, 100, d)
        assertNull(stopped.arrivalBattery); assertNull(stopped.minutesToArrival)
    }
    @Test fun missingInvalidAndZeroArrivalValuesAreHandledIndependently() {
        val d = TeslaNavigationProtocol.dashboard(response(routeDrive(energy = Float.NaN)), 1000, 100, VehicleDashboardData())
        assertNull(d.arrivalBattery); assertNotNull(d.minutesToArrival)
        val bad = TeslaNavigationProtocol.dashboard(response(routeDrive(energy = 101f, minutes = -1f)), 1000, 100, d)
        assertNull(bad.arrivalBattery); assertNull(bad.minutesToArrival)
        val absent = TeslaNavigationProtocol.dashboard(response(routeDrive(energy = null, minutes = null)), 2000, 100, d)
        assertNull(absent.arrivalBattery); assertNull(absent.minutesToArrival)
        val zero = TeslaNavigationProtocol.dashboard(response(routeDrive(energy = 0f, minutes = 0f)), 1000, 100, d)
        assertEquals(0f, zero.arrivalBattery!!.value, 0f)
        assertEquals(0f, zero.minutesToArrival!!.value, 0f)
        val negative = TeslaNavigationProtocol.dashboard(response(routeDrive(energy = -5f)), 1000, 100, d)
        assertEquals(-5f, negative.arrivalBattery!!.value, 0f)
    }
}
