package com.freddyq.diplay.probe

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import com.freddyq.diplay.customization.*
import java.util.UUID

/** Test build only. No ordinary vendor switch writes and no retry after an ambiguous result. */
class LvdsRequestProbe : VehicleCustomization {
    override val id = "lvds-request-probe-66"
    private var owner: Any? = null
    private var currentWindow: Any? = null
    private var attempted = false
    private var ticket = ""

    @Synchronized override fun onWirelessAudioHandoff(context: Context, connection: WirelessAudioConnection) {
        if (owner === connection.owner) return
        owner = connection.owner
        currentWindow = null
        attempted = false
        ticket = UUID.randomUUID().toString()
        report("armed for a new wireless CarPlay connection")
    }
    @Synchronized override fun onWirelessAudioEnded(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        currentWindow = null
        attempted = true
    }
    @Synchronized override fun onSessionEnded() {
        owner = null
        currentWindow = null
        attempted = true
    }
    // Consume all host layout writes even without the temporary agent. No hidden recovery gesture.
    override fun dispatchProjectionLayout(context: Context, layout: ProjectionLayout, service: IBinder) = false

    @Synchronized override fun onProjectionContentReady(context: Context, window: Any, control: ProjectionContentControl) {
        if (owner != null) currentWindow = window
    }
    @Synchronized override fun onProjectionContentLost(window: Any) {
        if (currentWindow === window) currentWindow = null
    }
    @Synchronized override fun onProjectionSourceReady(context: Context, window: Any, service: IBinder) {
        if (owner == null || currentWindow !== window || attempted) return
        attempted = true // Mark before IPC: timeout/exception never authorizes a resend.
        val outcome = LvdsProbeProtocol.request(service, ticket)
        report("one-shot ticket=$ticket cmd=1b/05 len=1 value=3 result=$outcome; physical display unconfirmed")
    }
    private fun report(message: String) = VehicleCustomizations.diagnostic("LvdsProbe66", message)
}

/** Private diagnostic protocol installed in RAM by the separately authorized agent. */
object LvdsProbeProtocol {
    const val TRANSACTION = 0x00660001
    const val MAGIC = 0x4c564453
    const val VERSION = 1
    const val TOKEN = "com.incall.serversdk.interactive.IDouInteractiveManager"
    fun request(service: IBinder, ticket: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TOKEN)
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(0x1b)
            data.writeInt(0x05)
            data.writeInt(1)
            data.writeInt(3)
            data.writeString(ticket)
            if (!service.transact(TRANSACTION, data, reply, 0)) return "agent-unavailable"
            reply.readException()
            if (reply.readInt() != MAGIC || reply.readInt() != VERSION) return "invalid-agent-reply"
            when (reply.readInt()) {
                1 -> "original-handler-returned"
                0 -> "original-handler-not-ready"
                -1 -> "rejected-or-expired"
                -2 -> "already-consumed"
                else -> "unknown-agent-status"
            }
        } catch (_: Exception) { "ipc-failed-no-retry" }
        finally { reply.recycle(); data.recycle() }
    }
}

class LvdsProbeInitializer : ContentProvider() {
    override fun onCreate(): Boolean { VehicleCustomizations.register(LvdsRequestProbe()); return true }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
