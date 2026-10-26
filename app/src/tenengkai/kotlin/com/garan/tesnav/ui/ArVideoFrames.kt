package com.garan.tesnav.ui

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

internal data class ArVideoFrame(val jpeg: ByteArray, val geometry: String, val received: Long, val generation: Long)

/** One latest frame only; no Bitmap is retained by the service and no video is written to disk. */
object ArVideoFrames {
    internal val nativeCameraOpen = MutableStateFlow(false)
    @JvmStatic fun setNativeCameraOpen(open: Boolean) { nativeCameraOpen.value = open }
    internal val nativeArPreferred = MutableStateFlow(false)
    @JvmStatic fun setNativeArPreferred(preferred: Boolean) { nativeArPreferred.value = preferred }
    @Volatile internal var generation = 0L
        private set
    internal val latest = MutableStateFlow<ArVideoFrame?>(null)
    private var accepted = 0L
    private var rejected = 0L
    private var loggedAt = 0L
    private var rejectLoggedAt = 0L
    @JvmStatic @Synchronized fun publish(jpeg: ByteArray, geometry: String, epoch: Double, requestStart: Long): Long {
        val now = SystemClock.elapsedRealtime()
        val age = if (geometry.isEmpty()) 0L else ArRoadGeometry.ageUpperBound(geometry, epoch, requestStart, now)
        if (age == null) rejected++ else accepted++
        if (now-loggedAt >= 5_000 || (age == null && now-rejectLoggedAt >= 1_000)) {
            if (age == null) rejectLoggedAt = now
            loggedAt = now
            val raw = runCatching {
                val root = com.google.gson.JsonParser.parseString(geometry).asJsonObject
                val source = root["sourceAgeMs"].asDouble
                val upper = now-requestStart-(root["sentElapsedMs"].asDouble-epoch)+source
                "sourceMs=${source.toInt()} rawUpperMs=${upper.toInt()} sampleMs=${now-requestStart} ageMs=${age ?: -1}"
            }.getOrDefault("timing=missing")
            Log.i("Tenengkai-ArFrames", "accepted=$accepted rejected=$rejected $raw")
        }
        // A rejected arrival is not a disconnect. The visible frame owns its
        // existing expiry; do not blank it early because one arrival was late.
        if (age == null) return -1L
        if (latest.subscriptionCount.value > 0)
            latest.value = ArVideoFrame(jpeg, geometry, now-age, generation)
        return now-age
    }
    @JvmStatic @Synchronized fun clear() { generation++; latest.value = null }
}
