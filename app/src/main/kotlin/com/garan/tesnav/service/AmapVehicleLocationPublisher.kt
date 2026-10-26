package com.garan.tesnav.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.garan.tesnav.model.*

/** Sends qualified CAN observations to the paired, signature-protected Auto receiver. */
internal class AmapVehicleLocationPublisher(private val context: Context) {
    private val observations = VehicleLocationObservation()
    private val selection = NavigationLocationFallback()
    private var sending = false

    fun update(enabled: Boolean, feedback: OemVehicleLaneState) {
        if (!enabled) { close(); return }
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val result = selection.select(null, observations.read(feedback, elapsed, wall), true, elapsed, wall)
        if (result.source != NavigationLocationSource.VEHICLE) { close(); return }
        val fix = result.vehicleFixToInject ?: return
        if (fix.coordinateSystem != NavigationCoordinateSystem.GCJ02) { close(); return }
        context.sendBroadcast(Intent(ACTION).setPackage("com.autonavi.amapauto")
            .putExtra("valid", true).putExtra("coordinateSystem", "GCJ02")
            .putExtra("observedWallMs", fix.measuredAtMs)
            .putExtra("observedElapsedMs", fix.receivedElapsedMs - fix.ageAtReceiptMs)
            .putExtra("latitude", fix.latitude).putExtra("longitude", fix.longitude)
            .putExtra("accuracyM", fix.accuracyM!!).putExtra("speedMps", fix.speedMps!!)
            .putExtra("bearingDeg", fix.bearingDeg!!))
        sending = true
    }

    fun close() {
        if (sending) context.sendBroadcast(Intent(ACTION).setPackage("com.autonavi.amapauto")
            .putExtra("valid", false))
        sending = false
        // Keep observation clock history: repeated ACKs must not become new fixes after an outage.
        selection.reset()
    }

    private companion object {
        const val ACTION = "com.garan.tesnav.tenengkai.VEHICLE_LOCATION"
    }
}
