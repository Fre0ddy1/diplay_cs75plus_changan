package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WirelessHostAddressTest {
    @Test fun manualApPrefersIpv4OverLinkLocal() {
        // Changan: the hotspot interface exposes both families; the iPhone resolves IPv4 first.
        val result = wirelessHostAddress(listOf(ip("192.168.43.1"), ip("fe80::1234")), 7)
        assertEquals("192.168.43.1", result!!.hostAddress)
    }

    @Test fun replacesScopeFromAnotherInterface() {
        val wrongScope = Inet6Address.getByAddress(null, ip("fe80::1234").address, 3)
        assertEquals(8, (wirelessHostAddress(listOf(wrongScope), 8) as Inet6Address).scopeId)
    }

    @Test fun fallsBackToIpv4WithoutUsableLinkLocal() {
        val ipv4 = ip("192.168.43.1")
        assertEquals(ipv4, wirelessHostAddress(listOf(ip("::1"), ip("2001:db8::1"), ipv4), 7))
        assertEquals(ipv4, wirelessHostAddress(listOf(ip("fe80::1234"), ipv4), 0))
        assertNull(wirelessHostAddress(listOf(ip("0.0.0.0"), ip("127.0.0.1"), ip("224.0.0.251")), 7))
    }

    @Test fun stationDiscoveryCoversBothFamiliesIpv4First() {
        // existingWifiHostAddresses returns IPv4 (primary) plus scoped IPv6 link-local.
        val result = existingWifiHostAddresses(listOf(ip("192.168.1.10"), ip("fe80::1")), 9)
        assertEquals(2, result.size)
        assertEquals("192.168.1.10", result[0].hostAddress)
        assertTrue(result[1] is Inet6Address)
        assertEquals(9, (result[1] as Inet6Address).scopeId)
        val ipv4Only = existingWifiHostAddresses(listOf(ip("192.168.1.10")), 9)
        assertEquals(1, ipv4Only.size)
        assertEquals("192.168.1.10", ipv4Only[0].hostAddress)
        val ipv6Only = existingWifiHostAddresses(listOf(ip("fe80::1")), 9)
        assertEquals(1, ipv6Only.size)
        assertTrue(ipv6Only[0] is Inet6Address)
    }

    private fun ip(value: String) = InetAddress.getByName(value)
}
