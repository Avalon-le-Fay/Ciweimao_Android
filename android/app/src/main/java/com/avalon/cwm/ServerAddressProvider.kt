package com.avalon.cwm

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Produces the same localhost/LAN URL information shown by the bundled web UI. */
object ServerAddressProvider {
    const val PORT = 8080
    const val LOCAL_URL = "http://127.0.0.1:8080"

    fun lanUrls(): List<String> {
        val addresses = linkedSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (network in Collections.list(interfaces)) {
                if (!network.isUp || network.isLoopback) continue
                for (address in Collections.list(network.inetAddresses)) {
                    if (
                        address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    ) {
                        addresses += "http://${address.hostAddress}:$PORT"
                    }
                }
            }
        } catch (_: Throwable) {
            // Address discovery is best effort. Localhost remains available without LAN data.
        }
        return addresses.sortedWith(
            compareBy<String> { !it.contains("192.168.") }.thenBy { it },
        )
    }
}
