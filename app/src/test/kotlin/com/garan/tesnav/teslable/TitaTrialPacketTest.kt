package com.garan.tesnav.teslable

import org.junit.Assert.*
import org.junit.Test

class TitaTrialPacketTest {
    private fun frame(battery: Int = 46, miles: Int = 116) = ByteArray(76).apply {
        val charge = (battery shl 10) or miles
        repeat(4) { this[40 + it] = (charge ushr (it * 8)).toByte() }
    }
    @Test fun observedChargeFieldsMatchInstrumentAndIgnoreOldOffsets() {
        val sample = TitaTrialPacket.decode(frame().apply {
            this[0] = 32; this[2] = 67; this[8] = 34; this[9] = 1
        })!!
        assertEquals(46, sample.batteryPercent)
        assertEquals(186.6839f, sample.rangeKm!!, 0.001f)
        assertEquals(187, kotlin.math.round(sample.rangeKm!!).toInt())
        assertEquals(188.29325f, TitaTrialPacket.decode(frame(miles = 117))!!.rangeKm!!, 0.001f)
    }
    @Test fun truncatedAndOtherPacketLengthsAreRejected() {
        for (length in listOf(0, 20, 75, 77)) assertNull(TitaTrialPacket.decode(frame().copyOf(length)))
    }
    @Test fun unavailableValuesDoNotHideOtherValidFields() {
        val invalidBattery = TitaTrialPacket.decode(frame(battery = 127))!!
        assertNull(invalidBattery.batteryPercent)
        assertNotNull(invalidBattery.rangeKm)
        val invalidRange = TitaTrialPacket.decode(frame(miles = 1023))!!
        assertEquals(46, invalidRange.batteryPercent)
        assertNull(invalidRange.rangeKm)
        assertNull(TitaTrialPacket.decode(frame(battery = 101))!!.batteryPercent)
        val zero = TitaTrialPacket.decode(frame(battery = 0, miles = 0))!!
        assertEquals(0, zero.batteryPercent)
        assertEquals(0f, zero.rangeKm!!, 0f)
    }
}
