package com.freddyq.diplay.mediabridge.lab

import android.app.Activity
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.freddyq.diplay.customization.MediaItem
import com.freddyq.diplay.customization.MediaSnapshot
import com.freddyq.diplay.mediabridge.publisher.MediaCorePublisher

class BridgeLabActivity : Activity() {
    private val owner = Any()
    private lateinit var publisher: MediaCorePublisher
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var snapshot: MediaSnapshot? = null
    private var track = 0
    private val poll = object : Runnable {
        override fun run() {
            publisher.readback { data -> runOnUiThread {
                status.text = getString(R.string.bridge_status, data.getString("code"), data.getString("stage"),
                    data.getBoolean("connected").toString(), data.getInt("provider"),
                    data.getBundle("media").toString(), data.getString("coverUri") ?: "无")
            } }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        publisher = MediaCorePublisher(ComponentName(this, LocalModelService::class.java))
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        fun button(text: String, clicked: () -> Unit) { layout.addView(Button(this).apply { this.text = text; setOnClickListener { clicked() } }) }
        layout.addView(TextView(this).apply { text = "离线桥接验证：不播放声音、不连接原车服务、不修改灯光。" })
        button("连接并发布下一首测试曲") {
            publisher.onMediaSessionStarted(this, owner)
            track++
            val item = MediaItem("测试曲$track", "本地验证", "合成数据", 180000, track)
            snapshot = MediaSnapshot(item, true, 0, SystemClock.elapsedRealtime(), true)
            publisher.onMediaSnapshot(this, owner, snapshot!!)
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(if (track % 2 == 0) Color.BLUE else Color.RED) }
            publisher.onMediaArtwork(this, owner, item, bitmap); bitmap.recycle()
        }
        button("暂停（保留曲目与封面）") { changePlaying(false) }
        button("继续（复用同一封面）") { changePlaying(true) }
        button("断开（清除本会话）") { publisher.onMediaSessionEnded(owner); snapshot = null }
        status = TextView(this); layout.addView(status)
        setContentView(layout); ui.post(poll)
    }

    private fun changePlaying(playing: Boolean) {
        val previous = snapshot ?: return
        val now = SystemClock.elapsedRealtime()
        snapshot = previous.copy(playing = playing, positionMillis = previous.positionAt(now), positionUpdatedAtMillis = now)
        publisher.onMediaSnapshot(this, owner, snapshot!!)
    }
    override fun onDestroy() { ui.removeCallbacks(poll); publisher.close(); super.onDestroy() }
}
