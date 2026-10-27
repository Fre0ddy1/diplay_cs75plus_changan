package com.freddyq.diplay.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.freddyq.diplay.customization.DiagnosticPolicy
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations

/** Stateless filtering consumes no growing map of roads, tracks or protocol messages. */
class CompactDiagnosticPolicy : DiagnosticPolicy {
    override val maxFileBytes = 256 * 1024L
    override val archiveCount = 2
    override val maxQueuedLines = 128
    private val periodic = Regex("(?i)(\\baudio stats\\b|\\bvideo stats\\b|\\bdecoder stats\\b|Receive: |THEME_DIAGNOSTIC sample|receiveCounters )")
    private val fault = Regex("(?i)(writeErrors|authFailures|inputDroppedTotal|decoderUnavailablePacketsTotal|readErrors|sendErrors)=[1-9]")
    private val protocolFailure = Regex("status=[45][0-9]{2}")
    override fun keep(message: String): Boolean {
        if (protocolFailure.containsMatchIn(message)) return true
        if (periodic.containsMatchIn(message)) return fault.containsMatchIn(message)
        if (message.contains("mainAudio unchanged=") || message.contains("iPhone playback metadata")) return false
        if (message.contains("airplay rx ") || message.contains("airplay tx status=") ||
            message.contains("airplay control request ") || message.contains("airplay control response ")) return false
        if (message.contains("airplay command type=") || message.contains("airplay modesChanged detail=") ||
            message.contains("AirPlay command type=") || message.contains("iAP tunnel iap2 rx=0x5001") ||
            message.contains("iap2 rx=0x5001")) return false
        return true
    }
}

class CompactLogCustomization : VehicleCustomization {
    override val id = "compact-logging"
    override val diagnosticPolicy = CompactDiagnosticPolicy()
}

class CompactLogInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        VehicleCustomizations.register(CompactLogCustomization())
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
