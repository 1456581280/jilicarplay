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
)

internal data class HotspotSelection(val name: String, val index: Int, val address: InetAddress) {
    fun sameAddress(other: HotspotSelection): Boolean = name == other.name && index == other.index &&
        address.address.contentEquals(other.address.address) &&
        (address as? Inet6Address)?.scopeId == (other.address as? Inet6Address)?.scopeId
}

internal fun selectHotspotInterface(
    snapshot: HotspotNetworkSnapshot,
    geelyCompatibility: Boolean = false,
    log: (String) -> Unit,
): HotspotSelection? {
    if (!snapshot.consistent || snapshot.apEnabled == false) {
        log("hotspot sample rejected: network_changed=${!snapshot.consistent} apEnabled=${snapshot.apEnabled}")
        return null
    }
    if (geelyCompatibility) return selectGeelyHotspotInterface(snapshot, log)
    return snapshot.interfaces.mapNotNull { iface ->
        val owned = snapshot.apInterfaces?.contains(iface.name) == true
        val upstream = snapshot.wifiUpstreams?.contains(iface.name) == true
        val address = wirelessHostAddress(iface.addresses.filter {
            it is Inet6Address && it.isLinkLocalAddress || it is Inet4Address && it.isSiteLocalAddress
        }, iface.index)
        val reason = when {
            !iface.up || iface.index <= 0 -> "interface_down"
            address == null -> "address_unavailable"
            owned -> "platform_ap"
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
        if (reason != "platform_ap" && reason != "wireless_non_upstream") null
        else (if (owned) 100 else 0) to HotspotSelection(iface.name, iface.index, address!!)
    }.sortedWith(compareByDescending<Pair<Int, HotspotSelection>> { it.first }.thenBy { it.second.name })
        .firstOrNull()?.second
}

// carlito: prefer usable IPv4 without treating an empty OEM AP list as proof of no LAN.
private fun geelyHotspotCandidates(
    snapshot: HotspotNetworkSnapshot,
    log: (String) -> Unit,
    includeDefaultLan: Boolean = false,
): List<HotspotSelection> {
    val excludedPrefixes = listOf("lo", "ip6", "bond", "dummy", "rmnet", "r_rmnet", "tun", "ppp", "sit")
    val ownedInterfaces = snapshot.apInterfaces.orEmpty()
    return snapshot.interfaces.mapNotNull { iface ->
        val owned = iface.name in ownedInterfaces
        val address = existingWifiHostAddresses(iface.addresses, iface.index).firstOrNull()
        // carlito: an OEM Ethernet default route is not proof that its private LAN is an uplink.
        // Cover it as an alternate listener without changing Android's selected default network.
        val defaultLan = includeDefaultLan && iface.name.startsWith("eth") &&
            address is Inet4Address && address.isSiteLocalAddress
        val reason = when {
            !iface.up || iface.index <= 0 -> "interface_down"
            excludedPrefixes.any { iface.name.startsWith(it) } -> "excluded_interface"
            ownedInterfaces.isNotEmpty() && !owned -> "not_platform_ap"
            !owned && ((iface.name == snapshot.defaultInterface && !defaultLan) ||
                snapshot.wifiUpstreams?.contains(iface.name) == true) -> "upstream_without_ap_evidence"
            else -> null
        }
        if (reason != null) {
            log("hotspot candidate compatibility=geely_apk iface=${iface.name} skipped=$reason")
            return@mapNotNull null
        }
        if (address == null) {
            log("hotspot candidate compatibility=geely_apk iface=${iface.name} skipped=address_unavailable")
            return@mapNotNull null
        }
        val interfaceScore = when {
            owned -> 200
            iface.name.startsWith("ap") || iface.name.contains("softap", ignoreCase = true) -> 100
            iface.name.startsWith("p2p") -> 80
            iface.name.startsWith("wlan") || iface.name.startsWith("swlan") -> 70
            else -> 0
        }
        val addressScore = when (address) {
            is Inet4Address -> if (address.isSiteLocalAddress) 500 else 0
            is Inet6Address -> if (address.isLinkLocalAddress) 15 else 0
            else -> 0
        }
        val score = interfaceScore + addressScore
        log("hotspot candidate compatibility=geely_apk iface=${iface.name} index=${iface.index} " +
            "family=${if (address is Inet6Address) "IPv6" else "IPv4"} " +
            "scope=${(address as? Inet6Address)?.scopeId ?: 0} score=$score apEvidence=$owned")
        score to HotspotSelection(iface.name, iface.index, address)
    }.sortedByDescending { it.first }.map { it.second }
}

private fun selectGeelyHotspotInterface(
    snapshot: HotspotNetworkSnapshot,
    log: (String) -> Unit,
): HotspotSelection? {
    val preferred = geelyHotspotCandidates(snapshot, log).firstOrNull()
    if (preferred?.address is Inet4Address) return preferred
    // carlito: if the only observable IPv4 LAN is the OEM Ethernet default, use it before IPv6.
    return geelyHotspotCandidates(snapshot, {}, includeDefaultLan = true)
        .firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress } ?: preferred
}

// carlito: OEM Ethernet VLANs can hide AP ownership. Serve their private IPv4 alternatives
// at the same port rather than trusting the first equal-score VLAN. No subnet is hardcoded.
internal fun manualHotspotAddresses(
    snapshot: HotspotNetworkSnapshot,
    selected: HotspotSelection,
    geelyCompatibility: Boolean,
): List<HotspotSelection> {
    if (!snapshot.consistent || snapshot.apEnabled == false) return emptyList()
    val primary = snapshot.interfaces.firstOrNull {
        it.name == selected.name && it.index == selected.index && it.up
    } ?: return emptyList()
    val addresses = existingWifiHostAddresses(primary.addresses, primary.index)
    if (addresses.none { HotspotSelection(primary.name, primary.index, it).sameAddress(selected) }) {
        return emptyList()
    }
    val result = mutableListOf(selected)
    addresses.filter { it != selected.address }.forEach {
        result.add(HotspotSelection(primary.name, primary.index, it))
    }
    if (geelyCompatibility && snapshot.apInterfaces.isNullOrEmpty()) {
        geelyHotspotCandidates(snapshot, {}, includeDefaultLan = true).filter {
            it.name != selected.name && it.address is Inet4Address && it.address.isSiteLocalAddress
        }.forEach(result::add)
    }
    return result.distinctBy { it.address.hostAddress }
}

internal class ManualHotspotReadiness(
    private val sample: () -> HotspotNetworkSnapshot,
    private val cancelled: () -> Boolean,
    private val pause: (Long) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val geelyCompatibility: Boolean = false,
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
            val selected = selectHotspotInterface(sample(), geelyCompatibility, log)
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
