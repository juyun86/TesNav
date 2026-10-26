package com.garan.tesnav.ui

import com.garan.tesnav.model.GeoPoint
import kotlin.math.*

/** Geometry for manual map comparison only; never exported as control input. */
internal object NavigationMapGeometry {
    const val SIZE = 256
    const val EGO_X = 128.0
    const val EGO_Y = 192.0
    private const val EARTH_CIRCUMFERENCE = 40075016.68557849
    private val metersPerDegree = 2 * PI * 6371008.8 / 360

    data class Pose(val point: GeoPoint, val bearing: Double)
    data class Pixel(val x: Double, val y: Double)

    fun valid(point: GeoPoint) = point.latitude.isFinite() && point.longitude.isFinite() &&
        abs(point.latitude) < 85 && point.longitude in -180.0..180.0

    fun correctedZoom(zoom: Float, metersPerPixel: Float): Float {
        require(zoom.isFinite() && metersPerPixel.isFinite() && metersPerPixel > 0)
        return (zoom + log2(metersPerPixel.toDouble())).toFloat()
    }

    fun scaleMatches(metersPerPixel: Float) = metersPerPixel.isFinite() && abs(metersPerPixel - 1f) <= .05f

    fun measuredScale(referenceMeters: Double, measuredPixels: Double): Float {
        require(referenceMeters.isFinite() && referenceMeters > 0 && measuredPixels.isFinite() && measuredPixels > 0)
        return (referenceMeters / measuredPixels).toFloat()
    }

    fun project(point: GeoPoint, pose: Pose): Pixel {
        require(valid(point) && valid(pose.point) && pose.bearing.isFinite())
        fun mercatorY(latitude: Double) = (1 - asinh(tan(Math.toRadians(latitude))) / PI) / 2
        var dx = (point.longitude - pose.point.longitude) / 360
        dx = ((dx + .5) % 1 + 1) % 1 - .5
        val scale = EARTH_CIRCUMFERENCE * cos(Math.toRadians(pose.point.latitude))
        dx *= scale
        val dy = (mercatorY(point.latitude) - mercatorY(pose.point.latitude)) * scale
        val bearing = Math.toRadians(pose.bearing)
        return Pixel(EGO_X + dx * cos(bearing) + dy * sin(bearing),
            EGO_Y - dx * sin(bearing) + dy * cos(bearing))
    }

    /** A single frozen fix, matching the official tracker's initial projection and +/-50m heading chord. */
    fun routePose(route: List<GeoPoint>, observed: GeoPoint): Pose? {
        if (route.size !in 2..20_000 || !valid(observed) || route.any { !valid(it) }) return null
        val origin = route.first()
        val lonScale = metersPerDegree * cos(Math.toRadians(origin.latitude))
        fun x(p: GeoPoint) = (p.longitude - origin.longitude) * lonScale
        fun y(p: GeoPoint) = (p.latitude - origin.latitude) * metersPerDegree
        val distance = DoubleArray(route.size)
        var nearestDistance = Double.POSITIVE_INFINITY
        var progress = 0.0
        for (i in 0 until route.lastIndex) {
            val a = route[i]
            val b = route[i + 1]
            val length = hypot((b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2)),
                b.latitude - a.latitude) * metersPerDegree
            distance[i + 1] = distance[i] + length
            val dx = x(b) - x(a)
            val dy = y(b) - y(a)
            val fraction = (((x(observed) - x(a)) * dx + (y(observed) - y(a)) * dy) /
                max(dx * dx + dy * dy, 1e-12)).coerceIn(0.0, 1.0)
            val gap = hypot(x(a) + fraction * dx - x(observed), y(a) + fraction * dy - y(observed))
            if (gap < nearestDistance) {
                nearestDistance = gap
                progress = distance[i] + fraction * length
            }
        }
        if (distance.last() <= 0) return null
        fun at(meters: Double): GeoPoint {
            val target = meters.coerceIn(0.0, distance.last())
            val i = (0 until route.lastIndex).firstOrNull { distance[it + 1] >= target && distance[it + 1] > distance[it] }
                ?: (route.lastIndex - 1)
            val ratio = if (distance[i + 1] > distance[i]) (target - distance[i]) / (distance[i + 1] - distance[i]) else 0.0
            return GeoPoint(route[i].latitude + ratio * (route[i + 1].latitude - route[i].latitude),
                route[i].longitude + ratio * (route[i + 1].longitude - route[i].longitude))
        }
        val before = at(progress - 50)
        val after = at(progress + 50)
        val bearing = Math.toDegrees(atan2((after.longitude - before.longitude) * cos(Math.toRadians(before.latitude)),
            after.latitude - before.latitude)).let { (it + 360) % 360 }
        return Pose(at(progress), bearing)
    }
}
