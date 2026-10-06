package com.shilapi.xcertplay.network

import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal data class HotspotInterfaceSnapshot(
    val name: String,
    val index: Int,
    val up: Boolean,
    val addresses: List<InetAddress>,
    val wireless: Boolean,
)

internal data class HotspotNetworkSnapshot(
    val interfaces: List<HotspotInterfaceSnapshot>,
    val apInterfaces: Set<String>?,
    val wifiUpstreams: Set<String>?,
    val defaultInterface: String?,
    val consistent: Boolean = true,
    val apEnabled: Boolean? = true,
    val hotspotConfirmed: Boolean = false,
    val vendorHostAddresses: Map<String, InetAddress> = emptyMap(),
    val kx11RoutedHotspot: Boolean = false,
)

internal data class HotspotSelection(val name: String, val index: Int, val address: InetAddress,
    val kx11Routed: Boolean = false) {
    fun sameAddress(other: HotspotSelection): Boolean = name == other.name && index == other.index &&
        address.address.contentEquals(other.address.address) &&
        (address as? Inet6Address)?.scopeId == (other.address as? Inet6Address)?.scopeId && kx11Routed == other.kx11Routed
}

internal fun selectHotspotInterface(snapshot: HotspotNetworkSnapshot, log: (String) -> Unit): HotspotSelection? {
    if (!snapshot.consistent) {
        log("hotspot sample rejected: network_changed=${!snapshot.consistent} apEnabled=${snapshot.apEnabled}")
        return null
    }
    return snapshot.interfaces.mapNotNull { iface ->
        val owned = snapshot.apInterfaces?.contains(iface.name) == true
        val upstream = snapshot.wifiUpstreams?.contains(iface.name) == true
        val vendorAddress = snapshot.vendorHostAddresses[iface.name]?.takeIf { host ->
            iface.addresses.any { it.address.contentEquals(host.address) }
        }
        val routed = if (snapshot.kx11RoutedHotspot)
            GeelyKx11NetworkPolicy.routedAddress(iface.name, iface.addresses) else null
        val conventional = manualHotspotHostAddresses(iface.addresses, iface.index).firstOrNull()
        val conventionalConfirmed = snapshot.apEnabled != false && (owned ||
            snapshot.hotspotConfirmed && conventional is Inet4Address && conventional.isSiteLocalAddress && !upstream)
        // carlito: Preserve a proven client route; otherwise prefer the AP's IPv4 address.
        val address = vendorAddress ?: if (conventionalConfirmed) conventional ?: routed else routed ?: conventional
        val reason = when {
            !iface.up || iface.index <= 0 -> "interface_down"
            address == null -> "address_unavailable"
            snapshot.apEnabled == false && routed == null -> "android_ap_off"
            vendorAddress != null -> "ecarx_client_route"
            owned && snapshot.apEnabled != false && address != routed -> "platform_ap"
            routed != null && address == routed -> "kx11_routed_hotspot"
            snapshot.hotspotConfirmed && address is Inet4Address && address.isSiteLocalAddress &&
                !Regex("^(rmnet|ccmni|pdp|wwan|tun|tap|dummy|veth).*", RegexOption.IGNORE_CASE)
                    .matches(iface.name) && !upstream ->
                "local_car_network_candidate"
            snapshot.hotspotConfirmed && snapshot.apInterfaces.isNullOrEmpty() &&
                iface.wireless && !upstream && snapshot.wifiUpstreams != null &&
                snapshot.defaultInterface != iface.name -> "state_confirmed_wireless_ap"
            snapshot.apInterfaces != null -> "not_platform_ap"
            upstream -> "wifi_upstream"
            snapshot.defaultInterface == iface.name -> "default_network_without_ap_evidence"
            snapshot.wifiUpstreams == null -> "upstream_unobservable"
            !iface.wireless -> "no_ap_evidence"
            else -> "wireless_non_upstream"
        }
        log("hotspot candidate iface=${iface.name} index=${iface.index} " +
            "family=${if (address is Inet6Address) "IPv6" else if (address != null) "IPv4" else "none"} " +
            "scope=${(address as? Inet6Address)?.scopeId ?: 0} evidence=$reason " +
            "ap=${snapshot.apInterfaces?.let { if (owned) "yes" else "no" } ?: "unobservable"} " +
            "defaultConflict=${owned && (upstream || snapshot.defaultInterface == iface.name)}")
        val priority = when (reason) {
            "ecarx_client_route" -> 150
            "platform_ap" -> 100
            "state_confirmed_wireless_ap" -> 90
            "local_car_network_candidate" -> if (snapshot.defaultInterface == iface.name) 5 else if (iface.wireless) 90 else 20
            "wireless_non_upstream" -> 0
            "kx11_routed_hotspot" -> if (iface.name.equals("eth0", ignoreCase = true)) 2 else 1
            else -> return@mapNotNull null
        }
        priority to HotspotSelection(iface.name, iface.index, address!!, reason == "kx11_routed_hotspot")
    }.sortedWith(compareByDescending<Pair<Int, HotspotSelection>> { it.first }.thenBy { it.second.name })
        .firstOrNull()?.second
}

internal class ManualHotspotReadiness(
    private val sample: () -> HotspotNetworkSnapshot,
    private val cancelled: () -> Boolean,
    private val pause: (Long) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    fun await(timeoutMillis: Long): HotspotSelection {
        val deadline = nowMillis() + timeoutMillis
        var previous: HotspotSelection? = null
        var stable = 0
        while (true) {
            if (cancelled()) throw InterruptedIOException("Hotspot readiness cancelled")
            if (nowMillis() >= deadline) throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_NOT_READY, "Hotspot network is not ready",
            )
            val selected = selectHotspotInterface(sample(), log)
            if (cancelled()) throw InterruptedIOException("Hotspot readiness cancelled")
            stable = if (selected != null && previous?.sameAddress(selected) == true) stable + 1 else 1
            previous = selected
            if (selected != null && stable >= WirelessStartupPolicy.STABLE_SAMPLES && nowMillis() < deadline) {
                log("hotspot interface confirmed iface=${selected.name} index=${selected.index} atMs=${nowMillis()}")
                return selected
            }
            pause(minOf(WirelessStartupPolicy.INTERFACE_POLL_MILLIS, (deadline - nowMillis()).coerceAtLeast(1)))
        }
    }
}
