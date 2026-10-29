package com.shilapi.xcertplay

import android.util.Log
import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic dump of the instruction-card stream (internal 112) as a raw Annex B file.
 *
 * Every access unit is written exactly as the decoder receives it, preceded by SPS/PPS from the
 * stream's VideoConfig. The result is playable offline (ffmpeg/VLC) and, unlike a screen grab,
 * contains the encoded frames the iPhone actually sent at the declared size.
 *
 * Only one file per navigation run; 256 MB cap so a forgotten diagnostic cannot fill storage.
 */
internal class CardStreamRecorder(private val directory: File) {
    private var output: OutputStream? = null
    private var file: File? = null
    private var bytesWritten = 0L
    private var frames = 0L
    private var stopped = false

    /** True once an IDR (NAL type 5) was written; a file without one plays black. */
    var sawKeyFrame = false
        private set

    val currentFile: File? get() = file
    val frameCount: Long get() = frames
    val byteCount: Long get() = bytesWritten

    @Synchronized
    fun start(codec: VideoCodec?, config: ByteArray?): File? {
        if (output != null) return file
        directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val extension = if (codec == VideoCodec.H265) "h265" else "h264"
        var candidate = File(directory, "card-112-$stamp.$extension")
        var duplicate = 2
        while (candidate.exists()) {
            candidate = File(directory, "card-112-$stamp-$duplicate.$extension")
            duplicate++
        }
        val stream = BufferedOutputStream(FileOutputStream(candidate), 64 * 1024)
        output = stream
        file = candidate
        bytesWritten = 0
        frames = 0
        stopped = false
        sawKeyFrame = false
        if (config != null && config.isNotEmpty()) writeLocked(config)
        Log.i(TAG, "card H.264 capture started ${candidate.absolutePath} codec=$codec")
        return candidate
    }

    @Synchronized
    fun writeConfig(annexB: ByteArray) {
        if (stopped || annexB.isEmpty()) return
        writeLocked(annexB)
    }

    @Synchronized
    fun writeFrame(annexB: ByteArray) {
        if (stopped || annexB.isEmpty()) return
        writeLocked(annexB)
        frames++
        if (!sawKeyFrame && hasIdr(annexB)) sawKeyFrame = true
        if (bytesWritten >= MAX_BYTES) stopLocked("size cap")
    }

    /** Finds an IDR NAL (type 5) in an Annex B access unit. */
    private fun hasIdr(data: ByteArray): Boolean {
        var i = 0
        while (i + 3 < data.size) {
            val sc3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val sc4 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            if (sc4) {
                if (i + 4 < data.size && (data[i + 4].toInt() and 0x1f) == 5) return true
                i += 4
            } else if (sc3) {
                if (i + 3 < data.size && (data[i + 3].toInt() and 0x1f) == 5) return true
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    @Synchronized
    fun stop(): File? {
        stopLocked("stopped")
        return file
    }

    private fun writeLocked(data: ByteArray) {
        val stream = output ?: return
        if (bytesWritten + data.size > MAX_BYTES) {
            stopLocked("size cap")
            return
        }
        try {
            stream.write(data)
            stream.flush()
            bytesWritten += data.size
        } catch (error: Exception) {
            Log.w(TAG, "card capture write failed", error)
            stopLocked("write failed")
        }
    }

    private fun stopLocked(reason: String) {
        if (stopped) return
        stopped = true
        val stream = output
        output = null
        runCatching { stream?.close() }
        file?.let {
            Log.i(TAG, "card H.264 capture stopped ${it.absolutePath} bytes=$bytesWritten frames=$frames keyframe=$sawKeyFrame reason=$reason")
        }
    }

    private companion object {
        const val TAG = "DiPlay-CardCapture"
        const val MAX_BYTES = 256L * 1024 * 1024
    }
}
