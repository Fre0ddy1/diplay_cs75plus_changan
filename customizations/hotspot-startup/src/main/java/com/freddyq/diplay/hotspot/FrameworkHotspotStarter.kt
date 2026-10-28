package com.freddyq.diplay.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import com.freddyq.diplay.customization.PlatformHotspotStarter

/** S311's framework wrapper/callback are allowlisted; its low-level Binder start method is not. */
internal class FrameworkHotspotStarter(
    private val app: Context,
    private val log: (String) -> Unit,
    private val wrapper: Class<*> = ConnectivityManager::class.java,
    private val managerProvider: () -> Any? = { app.getSystemService(ConnectivityManager::class.java) },
    private val restored: () -> Unit = {},
) : PlatformHotspotStarter {
    override fun onReady() = restored()
    override fun start(receiver: ResultReceiver) {
        log("car hotspot framework stage=resolve-wrapper")
        val callbackType = Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
        val method = wrapper.getMethod(
            "startTethering", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            callbackType, Handler::class.java,
        )
        log("car hotspot framework stage=create-callback")
        val callback = Class.forName("com.freddyq.diplay.hotspot.Android9HotspotCallback")
            .getConstructor(ResultReceiver::class.java).newInstance(receiver)
        val manager = managerProvider()
            ?: throw NoSuchMethodException("ConnectivityManager unavailable")
        log("car hotspot framework stage=startTethering")
        method.invoke(manager, 0, false, callback, Handler(Looper.getMainLooper()))
    }
}
