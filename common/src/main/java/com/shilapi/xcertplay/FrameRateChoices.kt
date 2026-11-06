package com.shilapi.xcertplay

/** Keep the currently saved intermediate rate visible without changing the available endpoints. */
internal object FrameRateChoices {
    fun rates(current: Int): List<Int> = listOf(30, current, 60).distinct().sorted()
}
