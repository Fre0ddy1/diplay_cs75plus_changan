package com.freddyq.diplay.mediabridge.receiver

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File

/** Subclass only in the real OEM MediaCore host. No fallback to a local-model success. */
abstract class OemBridgeService : Service() {
    protected abstract val publisherPackage: String
    protected abstract val publisherCertificateSha256: String
    protected abstract val providerSlot: NativeProviderSlot
    private var endpoint: BridgeEndpoint? = null

    final override fun onCreate() {
        super.onCreate()
        endpoint = runCatching {
            val identity = PublisherIdentity(packageManager, publisherPackage, publisherCertificateSha256)
            val sink = OemProviderAdapter.inOemProcess(classLoader, providerSlot)
            BridgeEndpoint(BridgeState(CoverStore(File(filesDir, "mediacore-bridge-covers"),
                packageName + ".mediacore.bridge.covers"), sink), identity::allows)
        }.onFailure { Log.e("MediaPublishBridge", "OEM integration unavailable; refusing binding", it) }.getOrNull()
    }
    final override fun onBind(intent: Intent?): IBinder? = endpoint
    final override fun onDestroy() { endpoint?.close(); super.onDestroy() }
}
