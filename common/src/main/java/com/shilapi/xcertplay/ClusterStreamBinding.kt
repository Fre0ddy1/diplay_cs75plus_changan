package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.ScreenStreamGeometry

/** UI-thread state for the two instrument streams. SETUP order is only the initial guess. */
internal class ClusterStreamBinding {
    private val pictures = mutableMapOf<Int, ScreenStreamGeometry>()
    var mapType = 111
        private set
    val cardType get() = if (mapType == 111) 112 else 111

    fun observe(type: Int, picture: ScreenStreamGeometry, panelWidth: Int): Boolean {
        if (type !in 111..112 || !picture.valid) return false
        pictures[type] = picture
        if (panelWidth <= 480) return false // No panel measurement, or no distinguishable map width.
        // Only distinguish the declared small card from a near-panel-width map. Unknown/intermediate
        // sizes, including two full-panel pictures, must not flip the binding or enlarge the card.
        val map = pictures.filterValues { it.cropWidth > 480 && it.cropWidth.toLong() * 4 >= panelWidth.toLong() * 3 }
        val card = pictures.filterValues { it.cropWidth <= 480 }
        val selected = when {
            map.size == 1 && card.size <= 1 -> map.keys.single()
            map.isEmpty() && card.size == 1 && pictures.size == 1 -> if (card.keys.single() == 111) 112 else 111
            else -> return false
        }
        if (selected == mapType) return false
        mapType = selected
        return true
    }

    fun cardGeometry(): ScreenStreamGeometry? = pictures[cardType]?.takeIf { it.cropWidth <= 480 }
    fun forget(type: Int) { pictures.remove(type) }
    fun reset() { pictures.clear(); mapType = 111 }
}
