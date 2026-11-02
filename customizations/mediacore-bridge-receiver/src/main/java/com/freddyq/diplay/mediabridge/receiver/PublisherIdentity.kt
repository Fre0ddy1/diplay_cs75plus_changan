package com.freddyq.diplay.mediabridge.receiver

import android.content.pm.PackageManager
import java.security.MessageDigest

/** Explicit package + certificate pin, independent of the OEM host's signing certificate. */
class PublisherIdentity(
    private val packages: PackageManager,
    private val packageName: String,
    certificateSha256: String,
) {
    private val expected = certificateSha256.lowercase().also { require(Regex("[0-9a-f]{64}").matches(it)) }

    @Suppress("DEPRECATION")
    fun allows(uid: Int): Boolean = runCatching {
        // A shared UID cannot distinguish its packages; do not claim package-level isolation there.
        if (packages.getPackagesForUid(uid)?.toSet() != setOf(packageName)) return false
        val info = packages.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        val signatures = info.signatures ?: return false
        signatures.size == 1 && MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())
            .joinToString("") { "%02x".format(it) } == expected
    }.getOrDefault(false)
}
