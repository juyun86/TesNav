package com.garan.tesnav.ui

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import com.garan.tesnav.service.NavigationDataSource
import com.garan.tesnav.service.NavigationSourcePolicy

object NavigationSourceMenu {
    fun show(
        context: Context,
        anchor: View,
        current: NavigationDataSource,
        extendedSources: Boolean,
        onSelected: (NavigationDataSource) -> Unit,
    ) {
        PopupMenu(context, anchor).apply {
            val sources = NavigationSourcePolicy.visibleSources(extendedSources)
            sources.forEachIndexed { index, source ->
                menu.add(0, index, index, source.menuText).apply {
                    isCheckable = true
                    isChecked = source == current
                }
            }
            setOnMenuItemClickListener { item ->
                sources.getOrNull(item.itemId)?.let(onSelected)
                true
            }
            show()
        }
    }
}
