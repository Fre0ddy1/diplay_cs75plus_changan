package com.shilapi.xcertplay.media

/** Full decoded buffer and its inclusive-format crop, in pixels. */
data class ScreenStreamGeometry(
    val streamWidth: Int,
    val streamHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
) {
    val valid: Boolean get() = streamWidth > 0 && streamHeight > 0 &&
        cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0 &&
        cropLeft.toLong() + cropWidth <= streamWidth && cropTop.toLong() + cropHeight <= streamHeight
}
