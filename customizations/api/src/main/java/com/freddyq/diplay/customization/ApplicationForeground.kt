package com.freddyq.diplay.customization

/** User-selected transport and the app's observed connection, not vehicle protocol values. */
data class ApplicationForeground(val wireless: Boolean, val vehicleHotspot: Boolean, val connectionActive: Boolean)
fun interface ApplicationForegroundControl { fun current(): ApplicationForeground }
