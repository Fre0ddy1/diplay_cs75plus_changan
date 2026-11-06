package com.shilapi.xcertplay
import org.junit.Assert.*
import org.junit.Test
class FrameRateChoicesTest {
    @Test fun everyExistingRateIsDisplayedAndTheEndpointsStaySelectable() {
        for (fps in 30..60 step 5) {
            val rates = FrameRateChoices.rates(fps)
            assertEquals(fps, rates[rates.indexOf(fps)])
            assertEquals(30, rates.first()); assertEquals(60, rates.last())
            assertEquals(rates.distinct(), rates)
        }
    }
}
