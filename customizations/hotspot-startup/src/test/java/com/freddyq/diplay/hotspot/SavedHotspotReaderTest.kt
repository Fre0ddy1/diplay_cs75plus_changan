package com.freddyq.diplay.hotspot

import android.net.wifi.WifiConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class SavedHotspotReaderTest {
    private fun config(name:String="Car AP", secret:String?="test-key-123") = WifiConfiguration().apply {
        SSID=name; preSharedKey=secret
        allowedKeyManagement.clear()
        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
    }
    @Test fun securedApPreservesExactNameAndPassword() {
        val result=SavedHotspotReader.credentials(config(" Car AP "," \"test-key\" "))!!
        assertEquals(" Car AP ",result.ssid)
        assertEquals(" \"test-key\" ",result.passphrase)
        assertFalse(result.toString().contains("test-key"))
        assertFalse(result.toString().contains("Car AP"))
    }
    @Test fun verifiedOpenApDoesNotReuseAnOldPassword() {
        val c=config().apply { allowedKeyManagement.clear(); allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE) }
        assertEquals("",SavedHotspotReader.credentials(c)!!.passphrase)
    }
    @Test fun hiddenOrMissingSecuredPasswordsAreNeverImportedAsOpen() {
        for (secret in listOf(null,"","*","********")) assertNull(SavedHotspotReader.credentials(config(secret=secret)))
    }
    @Test fun invalidNamesOrPasswordsDoNotReplaceManualConfiguration() {
        for (name in listOf("","<unknown ssid>","路".repeat(11),"a\u0000b")) assertNull(SavedHotspotReader.credentials(config(name)))
        assertNull(SavedHotspotReader.credentials(config(secret="short")))
        assertNull(SavedHotspotReader.credentials(config(secret="bad\u0000pass")))
    }
    @Test fun unknownSecurityIsNotGuessed() {
        assertNull(SavedHotspotReader.credentials(config().apply { allowedKeyManagement.clear() }))
    }
    class FakeManager(val config:WifiConfiguration?) {
        var reads=0
        fun getWifiApConfiguration():WifiConfiguration? { reads++; return config }
    }
    class DeniedManager { fun getWifiApConfiguration():WifiConfiguration = throw SecurityException("denied") }
    @Test fun usesOnlyOneReadOfSavedConfiguration() {
        val m=FakeManager(config())
        assertEquals("Car AP",SavedHotspotReader(m,FakeManager::class.java).read()!!.ssid)
        assertEquals(1,m.reads)
    }
    @Test fun deniedUnavailableOrNullInterfacesReturnNoCredentials() {
        val diagnostics=mutableListOf<String>()
        assertNull(SavedHotspotReader(DeniedManager(),DeniedManager::class.java,diagnostics::add).read())
        assertEquals(listOf("saved AP read failed type=SecurityException"),diagnostics)
        assertNull(SavedHotspotReader(Any(),Any::class.java).read())
        assertNull(SavedHotspotReader(FakeManager(null),FakeManager::class.java).read())
    }
}
