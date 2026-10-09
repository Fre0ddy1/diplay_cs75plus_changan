package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Dashboard card layout preview, used in two places:
 * - settings page (static schematic, touchEnabled=false);
 * - in-activity "card position controls" panel over the live CarPlay surface
 *   (touchEnabled=true): the user drags a card block to move it. Dragging only
 *   updates the preview state (dx/dy), it does NOT write the persisted settings —
 *   the panel's Save button persists and applies, Cancel discards.
 *
 * The preview renders the cluster-stream region (1920x720, centred on the UNI-V
 * 1920x1440 panel) and the five nav cards at their default anchors plus dx/dy.
 */
class CardPositionPreviewView(context: Context) : View(context) {

    data class CardState(val x: Int, val y: Int)

    private val states = mutableMapOf(
        "lane" to CardState(43, 43),
        "turn" to CardState(0, 0),
        "eta" to CardState(43, 500),
        "road" to CardState(560, 500),
        "exit" to CardState(1700, 43),
    )

    /** When true the user can drag the card blocks (in-activity panel); settings page keeps it false. */
    var touchEnabled: Boolean = false

    /** Invoked while the user drags a card: (cardId, x, y). Preview only, nothing persisted. */
    var onPreviewChanged: ((String, Int, Int) -> Unit)? = null

    private var turnX = 400
    private var turnY = 40

    /** Block rects from the last draw, used for drag hit-testing. */
    private val lastRects = mutableMapOf<String, RectF>()
    private var draggingCard: String? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 30, 32, 40)
        style = Paint.Style.FILL
    }
    private val streamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 64, 72, 92)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val rectF = RectF()

    private data class Anchor(
        val mode: Int, // 0=top-left, 1=centre, 2=bottom-left, 3=bottom-centre, 4=top-right
        val xPct: Float,
        val yPct: Float,
        val wPct: Float,
        val hPct: Float,
        val color: Int,
        val label: String,
    )

    private val anchors = listOf(
        Anchor(0, 4.5f, 4.5f, 30f, 18f, Color.rgb(90, 160, 255), "Lane"),
        Anchor(1, 50f, 12f, 28f, 20f, Color.rgb(120, 220, 130), "Turn"),
        Anchor(2, 3f, 97f, 24f, 16f, Color.rgb(255, 190, 90), "Arrival"),
        Anchor(3, 50f, 97f, 40f, 14f, Color.rgb(180, 140, 255), "Road"),
        Anchor(4, 97f, 3f, 14f, 12f, Color.rgb(255, 130, 130), "Exit"),
    )

    fun setOffset(card: String, x: Int, y: Int) {
        states[card] = CardState(x.coerceIn(0, 1920), y.coerceIn(0, 720))
        invalidate()
    }

    fun currentOffset(card: String): CardState = states[card] ?: CardState(0, 0)

    fun resetAll() {
        states.keys.forEach { states[it] = CardState(0, 0) }
        invalidate()
    }

    fun setTurnOffset(xPx: Int, yPx: Int) {
        turnX = xPx.coerceIn(0, 1920)
        turnY = yPx.coerceIn(0, 720)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        setMeasuredDimension(w, (w * 0.32f).toInt().coerceAtLeast(dp(120f).toInt()))
    }

    private fun streamHeight(): Float = height.toFloat() * 0.5f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val panelW = width.toFloat()
        val panelH = height.toFloat()
        rectF.set(0f, 0f, panelW, panelH)
        canvas.drawRoundRect(rectF, dp(8f), dp(8f), panelPaint)
        val streamH = streamHeight()
        val streamTop = (panelH - streamH) / 2f
        rectF.set(0f, streamTop, panelW, streamTop + streamH)
        canvas.drawRoundRect(rectF, dp(6f), dp(6f), streamPaint)
        canvas.drawRoundRect(rectF, dp(6f), dp(6f), strokePaint)

        lastRects.clear()
        anchors.forEachIndexed { index, anchor ->
            val card = anchorsKeys[index]
            val state = states[card] ?: CardState(0, 0)
            val w = anchor.wPct / 100f * panelW
            val h = anchor.hPct / 100f * streamH
            var left: Float
            var top: Float
            if (card == "turn") {
                left = turnX / 1920f * panelW
                top = streamTop + turnY / 720f * streamH
            } else {
                // Absolute position: card top-left = (state.x, state.y) in the 1920x720
                // cluster-stream region, origin at its top-left corner.
                left = state.x / 1920f * panelW
                top = streamTop + state.y / 720f * streamH
            }
            left = left.coerceIn(0f, (panelW - w).coerceAtLeast(0f))
            top = top.coerceIn(streamTop, (streamTop + streamH - h).coerceAtLeast(streamTop))
            rectF.set(left, top, left + w, top + h)
            canvas.drawRoundRect(rectF, dp(4f), dp(4f), Paint(anchor.color).apply { alpha = if (draggingCard == card) 255 else 200 })
            canvas.drawText(anchor.label, left + w / 2f, top + h / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
            lastRects[card] = RectF(left, top, left + w, top + h)
        }
    }

    /** Anchor keys in the same order as [anchors]. */
    private val anchorsKeys = listOf("lane", "turn", "eta", "road", "exit")

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchEnabled) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingCard = lastRects.entries.firstOrNull { (_, r) -> r.contains(event.x, event.y) }?.key
                if (draggingCard != null) {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val card = draggingCard ?: return false
                val streamH = streamHeight()
                if (streamH <= 0f) return false
                val state = states[card] ?: return false
                if (card == "turn") {
                    // The turn card is positioned in absolute px (0..1920 / 0..720).
                    val scaleX = width / 1920f
                    val scaleY = streamH / 720f
                    val nx = (turnX + (event.x - lastTouchX) / scaleX).toInt().coerceIn(0, 1920)
                    val ny = (turnY + (event.y - lastTouchY) / scaleY).toInt().coerceIn(0, 720)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    turnX = nx
                    turnY = ny
                    onPreviewChanged?.invoke(card, nx, ny)
                    invalidate()
                    return true
                }
                val scaleX = width / 1920f
                val scaleY = streamH / 720f
                val nx = (state.x + (event.x - lastTouchX) / scaleX).toInt().coerceIn(0, 1920)
                val ny = (state.y + (event.y - lastTouchY) / scaleY).toInt().coerceIn(0, 720)
                lastTouchX = event.x
                lastTouchY = event.y
                states[card] = CardState(nx, ny)
                onPreviewChanged?.invoke(card, nx, ny)
                invalidate()
                return true
            }
            else -> {
                if (draggingCard != null) {
                    draggingCard = null
                    invalidate()
                    return true
                }
                return false
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
