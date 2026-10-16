package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(val nalus: ByteArray, val receivedNs: Long = System.nanoTime()) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
    /**
     * How long a decoded-picture job is held before it becomes available, in milliseconds.
     *
     * This is the video half of A/V sync. Music is buffered on purpose (see [MediaAudioBuffer]:
     * 300/1000/1500 ms to survive Wi-Fi radio gaps) while video is rendered the moment it is
     * decoded, so sound sits behind the picture by that much and lyrics show up early. Holding
     * each frame for the same amount of time shifts the whole picture later and lines the two
     * back up, without weakening the buffer that keeps music from stuttering.
     *
     * Control jobs ([VideoJob.Config], [VideoJob.SurfaceChanged], [VideoJob.Resync]) are never
     * held - they carry no picture, and delaying a resync would only stall recovery. 0 keeps the
     * old behaviour exactly.
     */
    private val holdMillis: Long = 0,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()
    // The decoder thread is the only poller, so a single held slot is enough.
    private var held: VideoJob.Frame? = null

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            val heldFrame = held
            val frames = jobs.filterIsInstance<VideoJob.Frame>() +
                if (heldFrame != null) listOf(heldFrame) else emptyList()
            if (frames.size >= maxFrames || frames.sumOf { it.nalus.size.toLong() } + job.nalus.size > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) return
        }
        jobs.offer(job)
    }

    @Synchronized fun discardFrames() {
        jobs.removeIf { it is VideoJob.Frame || it is VideoJob.Resync }
        // A frame held back would otherwise be rendered after the resync: a stale picture on top
        // of a fresh reference chain.
        held = null
    }

    fun poll(timeoutMillis: Long): VideoJob? {
        if (holdMillis <= 0) return jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0) return null
            val job = held ?: jobs.poll(remainingNs, TimeUnit.NANOSECONDS) ?: return null
            if (job !is VideoJob.Frame) return job
            val ageMs = (System.nanoTime() - job.receivedNs) / 1_000_000L
            if (ageMs >= holdMillis) {
                held = null
                return job
            }
            // Too young to show: keep it back and wait for it to age.
            held = job
        }
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
