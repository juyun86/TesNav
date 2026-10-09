package com.garan.tesnav.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Button

internal data class SpeedColorRule(val speedKph: Float, val color: Int)
internal data class SpeedColorConfiguration(val enabled: Boolean, val rules: List<SpeedColorRule>) {
    fun colorFor(speed: Float?): Int {
        if (!enabled || speed == null || !speed.isFinite() || speed < 0f) return Color.WHITE
        return rules.filter { it.speedKph <= speed }.maxByOrNull { it.speedKph }?.color ?: Color.WHITE
    }
}

/** Configurable absolute-speed colors, following Dash's speed/color mapping settings. */
internal object SpeedColorSettings {
    const val PREFS = "instrument_speed_colors"
    const val ENABLED = "enabled"
    private const val RULES = "rules"
    fun parse(value: String): List<SpeedColorRule> = value.split(';').mapNotNull { item ->
        val parts = item.split(':')
        if (parts.size != 2) return@mapNotNull null
        val speed = parts[0].toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..350f } ?: return@mapNotNull null
        val color = parts[1].toLongOrNull(16)?.takeIf { it in 0..0xffffffffL } ?: return@mapNotNull null
        SpeedColorRule(speed, color.toInt())
    }.associateBy { it.speedKph }.values.sortedBy { it.speedKph }
    fun encode(rules: List<SpeedColorRule>): String = rules.sortedBy { it.speedKph }.joinToString(";") {
        "${it.speedKph}:${(it.color.toLong() and 0xffffffffL).toString(16)}"
    }
    fun read(context: Context): SpeedColorConfiguration {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SpeedColorConfiguration(prefs.getBoolean(ENABLED, true), parse(prefs.getString(RULES, "").orEmpty()))
    }
    private fun save(context: Context, rules: List<SpeedColorRule>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(RULES, encode(rules)).apply()
    }
    fun showEditor(context: Context) {
        val rules = read(context).rules
        val labels = rules.map { "从 ${it.speedKph.toString().removeSuffix(".0")} km/h 起 · #${String.format("%06X", it.color and 0xffffff)}" }.toTypedArray()
        AlertDialog.Builder(context).setTitle("车速颜色阈值")
            .setItems(labels) { _, index -> edit(context, rules[index]) }
            .setPositiveButton("添加") { _, _ -> edit(context, null) }
            .setNeutralButton("清空规则") { _, _ -> save(context, emptyList()) }
            .setNegativeButton("关闭", null).show()
    }
    private fun edit(context: Context, existing: SpeedColorRule?) {
        val speedInput = EditText(context).apply {
            hint = "起始速度（km/h）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            existing?.let { setText(it.speedKph.toString().removeSuffix(".0")) }
        }
        val colorInput = EditText(context).apply {
            hint = "颜色，例如 #FF5252"
            setText(existing?.let { String.format("#%06X", it.color and 0xffffff) } ?: "#FF5252")
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(speedInput); addView(colorInput)
            addView(Button(context).apply {
                text = "选择颜色"
                setOnClickListener {
                    val colors = listOf("白色" to "#FFFFFF", "绿色" to "#66BB6A", "黄色" to "#FFEB3B", "橙色" to "#FF9800", "红色" to "#FF5252", "蓝色" to "#42A5F5")
                    AlertDialog.Builder(context).setTitle("选择颜色").setItems(colors.map { it.first }.toTypedArray()) { _, index ->
                        colorInput.setText(colors[index].second)
                    }.show()
                }
            })
        }
        val builder = AlertDialog.Builder(context).setTitle(if (existing == null) "添加车速颜色" else "编辑车速颜色")
            .setView(body).setPositiveButton("保存", null).setNegativeButton("取消", null)
        if (existing != null) builder.setNeutralButton("删除") { _, _ ->
            save(context, read(context).rules.filter { it.speedKph != existing.speedKph })
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val speed = speedInput.text.toString().toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..350f }
                if (speed == null) { speedInput.error = "请输入 0–350 km/h 的速度"; return@setOnClickListener }
                val color = runCatching { Color.parseColor(colorInput.text.toString().trim()) }.getOrNull()
                if (color == null) { colorInput.error = "请输入有效颜色，例如 #FF5252"; return@setOnClickListener }
                val rules = read(context).rules.filter { it.speedKph != existing?.speedKph && it.speedKph != speed }
                save(context, rules + SpeedColorRule(speed, color))
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
