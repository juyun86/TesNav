package com.garan.tesnav.ui

import android.content.Context
import android.widget.FrameLayout

/** Original TesNav does not load a privileged car-map component. */
class EmbeddedAmapView(context: Context) : FrameLayout(context) {
    fun setActive(active: Boolean) = Unit
    fun dispose() = Unit
}
