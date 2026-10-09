package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Prefer the IPv4 hotspot address, then fall back to a scoped IPv6 link-local address.
 *
 * Changan Feiyu OS (Android 9) and most Android AP stacks expose both families on the hotspot
 * interface. CarPlay iPhone clients resolve the accessory over IPv4 first; advertising only a
 * link-local IPv6 address (as the previous implementation did whenever the interface had one)
 * leaves the iPhone unable to reach the AirPlay listener — Wi-Fi discovery stays idle
 * (bonjourAdded=0, tcpAccepted=0) even though the phone joined the AP. The 0.2.13 build that
 * works on the UNI-V used IPv4-first ordering, so keep IPv4 authoritative here.
 */
internal fun wirelessHostAddress(addresses: List<InetAddress>, interfaceIndex: Int): InetAddress? {
    addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }?.let { return it }
    if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            return Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    }
    return null
}

/** Station LAN discovery must cover IPv4 multicast as well as scoped link-local IPv6. */
internal fun existingWifiHostAddresses(addresses: List<InetAddress>, interfaceIndex: Int): List<InetAddress> {
    val ipv4 = addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }
    // Collected independently of wirelessHostAddress(): that helper is IPv4-first for the
    // Changan hotspot, while the station LAN needs both families for dual-stack discovery.
    val ipv6 = if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    } else {
        null
    }
    return listOfNotNull(ipv4, ipv6)
}
