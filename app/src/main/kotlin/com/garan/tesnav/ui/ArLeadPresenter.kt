package com.garan.tesnav.ui

import java.util.Locale

internal object ArLeadPresenter {
    fun text(lead: ArRoadLead): String {
        fun number(value: Float?, nonNegative: Boolean = true): String =
            value?.takeIf { it.isFinite() && (!nonNegative || it >= 0) }
                ?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
        val relative = lead.relativeSpeed.takeIf { it.isFinite() }
        val motion = when {
            relative == null -> "相对速度 —"
            relative < 0 -> "接近 ${number(-relative)} km/h"
            relative > 0 -> "远离 ${number(relative)} km/h"
            else -> "相对速度 0.0 km/h"
        }
        return "${number(lead.distance)} m · ${number(lead.speed)} km/h\n$motion"
    }
}
