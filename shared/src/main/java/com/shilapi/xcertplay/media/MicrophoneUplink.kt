package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val stats = MicrophoneCaptureStats(config, report = { message ->
        Log.i(TAG, message)
        onDiagnostic(message)
    })
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    @Volatile private var effects: List<AudioEffect> = emptyList()
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            stats.failure(MicrophoneFailureStage.MIN_BUFFER, code = minBuffer)
            running.set(false)
            return false
        }

        val source = when (config.audioType) {
            "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            // Voice-recognition uplinks must NOT use VOICE_RECOGNITION on this head unit.
            //
            // Measured 2026-10-21 18:55 on the car (release build): VOICE_RECOGNITION delivered
            // 367 KB/s against a 96 KB/s baseline, i.e. ~3.8x the negotiated rate. The wall-clock
            // pacing below then decimates 3.8:1 with no low-pass filter, which does not produce
            // "a few dropped frames" but a pitch-shifted, aliased clip - the "WeChat voice is all
            // noise" report. Pacing fixes the packet rate, not the waveform.
            //
            // VOICE_COMMUNICATION delivered the correct 95 KB/s in the 2026-10-20 21:56 log, so
            // pacing becomes a safety net instead of the primary mechanism. The known drawback is
            // that it moves the head unit onto its call audio path; the volume guard in
            // AndroidMediaSink (music-level snapshot and restore around a call) covers that, and
            // the guidance-volume key routing is decided by navigationAudioActive(), which this
            // source choice does not touch.
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else -> MediaRecorder.AudioSource.MIC
        }
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            OpusEncoder(config.bitrate ?: 48_000, config.sampleRate).takeIf { it.available }
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            stats.failure(MicrophoneFailureStage.ENCODER)
            running.set(false)
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        val nextRecorder = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (error: Exception) {
            Log.e(TAG, "microphone recorder creation failed", error)
            stats.failure(MicrophoneFailureStage.RECORDER_CREATION, error)
            nextEncoder?.close()
            running.set(false)
            return false
        }
        if (nextRecorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone recorder failed to initialize")
            stats.failure(MicrophoneFailureStage.RECORDER_INITIALIZATION, code = nextRecorder.state)
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            stats.failure(MicrophoneFailureStage.SOCKET_CREATION, error)
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            if (config.audioType == "telephony") effects = voiceEffects(nextRecorder.audioSessionId)
            nextRecorder.startRecording()
            stats.started(routeType(nextRecorder))
            // The rate the device actually runs at, next to the one we negotiated. When these
            // differ the uplink is unusable no matter how well the pacing hides it, and this line
            // is the quickest way to see that from a field report.
            Log.i(
                TAG,
                "Microphone: source=$source requestedRate=${config.sampleRate} " +
                    "actualRate=${nextRecorder.sampleRate} channels=${config.channels}",
            )
            thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            release()
            false
        }
    }

    private fun voiceEffects(sessionId: Int): List<AudioEffect> = listOfNotNull(
        enabledEffect("AEC") {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null
        },
        enabledEffect("NS") {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
        },
    )

    // Advertised effects may still fail to initialize on a vendor ROM. Keep recording without them.
    private fun enabledEffect(name: String, create: () -> AudioEffect?): AudioEffect? {
        var effect: AudioEffect? = null
        try {
            effect = create()
            if (effect != null) {
                val status = effect.setEnabled(true)
                if (status == AudioEffect.SUCCESS && effect.enabled) {
                    Log.i(TAG, "microphone effect=$name enabled=true")
                    return effect
                }
                Log.w(TAG, "microphone effect=$name could not be enabled status=$status")
            } else {
                Log.i(TAG, "microphone effect=$name unavailable")
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect=$name unavailable; continuing without it", error)
        }
        effect?.let(::releaseEffect)
        return null
    }

    private fun releaseEffect(effect: AudioEffect) {
        try {
            effect.release()
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect release failed", error)
        }
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        val routeInfo = { routeType(recorder) }
        // Wall-clock pacing. A capture device that returns faster than the negotiated rate would
        // otherwise make the phone play the microphone at that speed - the "it is all noise"
        // voice clip. Frames that arrive ahead of their slot are dropped instead of sent, so the
        // RTP timeline always matches real time whatever the device does.
        val frameNanos = config.samplesPerPacket.toLong() * 1_000_000_000L / config.sampleRate
        val clockBase = System.nanoTime()
        var framesPaced = 0L
        var filled = 0
        try {
            while (running.get()) {
                stats.reading()
                val count = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                stats.read(count)
                if (count < 0) {
                    if (running.get()) {
                        Log.e(TAG, "microphone read failed code=$count")
                        stats.failure(MicrophoneFailureStage.READ, code = count)
                    }
                    return
                }
                if (count == 0) {
                    stats.flush(routeType = routeInfo)
                    continue
                }
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        val due = clockBase + (framesPaced + 1) * frameNanos
                        if (System.nanoTime() + frameNanos / 2 >= due) {
                            sendFrame(socket, counters, frame)
                            framesPaced++
                        } else {
                            stats.pacedDrop()
                        }
                        filled = 0
                    }
                }
                stats.flush(routeType = routeInfo)
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                stats.failure(MicrophoneFailureStage.CAPTURE, error)
            }
        } finally {
            stats.flush(ended = true, routeType = routeInfo)
            running.set(false)
            release()
        }
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        stats.encoded(bodies.size, if (bodies.isEmpty()) 1 else bodies.count { it.isEmpty() })
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.samplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            stats.sent()
        } catch (error: Exception) {
            stats.sendFailed()
            if (running.get()) throw error
        }
    }

    private fun routeType(recorder: AudioRecord): Int? = runCatching { recorder.routedDevice?.type }.getOrNull()

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentEffects = effects
        effects = emptyList()
        currentEffects.forEach(::releaseEffect)
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
    }
}
