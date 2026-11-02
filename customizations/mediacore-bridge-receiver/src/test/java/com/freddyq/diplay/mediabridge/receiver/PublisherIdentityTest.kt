package com.freddyq.diplay.mediabridge.receiver

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PublisherIdentityTest {
    @Suppress("DEPRECATION")
    @Test fun packageAndCertificateMustBothMatchAndSharedUidIsRejected() {
        val pm = RuntimeEnvironment.getApplication().packageManager
        val cert = byteArrayOf(1, 2, 3, 4)
        val hash = MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }
        val info = PackageInfo().apply {
            packageName = "local.publisher"
            applicationInfo = ApplicationInfo().apply { uid = 12345; packageName = "local.publisher" }
            signatures = arrayOf(Signature(cert))
        }
        Shadows.shadowOf(pm).installPackage(info)
        Shadows.shadowOf(pm).setPackagesForUid(12345, "local.publisher")
        val identity = PublisherIdentity(pm, "local.publisher", hash)
        assertTrue(identity.allows(12345)); assertFalse(identity.allows(12346))
        assertFalse(PublisherIdentity(pm, "local.publisher", "0".repeat(64)).allows(12345))
        Shadows.shadowOf(pm).setPackagesForUid(12345, "local.publisher", "other.app")
        assertFalse(identity.allows(12345))
    }
}
