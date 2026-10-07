package com.shilapi.xcertplay

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * InCallClusterSdk
 *
 * Port of the FengQing (风清) UNI-V box's `android.car.ClusterInstrumentSdk`
 * (TAG = "CarplayClusterSdk") — the Chang'an vehicle head-unit (`com.incall`
 * system) dashboard SDK. It talks to the car's instrument-cluster system
 * directly via reflection + Binder transactions, with NO system signature.
 *
 * Key idea: calling [sendNavigateStatus] with 1 ("navigation running") makes
 * the head-unit switch the instrument cluster into navigation-projection
 * mode, i.e. it registers the dashboard display into DisplayManager. That is
 * exactly the cold-start activation DiPlay needs so three-finger swipes can
 * project before the stock Amap has ever been launched.
 *
 * Service graph (from the decompiled SDK):
 *   ServiceManager.getService("com.incall.SVR_MNG_SERVICE")
 *     -> transact(6) -> client binder for a named service
 *   "com.incall.double.INTERACTIVE_SERVICE"  (IDouInteractiveManager)
 *     -> tx 22 = sendNavigateStatus(0|1)
 *
 * All calls are best-effort: failures are logged and return false, never
 * thrown.
 */
object InCallClusterSdk {
    private const val TAG = "InCallClusterSdk"

    private const val SVR_MNG_SERVICE = "com.incall.SVR_MNG_SERVICE"
    private const val SVR_MANAGER_TOKEN = "com.incall.serversdk.server.ISvrManager"
    private const val INTERACTIVE_SERVICE = "com.incall.double.INTERACTIVE_SERVICE"
    private const val INTERACTIVE_TOKEN = "com.incall.serversdk.interactive.IDouInteractiveManager"

    private const val TX_GET_CLIENT_BINDER = 6
    private const val TX_NAV_STATUS = 22

    /** Whether the Chang'an instrument-cluster services exist on this head-unit. */
    fun isAvailable(): Boolean = getSystemServiceBinder(SVR_MNG_SERVICE) != null

    /**
     * Tell the head-unit the navigation status.
     * 1 = running (activates the cluster navigation projection mode),
     * 0 = none. Returns true when the Binder transaction succeeded.
     */
    fun sendNavigateStatus(status: Int): Boolean = sendInteractiveInt(TX_NAV_STATUS, status)

    private fun sendInteractiveInt(code: Int, value: Int): Boolean {
        val clientBinder = getClientBinder(INTERACTIVE_SERVICE) ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERACTIVE_TOKEN)
            data.writeInt(value)
            val ok = clientBinder.transact(code, data, reply, 0)
            reply.readException()
            return ok
        } catch (e: Exception) {
            Log.w(TAG, "sendInteractiveInt($code,$value) failed", e)
            return false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun getClientBinder(serviceName: String): IBinder? {
        val systemService = getSystemServiceBinder(SVR_MNG_SERVICE) ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SVR_MANAGER_TOKEN)
            data.writeString(serviceName)
            val ok = systemService.transact(TX_GET_CLIENT_BINDER, data, reply, 0)
            reply.readException()
            return if (ok) reply.readStrongBinder() else null
        } catch (e: Exception) {
            Log.w(TAG, "getClientBinder($serviceName) failed", e)
            return null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun getSystemServiceBinder(serviceName: String): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            getService.invoke(null, serviceName) as? IBinder
        } catch (e: Exception) {
            Log.w(TAG, "ServiceManager.getService($serviceName) failed", e)
            null
        }
    }
}
