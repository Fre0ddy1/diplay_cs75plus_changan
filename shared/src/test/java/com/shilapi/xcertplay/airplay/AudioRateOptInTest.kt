package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AudioRateOptInTest {
    private fun config() = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:02", "366.0",
        AirPlayDisplayConfig(1280, 720), microphone = true)
    private fun offer(c: AirPlayConfig, type: Int, audio: String): Map<*, *> =
        (AirPlayInfoPlist.build(c)["audioFormats"] as List<*>).map { it as Map<*, *> }
            .single { it["type"] == type && it["audioType"] == audio }
    @Test fun defaultsRetainOriginalOutputOffers() {
        val c = config()
        assertFalse(c.callAtCallRate); assertFalse(c.speechWithout8And16Khz)
        assertEquals(0xc3fc or 0x70000000, offer(c, 100, "default")["audioOutputFormats"])
        assertEquals(0x4154 or 0x70000000, offer(c, 100, "telephony")["audioOutputFormats"])
    }
    @Test fun callRateRestrictsBothDirectionsWithoutChangingMusicOrSiri() {
        val old = config(); val c = old.copy(callAtCallRate = true)
        assertEquals(0x154, offer(c, 100, "telephony")["audioOutputFormats"])
        assertEquals(0x154, offer(c, 100, "telephony")["audioInputFormats"])
        assertEquals(offer(old, 102, "media"), offer(c, 102, "media"))
        assertEquals(offer(old, 100, "speechRecognition"), offer(c, 100, "speechRecognition"))
        assertFalse(offer(c.copy(microphone = false), 100, "telephony").containsKey("audioInputFormats"))
    }
    @Test fun speechOptionOnlyRemovesLowRatePcmFromPromptOutputs() {
        val old = config(); val c = old.copy(speechWithout8And16Khz = true)
        for ((type, audio) in listOf(100 to "default", 100 to "alert", 101 to "default")) {
            val before = offer(old, type, audio)["audioOutputFormats"] as Int
            assertEquals(before and 0x3c.inv(), offer(c, type, audio)["audioOutputFormats"])
        }
        assertEquals(offer(old, 100, "telephony"), offer(c, 100, "telephony"))
        assertEquals(offer(old, 102, "media"), offer(c, 102, "media"))
    }
}
