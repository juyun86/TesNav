package com.garan.tesnav.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.garan.tesnav.model.NavigationState
import com.garan.tesnav.model.OemVehicleLaneState

/** AMap-style lane arrows with recommended lanes highlighted in green. */
class LaneGuidanceView(context: Context, private val feedbackDisplayBudgetMs: Long) : LinearLayout(context) {
    private val title = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
    }
    private val laneRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val currentLaneStatus = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(Color.WHITE)
    }
    private val telemetry = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 11f
        setTextColor(Color.LTGRAY)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(10))
        background = rounded(Color.argb(215, 24, 32, 38), Color.argb(120, 255, 255, 255))
        elevation = dp(8).toFloat()
        visibility = View.GONE
        addView(title, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(laneRow, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        addView(currentLaneStatus, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        addView(telemetry, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Keep multiline status readable in the narrow landscape instrument panel. */
    fun fitToPanel() {
        listOf(title, currentLaneStatus, telemetry).forEach {
            it.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val rowIndex = indexOfChild(laneRow)
        val rowParams = laneRow.layoutParams
        removeView(laneRow)
        addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(laneRow, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }, rowIndex, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (rowParams as LayoutParams).topMargin
        })
    }

    fun render(state: NavigationState, oem: OemVehicleLaneState = OemVehicleLaneState()) {
        val displayed = oem.forDisplay(SystemClock.elapsedRealtime(), feedbackDisplayBudgetMs)
        val presentation = LaneGuidancePresenter.present(state, displayed, System.currentTimeMillis())
        telemetry.text = displayed.navigationTelemetry?.displayText().orEmpty()
        telemetry.visibility = if (telemetry.text.isEmpty()) View.GONE else View.VISIBLE
        title.visibility = if (presentation == null) View.GONE else View.VISIBLE
        laneRow.visibility = if (presentation == null) View.GONE else View.VISIBLE
        currentLaneStatus.text = presentation?.currentLaneStatus.orEmpty()
        currentLaneStatus.visibility = if (currentLaneStatus.text.isEmpty()) View.GONE else View.VISIBLE
        currentLaneStatus.setTextColor(when (presentation?.currentLaneStatus) {
            "原车报告：在推荐车道" -> Color.rgb(94, 235, 151)
            "原车报告：不在推荐车道" -> Color.rgb(255, 185, 96)
            else -> Color.LTGRAY
        })
        if (presentation == null) {
            visibility = if (telemetry.text.isEmpty()) View.GONE else View.VISIBLE
            laneRow.removeAllViews()
            return
        }
        visibility = View.VISIBLE
        title.text = presentation.title
        laneRow.removeAllViews()
        presentation.items.forEachIndexed { position, lane ->
            laneRow.addView(
                TextView(context).apply {
                    gravity = Gravity.CENTER
                    text = (if (lane.current) "我 " else "") + if (lane.prohibited) "避" else lane.symbols
                    textSize = if (lane.current || lane.symbols.length > 2) 16f else 24f
                    setTypeface(typeface, if (lane.recommended) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (lane.recommended || lane.prohibited) Color.WHITE else Color.rgb(38, 50, 56))
                    setPadding(dp(7), dp(6), dp(7), dp(6))
                    contentDescription = "车道 ${lane.index + 1}${if (lane.current) "，本车" else ""}${if (lane.recommended) "，推荐" else ""}" +
                        "${if (lane.prohibited) "，当前路线避选" else ""}，${lane.symbols}"
                    background = rounded(
                        when {
                            lane.prohibited -> PROHIBITED_RED
                            lane.recommended -> RECOMMENDED_GREEN
                            else -> Color.argb(238, 255, 255, 255)
                        },
                        when {
                            lane.current -> Color.YELLOW
                            lane.prohibited -> PROHIBITED_RED
                            lane.recommended -> RECOMMENDED_GREEN
                            else -> Color.rgb(176, 190, 197)
                        },
                    )
                },
                LayoutParams(dp(58), dp(48)).apply {
                    if (position > 0) marginStart = dp(5)
                },
            )
        }
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(9).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val RECOMMENDED_GREEN = Color.rgb(20, 145, 82)
        val PROHIBITED_RED = Color.rgb(198, 40, 40)
    }
}
