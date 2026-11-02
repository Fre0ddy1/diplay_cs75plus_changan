package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MediaPcmGainTest {
    private fun pcm(vararg samples: Int) = samples.flatMap { listOf(it.toByte(), (it shr 8).toByte()) }.toByteArray()
    private fun sample(bytes: ByteArray, index: Int) = (bytes[index * 2 + 1].toInt() shl 8) or (bytes[index * 2].toInt() and 255)
    @Test fun steadySeventyFivePercentIsNotSquared() {
        val gain = MediaPcmGain(2)
        gain.apply(pcm(10000,10000),0,4,0.75f)
        val next = pcm(10000,-10000)
        gain.apply(next,0,next.size,0.75f)
        assertEquals(7500,sample(next,0)); assertEquals(-7500,sample(next,1))
    }
    @Test fun gainTransitionIsSmoothAndIdenticalInBothChannels() {
        val next = pcm(10000,10000,10000,10000,10000,10000,10000,10000)
        MediaPcmGain(2).apply(next,0,next.size,0.6f)
        for (frame in 0..3) assertEquals(sample(next,frame*2),sample(next,frame*2+1))
        assertEquals(9000,sample(next,0)); assertEquals(6000,sample(next,6))
        assertTrue(sample(next,0)>sample(next,2)); assertTrue(sample(next,2)>sample(next,4))
    }
    @Test fun resourceDenialMutesTheWholeFirstBufferImmediately() {
        val next=pcm(32000,-32000,10000,-10000)
        MediaPcmGain(2).apply(next,0,next.size,0f)
        assertArrayEquals(ByteArray(next.size),next)
    }
    @Test fun restorationRampsBackToUnityInsteadOfKeepingTheOldDuck() {
        val gain=MediaPcmGain(1)
        gain.apply(pcm(10000,10000),0,4,0f)
        val next=pcm(10000,10000)
        gain.apply(next,0,next.size,1f)
        assertEquals(5000,sample(next,0)); assertEquals(10000,sample(next,1))
    }
    @Test fun onlyTheRequestedCompleteFramesAreModified() {
        val next=pcm(1111,10000,10000,2222)
        val gain=MediaPcmGain(2)
        gain.apply(next,2,4,0.5f)
        assertEquals(1111,sample(next,0)); assertEquals(2222,sample(next,3))
        assertEquals(5000,sample(next,1)); assertEquals(5000,sample(next,2))
    }
}
