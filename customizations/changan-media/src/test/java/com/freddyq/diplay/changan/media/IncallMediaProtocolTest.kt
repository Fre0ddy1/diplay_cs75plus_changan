package com.freddyq.diplay.changan.media

import android.os.Binder
import android.os.Parcel
import com.freddyq.diplay.customization.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class IncallMediaProtocolTest {
    private fun snapshot(playing: Boolean = true, position: Long? = 1000, total: Long? = 60000) =
        MediaSnapshot(MediaItem("中文\"歌名\n", "歌手\\A", "专辑", total, 8), playing, position, 5000, false)

    @Test fun realPausedStateAndJsonEscapingPreserveThePhoneMetadata() {
        val json = JSONObject(IncallMediaProtocol.metadataJson(100901, snapshot(false)))
        assertEquals(1, json.getInt("playStatus")); assertEquals(25, json.getInt("sourceType"))
        assertEquals(100901, json.getInt("sourceID"))
        assertEquals("中文\"歌名\n", json.getString("songName"))
        assertEquals("歌手\\A", json.getString("singerName"))
        assertEquals(2, JSONObject(IncallMediaProtocol.metadataJson(100901, snapshot())).getInt("playStatus"))
    }
    @Test fun allThreeTransactionsMatchTheVerifiedNativeWireOrder() {
        val received = mutableListOf<Int>()
        val file = File("/storage/emulated/0/Android/data/test/files/cp-test.jpg")
        val endpoint = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(IncallMediaProtocol.TOKEN); assertEquals(0, flags); received += code
                when (code) {
                    37 -> assertEquals(100901, JSONObject(data.readString()!!).getInt("sourceID"))
                    38 -> { assertEquals(100901, data.readInt()); assertEquals(3000, data.readInt()); assertEquals(60000, data.readInt()) }
                    39 -> { assertEquals(100901, data.readInt()); assertEquals(file.name, data.readString()); assertEquals(file.absolutePath, data.readString()) }
                    else -> fail("unexpected control transaction $code")
                }
                assertEquals(0, data.dataAvail()); reply!!.writeNoException(); return true
            }
        }
        IncallMediaProtocol(endpoint).apply { metadata(100901, snapshot()); progress(100901, snapshot(), 7000); cover(100901, file) }
        assertEquals(listOf(37, 38, 39), received)
    }
    @Test fun pausedProgressIsSuppressedAndUnknownDurationIsNotInvented() {
        var calls = 0
        val endpoint = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls++; data.enforceInterface(IncallMediaProtocol.TOKEN)
                assertEquals(38, code); data.readInt(); assertEquals(1000, data.readInt()); assertEquals(60000, data.readInt())
                reply!!.writeNoException(); return true
            }
        }
        val protocol = IncallMediaProtocol(endpoint)
        protocol.progress(1, snapshot(false), 900000)
        protocol.progress(1, snapshot(total = null), 900000)
        protocol.progress(1, snapshot(position = null), 900000)
        protocol.progress(1, snapshot(total = Long.MAX_VALUE), 900000)
        assertEquals(0, calls)
        assertEquals(1000L, snapshot(false).positionAt(900000))
    }
    @Test fun positionIsMonotonicClampedAndOverflowSafe() {
        val value = snapshot()
        assertEquals(1000L, value.positionAt(3000))
        assertEquals(60000L, value.positionAt(Long.MAX_VALUE))
        val unbounded = snapshot(total = null).copy(positionMillis = Long.MAX_VALUE - 2)
        assertEquals(Long.MAX_VALUE, unbounded.positionAt(8000))
    }
}
