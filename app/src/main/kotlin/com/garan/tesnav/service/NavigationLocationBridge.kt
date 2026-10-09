package com.garan.tesnav.service

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng
import com.garan.tesnav.data.NavigationRepository
import com.garan.tesnav.model.*

/** Independent phone recovery probe; injected SDK output can never populate this listener. */
internal class NavigationLocationBridge(
    context: Context,
    private val repository: NavigationRepository,
    private val onPlanningLocationChanged: () -> Unit = {},
) {
    private val traceDirectory = context.applicationContext.filesDir
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val coordinateConverter = CoordinateConverter(context.applicationContext)
    private val vehicle = VehicleLocationObservation()
    private var rawPhone: NavigationLocationObservation? = null
    private var planningPhone: NavigationLocationObservation? = null
    private var listening = false
    private var active = false
    private var phoneHandbackAt: Long? = null
    private var lastSource: NavigationLocationSource? = null
    private var lastTraceElapsed = 0L
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.isFromMockProvider || !location.hasAccuracy()) return
            val now = SystemClock.elapsedRealtime()
            val age = now - location.elapsedRealtimeNanos / 1_000_000
            if (age !in 0L..2999L) return
            val fix = NavigationLocationObservation("phone-gps", location.time, now, age,
                location.latitude, location.longitude, location.accuracy.toDouble(),
                location.speed.toDouble().takeIf { location.hasSpeed() },
                location.bearing.toDouble().takeIf { location.hasBearing() }, NavigationCoordinateSystem.WGS84, true)
            // Android GPS is independent of the navigator's emulator. Convert to the
            // same GCJ02 system as NaviPoi without renewing the original observation time.
            val planningFix = runCatching {
                val point = coordinateConverter.from(CoordinateConverter.CoordType.GPS)
                    .coord(LatLng(fix.latitude, fix.longitude)).convert()
                fix.copy(latitude = point.latitude, longitude = point.longitude,
                    coordinateSystem = NavigationCoordinateSystem.GCJ02)
            }.getOrNull()
            planningPhone = planningFix
            repository.updatePlanningLocation(planningFix)
            if (location.hasSpeed() && location.hasBearing()) rawPhone = fix
            onPlanningLocationChanged()
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            rawPhone = null
            planningPhone = null
            repository.updatePlanningLocation(null)
            onPlanningLocationChanged()
        }
        @Deprecated("Legacy callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun update(state: NavigationState, feedback: OemVehicleLaneState) {
        val enabled = state.navigationMode == NavigationMode.REALTIME && state.routePlanned && !state.routeRecalculating
        if (!enabled && !repository.needsIndependentPlanningLocation) {
            if (listening) { close(); repository.updateExternalLocation(null, null) }
            return
        }
        if (!listening) {
            listening = runCatching {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
                true
            }.getOrDefault(false)
        }
        if (!enabled) {
            if (active) {
                repository.updateExternalLocation(null, null)
                phoneHandbackAt = null
                vehicle.reset()
            }
            active = false
            return
        }
        active = true
        val now = SystemClock.elapsedRealtime()
        val wasExternal = repository.usingVehicleLocation
        val sdkPhone = repository.phoneLocationObservation
        val handback = phoneHandbackAt
        if (handback != null && (sdkPhone?.receivedElapsedMs ?: -1) >= handback) phoneHandbackAt = null
        val waitingForSdk = phoneHandbackAt?.let { now - it < 3000 } == true
        val phone = if (wasExternal || waitingForSdk) rawPhone else sdkPhone
        val result = repository.updateExternalLocation(phone, vehicle.read(feedback, now, System.currentTimeMillis()))
        if (wasExternal && result.source == NavigationLocationSource.PHONE) phoneHandbackAt = now
        if (result.source != lastSource || now - lastTraceElapsed >= 5000) {
            val line = "source=${result.source} reason=${result.reason} external=${repository.usingVehicleLocation} " +
                "phoneWeak=${repository.phoneGpsSignalWeak} sdkPhoneAgeMs=${sdkPhone?.let { now-it.receivedElapsedMs+it.ageAtReceiptMs }} " +
                "rawPhoneAgeMs=${rawPhone?.let { now-it.receivedElapsedMs+it.ageAtReceiptMs }} " +
                "vehicleStatus=${feedback.navigationTelemetry?.gpsStatus} vehiclePositionAgeMs=${feedback.navigationTelemetry?.gpsPositionAgeMs}"
            android.util.Log.i("NavigationLocation", line)
            if (com.garan.tesnav.BuildConfig.DEBUG) com.garan.tesnav.util.NavigationTrace.append(traceDirectory, line, "NavigationLocation")
            lastSource = result.source
            lastTraceElapsed = now
        }
    }

    fun latestPlanningLocation(): NavigationLocationObservation? = planningPhone

    fun close() {
        if (listening) manager.removeUpdates(listener)
        listening = false; active = false; rawPhone = null; planningPhone = null; vehicle.reset()
        repository.updatePlanningLocation(null)
        phoneHandbackAt = null; lastSource = null; lastTraceElapsed = 0L
    }
}
