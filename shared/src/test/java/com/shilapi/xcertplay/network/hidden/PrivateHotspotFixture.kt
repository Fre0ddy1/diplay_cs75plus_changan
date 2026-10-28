package com.shilapi.xcertplay.network.hidden

import android.os.ResultReceiver

interface PublicHotspotContract { fun startTethering(type: Int, receiver: ResultReceiver, showUi: Boolean, caller: String) }
object PrivateHotspotFixture {
    fun service(onStart: () -> Unit): Any = PrivateProxy(onStart)
    private class PrivateProxy(private val onStart: () -> Unit) : PublicHotspotContract {
        override fun startTethering(type: Int, receiver: ResultReceiver, showUi: Boolean, caller: String) { onStart() }
    }
}
