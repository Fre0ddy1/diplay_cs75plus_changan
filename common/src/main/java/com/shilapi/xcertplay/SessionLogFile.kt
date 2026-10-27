package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import com.freddyq.diplay.customization.DiagnosticPolicy
import com.freddyq.diplay.customization.VehicleCustomizations
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, private diagnostics. Each write is redacted before touching storage.
 *
 * Writes are asynchronous. The caller only redacts and enqueues; a single daemon thread does the
 * file I/O, including the archive rotation. This matters because every log call used to append
 * synchronously on whatever thread made it - and some of those callers are the UI thread and the
 * video path. A rotation copies seven files, so one unlucky burst of navigation logs could stall
 * the caller for far longer than a frame. Dropping log lines under pressure is the intended
 * trade-off: the first rule here is that the picture stays smooth.
 */
internal class SessionLogFile(
    val file: File,
    private val policy: DiagnosticPolicy? = VehicleCustomizations.diagnosticPolicy(),
) : Closeable {
    private val fileLimit = policy?.maxFileBytes?.coerceIn(4096L, MAX_BYTES) ?: MAX_BYTES
    private val archiveNames = ARCHIVE_NAMES.take(policy?.archiveCount?.coerceIn(1, ARCHIVE_NAMES.size) ?: ARCHIVE_NAMES.size)
    private val queue = LinkedBlockingQueue<String>(policy?.maxQueuedLines?.coerceIn(16, MAX_QUEUED_LINES) ?: MAX_QUEUED_LINES)
    private val dropped = AtomicLong()
    private val written = AtomicLong()

    @Volatile
    private var closed = false

    private val writer = Thread(::drainLoop, "session-log-writer").apply {
        isDaemon = true
        start()
    }

    fun reset(header: String) = synchronized(this) {
        if (closed) return@synchronized
        queue.clear()
        file.parentFile?.mkdirs()
        // Retire only our own old rotation slots when upgrading to a smaller distribution budget.
        if (policy != null && file.name == "diplay.log") {
            ARCHIVE_NAMES.drop(archiveNames.size).forEach { File(file.parentFile, it).delete() }
            archiveNames.forEach { trimTail(File(file.parentFile, it)) }
        }
        rotate()
        file.writeText("")
        enqueue(header)
    }

    fun append(line: String) = enqueue(line)

    private fun enqueue(line: String) {
        if (closed) return
        if (policy != null && !policy.keep(line)) return
        val safe = DiagnosticRedactor.redact(line) ?: return
        // offer() never blocks: if the writer cannot keep up, diagnostics get dropped rather than
        // slowing the thread that is trying to render CarPlay.
        if (!queue.offer(safe)) dropped.incrementAndGet()
    }

    private fun drainLoop() {
        while (!closed || queue.isNotEmpty()) {
            val first = try {
                queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } ?: continue
            val batch = ArrayList<String>(MAX_BATCH)
            batch.add(first)
            queue.drainTo(batch, MAX_BATCH - 1)
            writeBatch(batch)
        }
    }

    @Synchronized private fun writeBatch(batch: List<String>) {
        runCatching {
            val lost = dropped.getAndSet(0L)
            val lines = if (lost > 0) batch + "(session log dropped $lost lines to stay responsive)" else batch
            var currentBytes = file.length()
            var bufferedBytes = 0L
            val buffer = StringBuilder()
            fun flush() {
                if (buffer.isEmpty()) return
                file.appendText(buffer.toString())
                currentBytes += bufferedBytes
                bufferedBytes = 0
                buffer.setLength(0)
            }
            for (line in lines) {
                val entry = line + '\n'
                val bytes = entry.toByteArray(Charsets.UTF_8).size
                if (currentBytes + bufferedBytes + bytes > fileLimit) {
                    flush()
                    rotate()
                    file.writeText("")
                    currentBytes = 0
                }
                buffer.append(entry)
                bufferedBytes += bytes
            }
            flush()
            written.addAndGet(batch.size.toLong())
        }
    }

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        trimTail(file)
        for (index in archiveNames.lastIndex downTo 1) {
            val source = File(file.parentFile, archiveNames[index - 1])
            val destination = File(file.parentFile, archiveNames[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, archiveNames.first()), overwrite = true)
    }

    private fun trimTail(target: File) {
        if (!target.isFile || target.length() <= fileLimit) return
        val tail = ByteArray(fileLimit.toInt())
        RandomAccessFile(target, "r").use {
            it.seek(it.length() - tail.size)
            it.readFully(tail)
        }
        // Start at a complete line, preserving UTF-8 characters at the trimming boundary.
        val start = tail.indexOf('\n'.code.toByte()) + 1
        target.writeBytes(if (start > 0) tail.copyOfRange(start, tail.size) else byteArrayOf())
    }

    override fun close() {
        synchronized(this) { closed = true }
        // Give the writer a moment to flush what is already queued; never block shutdown on it.
        runCatching { writer.join(CLOSE_FLUSH_MS) }
        Unit
    }

    companion object {
        const val MAX_BYTES = 512 * 1024L
        private const val MAX_QUEUED_LINES = 2_048
        private const val MAX_BATCH = 128
        private const val POLL_MS = 200L
        private const val CLOSE_FLUSH_MS = 300L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES: List<String> get() = ARCHIVE_NAMES.take(
            VehicleCustomizations.diagnosticPolicy()?.archiveCount?.coerceIn(1, ARCHIVE_NAMES.size) ?: ARCHIVE_NAMES.size,
        ).reversed() + "diplay.log"
    }
}
