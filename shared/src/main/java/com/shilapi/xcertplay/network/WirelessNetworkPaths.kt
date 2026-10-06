package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.Closeable
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket

// carlito: Keep interface scope in keys: Inet6Address.equals ignores the scope ID.
internal fun networkAddressKey(address: InetAddress): String =
    address.address.joinToString("") { "%02x".format(it.toInt() and 255) } +
        ":${(address as? Inet6Address)?.scopeId ?: 0}"

/** carlito: Local car networks include Ethernet/VLAN bridges, not just named Wi-Fi interfaces. */
internal object WirelessNetworkPaths {
    fun addresses(context: Context): List<InetAddress> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val excluded = runCatching {
            connectivity?.allNetworks.orEmpty().mapNotNull { network ->
                val caps = connectivity?.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                    connectivity?.getLinkProperties(network)?.interfaceName else null
            }.toSet()
        }.getOrDefault(emptySet())
        return runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList()).sortedBy { it.name }.flatMap { iface ->
                runCatching {
                    if (!iface.isUp || iface.isLoopback || iface.index <= 0 || iface.name in excluded ||
                        Regex("^(rmnet|ccmni|pdp|wwan|tun|tap|dummy|veth|sit|ip6tnl).*", RegexOption.IGNORE_CASE)
                            .matches(iface.name)) return@runCatching emptyList<InetAddress>()
                    val addresses = iface.inetAddresses.toList()
                    val ipv4 = addresses.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
                    // An IPv6-only physical link is still useful; virtual placeholders are not.
                    val ipv6 = if (ipv4.isNotEmpty() || wirelessInterfaceName(iface.name))
                        addresses.filterIsInstance<Inet6Address>().filter { it.isLinkLocalAddress }
                            .map { Inet6Address.getByAddress(null, it.address, iface.index) } else emptyList()
                    (ipv4 + ipv6).take(8)
                }.getOrDefault(emptyList())
            }.distinctBy(::networkAddressKey).take(64)
    }

    fun describe(address: InetAddress): String {
        val iface = runCatching {
            if (address is Inet6Address && address.scopeId > 0) NetworkInterface.getByIndex(address.scopeId)
            else NetworkInterface.getByInetAddress(address)
        }.getOrNull()
        val prefix = iface?.interfaceAddresses?.firstOrNull {
            it.address.address.contentEquals(address.address)
        }?.networkPrefixLength
        return "iface=${iface?.name ?: "unknown"} index=${iface?.index ?: 0} " +
            "local=${address.hostAddress} prefix=${prefix ?: "unknown"} " +
            "family=${if (address is Inet4Address) "IPv4" else "IPv6"} " +
            "scope=${(address as? Inet6Address)?.scopeId ?: 0}"
    }

    fun bindSocket(context: Context, socket: Socket, source: InetAddress) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val name = NetworkInterface.getByInetAddress(source)?.name ?: return
        // carlito: Bind only this probe; never change the process's USB/cloud/default routing.
        manager.allNetworks.firstOrNull { manager.getLinkProperties(it)?.interfaceName == name &&
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false }
            ?.bindSocket(socket)
    }
}

/** carlito: Framework callbacks plus polling cover vendor APs absent from ConnectivityManager. */
internal class WirelessNetworkMonitor(
    context: Context,
    private val onChange: (List<InetAddress>) -> Unit,
    private val log: (String) -> Unit,
    private val initialAddresses: List<InetAddress> = emptyList(),
) : Closeable {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val signal = Object()
    @Volatile private var closed = false
    private var previous: List<String>? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = wake()
        override fun onLost(network: Network) = wake()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = wake()
    }
    private val registered = runCatching {
        checkNotNull(connectivity).registerNetworkCallback(
            NetworkRequest.Builder().clearCapabilities().build(), callback)
    }.isSuccess
    private val worker = Thread({
        while (!closed) {
            try {
                val known = initialAddresses.filter { address -> runCatching {
                    val iface = if (address is Inet6Address && address.scopeId > 0)
                        NetworkInterface.getByIndex(address.scopeId) else NetworkInterface.getByInetAddress(address)
                    iface?.isUp == true && iface.inetAddresses.toList().any { it.address.contentEquals(address.address) }
                }.getOrDefault(false) }
                val addresses = (known + WirelessNetworkPaths.addresses(appContext)).distinctBy(::networkAddressKey)
                val keys = addresses.map(::networkAddressKey)
                if (keys != previous && !closed) {
                    previous = keys
                    log("LOCAL_NETWORK path_change count=${addresses.size} callbacks=$registered")
                    addresses.forEach { log("LOCAL_NETWORK path ${WirelessNetworkPaths.describe(it)}") }
                    onChange(addresses)
                }
                synchronized(signal) { if (!closed) signal.wait(2_000) }
            } catch (_: InterruptedException) { return@Thread }
            catch (error: Exception) { log("LOCAL_NETWORK monitor failed=${error.javaClass.simpleName}") }
        }
    }, "diplay-network-paths").apply { isDaemon = true }

    fun start() = worker.start()
    private fun wake() { synchronized(signal) { signal.notifyAll() } }
    override fun close() {
        closed = true
        if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
        worker.interrupt()
        wake()
    }
}
