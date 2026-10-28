package com.shilapi.xcertplay.network

import android.os.ResultReceiver

/** The platform Binder proxy is not public; invoke the public interface method instead. */
internal object AndroidHotspotStarter {
    fun start(service: Any, receiver: ResultReceiver, callerPackage: String) =
        invokeStart(service, Class.forName("android.net.IConnectivityManager"), receiver, callerPackage)

    internal fun invokeStart(service: Any, contract: Class<*>, receiver: ResultReceiver, callerPackage: String) {
        val method = contract.getMethod(
            "startTethering", Int::class.javaPrimitiveType, ResultReceiver::class.java,
            Boolean::class.javaPrimitiveType, String::class.java,
        )
        // Resolving on service.javaClass gives a Method declared by the non-public Stub.Proxy.
        // Public-interface dispatch keeps Java access checks and Android permission checks intact.
        method.invoke(service, 0, receiver, false, callerPackage)
    }
}
