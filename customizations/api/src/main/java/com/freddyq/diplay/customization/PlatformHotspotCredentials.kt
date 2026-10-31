package com.freddyq.diplay.customization

/** Read-only system AP credentials; an empty passphrase denotes a verified open AP. */
class PlatformHotspotCredentials(val ssid: String, val passphrase: String) {
    override fun toString() = "PlatformHotspotCredentials([redacted])"
}
