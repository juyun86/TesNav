package com.garan.tesnav.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.garan.tesnav.model.NavigationState
import java.util.Locale

/** Guidance graphics from the selected car-navigation feed, never a second SDK route. */
class AutoNavigationGuidanceView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var state = NavigationState()
    private var fresh = false
    fun render(value: NavigationState, isFresh: Boolean) {
        state = value; fresh = isFresh; invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        canvas.save(); canvas.scale(d, d)
        paint.color = Color.rgb(18, 103, 71); paint.style = Paint.Style.FILL
        canvas.drawRoundRect(0f, 0f, width / d, height / d, 16f, 16f, paint)
        paint.color = Color.WHITE
        val direction = state.maneuver.name
        val left = direction.contains("LEFT")
        val right = direction.contains("RIGHT")
        val known = direction !in listOf("NONE", "UNKNOWN", "ROUNDABOUT", "DESTINATION")
        if (fresh && known) {
            val path = Path()
            val x = if (left) 25f else if (right) 77f else 51f
            val y = if (left || right) 41f else 19f
            path.moveTo(51f, 82f)
            if (direction.contains("U_TURN")) {
                path.lineTo(51f, 41f); path.quadTo(x, 12f, x, 53f)
            } else if (left || right) {
                path.lineTo(51f, 41f); path.lineTo(x, y)
            } else path.lineTo(x, y)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 9f
            paint.strokeJoin = Paint.Join.ROUND; canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            val head = Path()
            if (direction.contains("U_TURN")) {
                head.moveTo(x - 12, 47f); head.lineTo(x + 12, 47f); head.lineTo(x, 66f)
            } else if (left || right) {
                val tail = x + if (left) 15f else -15f
                head.moveTo(x, y); head.lineTo(tail, y - 13); head.lineTo(tail, y + 13)
            } else {
                head.moveTo(x, y - 10); head.lineTo(x - 13, y + 10); head.lineTo(x + 13, y + 10)
            }
            head.close(); canvas.drawPath(head, paint)
        } else {
            paint.textSize = 36f; canvas.drawText(if (fresh) "●" else "—", 35f, 65f, paint)
        }
        val meters = state.nextTurnDistanceMeters
        val distance = if (!fresh) "引导已过期" else when {
            meters == null -> "距离未知"
            meters >= 1000 -> String.format(Locale.CHINA, "%.1f 公里", meters / 1000.0)
            else -> "$meters 米"
        }
        paint.textSize = 28f; paint.isFakeBoldText = true
        canvas.drawText(distance, 102f, 44f, paint)
        paint.textSize = 16f; paint.isFakeBoldText = false
        val road = if (fresh) state.nextRoad.orEmpty() else "等待车机更新"
        val available = width / d - 118
        val count = paint.breakText(road, true, available.coerceAtLeast(0f), null)
        canvas.drawText(road.take(count), 102f, 76f, paint)
        canvas.restore()
    }
}
