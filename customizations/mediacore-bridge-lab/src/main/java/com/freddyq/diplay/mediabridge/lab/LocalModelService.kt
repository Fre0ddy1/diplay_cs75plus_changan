package com.freddyq.diplay.mediabridge.lab

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.freddyq.diplay.mediabridge.contract.BridgeReceipt
import com.freddyq.diplay.mediabridge.receiver.BridgeEndpoint
import com.freddyq.diplay.mediabridge.receiver.BridgeState
import com.freddyq.diplay.mediabridge.receiver.CoverStore
import com.freddyq.diplay.mediabridge.receiver.ProviderSink
import com.freddyq.diplay.mediabridge.receiver.PublishedMedia
import java.io.File

/** Deliberately not called MediaCoreService. It cannot update any OEM process. */
class LocalModelService : Service() {
    private lateinit var endpoint: BridgeEndpoint
    override fun onCreate() {
        super.onCreate()
        val sink = object : ProviderSink {
            override val stage = BridgeReceipt.LOCAL_MODEL
            override fun stage(media: PublishedMedia?) { /* State is held by BridgeState for readback only. */ }
        }
        endpoint = BridgeEndpoint(BridgeState(CoverStore(File(filesDir, "mediacore-bridge-covers"),
            packageName + ".mediacore.bridge.covers"), sink)) { it == Process.myUid() }
    }
    override fun onBind(intent: Intent?): IBinder = endpoint
    override fun onDestroy() { endpoint.close(); super.onDestroy() }
}
