package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
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
internal class SessionLogFile(val file: File) : Closeable {
    private val queue = LinkedBlockingQueue<String>(MAX_QUEUED_LINES)
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
        rotate()
        file.writeText("")
        enqueue(header)
    }

    fun append(line: String) = enqueue(line)

    private fun enqueue(line: String) {
        if (closed) return
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

    private fun writeBatch(batch: List<String>) {
        runCatching {
            if (file.length() > MAX_BYTES) {
                rotate()
                file.writeText("")
            }
            // One append call per batch: fewer opens, fewer fsyncs, less time holding the file.
            file.appendText(buildString {
                batch.forEach { append(it).append('\n') }
            })
            written.addAndGet(batch.size.toLong())
            val lost = dropped.getAndSet(0L)
            if (lost > 0) {
                file.appendText("(session log dropped $lost lines to stay responsive)\n")
            }
        }
    }

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }

    override fun close() = synchronized(this) {
        closed = true
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
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
