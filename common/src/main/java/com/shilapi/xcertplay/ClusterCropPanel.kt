package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/**
 * In-activity overlay to adjust which part of the CarPlay cluster stream fills the
 * instrument panel (dashboard). Width/height below 100 % zoom the picture in, left/top
 * shift the visible window; values are percents of the panel. Saved immediately, applied
 * when the panel closes (the host rebuilds the cluster window).
 */
internal class ClusterCropPanel(
    context: Context,
    private val close: () -> Unit,
    private val onAdjust: () -> Unit = {},
) : LinearLayout(context) {
    private val steps = listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90)
    private val sizeSteps = listOf(10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100)

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Int = 15, color: Int = Color.WHITE) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
    }

    private fun row(title: String, values: List<Int>, current: Int, format: (Int) -> String, save: (Int) -> Unit) {
        val label = text(format(current), 15, Color.rgb(166, 200, 255))
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        head.addView(text(title), LayoutParams(0, -2, 1f))
        head.addView(label)
        addView(head)
        addView(SeekBar(context).apply {
            max = values.lastIndex
            progress = values.indexOf(current).coerceIn(values.indices)
            // Do NOT set minHeight here: the head-unit ROM strips SeekBar#setMinHeight and
            // throws NoSuchMethodError; the row LayoutParams already fixes the height.
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = values[progress.coerceIn(values.indices)]
                    label.text = format(value)
                    if (fromUser) {
                        save(value)
                        onAdjust()
                    }
                }
                override fun onStartTrackingTouch(seek: SeekBar?) = Unit
                override fun onStopTrackingTouch(seek: SeekBar?) = Unit
            })
        }, LayoutParams(-1, dp(48)))
    }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(Color.argb(235, 12, 17, 27))
        isClickable = true
        addView(text(context.getString(R.string.cluster_crop_title), 17, Color.WHITE))
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply { addView(rows) }
        addView(scroll, LayoutParams(-1, 0, 1f))
        rows.addView(text(context.getString(R.string.cluster_crop_hint), 13, Color.rgb(168, 182, 202)))
        row(
            context.getString(R.string.cluster_crop_left), steps,
            AirPlayPersistence.loadClusterCropLeftPercent(context),
            { "${it}%" }, { AirPlayPersistence.saveClusterCrop(context, it, AirPlayPersistence.loadClusterCropTopPercent(context), AirPlayPersistence.loadClusterCropWidthPercent(context), AirPlayPersistence.loadClusterCropHeightPercent(context)) },
        )
        row(
            context.getString(R.string.cluster_crop_top), steps,
            AirPlayPersistence.loadClusterCropTopPercent(context),
            { "${it}%" }, { AirPlayPersistence.saveClusterCrop(context, AirPlayPersistence.loadClusterCropLeftPercent(context), it, AirPlayPersistence.loadClusterCropWidthPercent(context), AirPlayPersistence.loadClusterCropHeightPercent(context)) },
        )
        row(
            context.getString(R.string.cluster_crop_width), sizeSteps,
            AirPlayPersistence.loadClusterCropWidthPercent(context),
            { "${it}%" }, { AirPlayPersistence.saveClusterCrop(context, AirPlayPersistence.loadClusterCropLeftPercent(context), AirPlayPersistence.loadClusterCropTopPercent(context), it, AirPlayPersistence.loadClusterCropHeightPercent(context)) },
        )
        row(
            context.getString(R.string.cluster_crop_height), sizeSteps,
            AirPlayPersistence.loadClusterCropHeightPercent(context),
            { "${it}%" }, { AirPlayPersistence.saveClusterCrop(context, AirPlayPersistence.loadClusterCropLeftPercent(context), AirPlayPersistence.loadClusterCropTopPercent(context), AirPlayPersistence.loadClusterCropWidthPercent(context), it) },
        )
        val actions = LinearLayout(context)
        actions.addView(Button(context).apply {
            text = context.getString(R.string.cluster_crop_reset); isAllCaps = false
            setOnClickListener {
                AirPlayPersistence.saveClusterCrop(context, 0, 0, 100, 100)
                close()
            }
        }, LayoutParams(0, dp(48), 1f))
        actions.addView(Button(context).apply {
            text = context.getString(R.string.cluster_crop_done); isAllCaps = false
            setOnClickListener { close() }
        }, LayoutParams(0, dp(48), 1f))
        addView(actions)
    }
}
