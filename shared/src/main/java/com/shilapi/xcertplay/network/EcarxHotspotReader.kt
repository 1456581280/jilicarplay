package com.shilapi.xcertplay.network

import android.content.Context
import android.os.SystemClock
import dalvik.system.PathClassLoader
import java.io.Closeable
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Reads the car-owned AP; no configuration, tethering or client limits are changed. */
internal class EcarxHotspotReader(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : Closeable {
    data class Snapshot(
        val enabled: Boolean? = null,
        val wifi6Enabled: Boolean? = null,
        val band: Int? = null,
        val routedHosts: Map<String, InetAddress> = emptyMap(),
        val diagnostic: String = "ecarxHotspot=unavailable",
    )

    private data class Driver(val api: Class<*>, val instance: Any, val connection: Class<*>?)
    private var drivers: List<Driver>? = null
    private var lastRead = Long.MIN_VALUE
    @Volatile private var latest = Snapshot()
    private val failures = mutableSetOf<String>()
    private var closed = false
    private val loader = apiLoader(context)

    @Synchronized
    fun snapshot(): Snapshot {
        if (closed) return Snapshot()
        val now = SystemClock.elapsedRealtime()
        if (lastRead != Long.MIN_VALUE && now - lastRead < 1_000) return latest
        lastRead = now
        val active = drivers ?: API_NAMES.mapNotNull { name ->
            val api = runCatching { Class.forName(name, false, loader) }
                .onFailure { failure(name.substringAfterLast('.'), "load", it) }.getOrNull()
                ?: return@mapNotNull null
            try {
                val instance = api.getMethod("create", Context::class.java).invoke(null, context)
                    ?: return@mapNotNull null
                val connectable = runCatching {
                    Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable", false, loader)
                        .takeIf { it.isInstance(instance) }
                }.getOrNull()
                val connection = connectable?.let {
                    runCatching { it.getMethod("connect").invoke(instance); it }
                        .onFailure { error -> failure(api.simpleName, "connect", error) }.getOrNull()
                }
                Driver(api, instance, connection)
            } catch (error: Exception) {
                failure(api.simpleName, "create", error)
                null
            } catch (error: LinkageError) {
                failure(api.simpleName, "create", error)
                null
            }
        }.also { drivers = it }
        var wifi6Enabled: Boolean? = null
        var band: Int? = null
        var count = 0
        val peers = mutableListOf<InetAddress>()
        active.forEach { driver ->
            val enabled = invoke(driver, "getWifi6ApEnabled") as? Boolean
            if (enabled != null) wifi6Enabled = enabled
            val clients = (invoke(driver, "getWifiApClients") as? Iterable<*>)
                ?.filterNotNull().orEmpty()
            count += clients.size
            val addresses = clients.mapNotNull { client ->
                runCatching {
                    val api = Class.forName(CLIENT_API, false, loader)
                    numericAddress(api.getMethod("getIP").invoke(client) as? String)
                }.getOrNull()
            }
            peers += addresses
            addresses.forEach { log("LOCAL_NETWORK peer api=${driver.api.simpleName} " +
                "address=${it.hostAddress} family=${if (it is Inet4Address) "IPv4" else "IPv6"} " +
                "scope=${(it as? Inet6Address)?.scopeId ?: 0}") }
            if (enabled == true || clients.isNotEmpty()) {
                val host = invoke(driver, "getWifiAPHost")
                if (host != null) runCatching {
                    val api = Class.forName(HOST_API, false, loader)
                    (api.getMethod("getCurrentFrequencyMode").invoke(host) as? Number)?.toInt()
                }.getOrNull()?.takeIf { it == 1 || it == 2 }?.let { band = it }
            }
        }
        val routes = peers.distinctBy { it.hostAddress }.mapNotNull(::routeToClient).toMap()
        routes.forEach { (name, address) -> log("LOCAL_NETWORK route iface=$name local=${address.hostAddress} result=matched") }
        val enabled = when {
            wifi6Enabled == true || count > 0 -> true
            else -> null // Wifi6 off does not prove that the separate classic AP is off.
        }
        val diagnostic = "ecarxHotspot apis=${active.joinToString(",") { it.api.simpleName }} " +
            "wifi6Enabled=$wifi6Enabled clients=$count addresses=${peers.size} " +
            "routes=${routes.keys.sorted()} band=$band"
        if (diagnostic != latest.diagnostic) log(diagnostic)
        latest = Snapshot(enabled, wifi6Enabled, band, routes, diagnostic)
        return latest
    }

    fun diagnosticSnapshot(): String = latest.diagnostic

    private fun invoke(driver: Driver, method: String): Any? = try {
        driver.api.getMethod(method).invoke(driver.instance)
    } catch (_: NoSuchMethodException) {
        null
    } catch (error: Exception) {
        failure(driver.api.simpleName, method, error)
        null
    } catch (error: LinkageError) {
        failure(driver.api.simpleName, method, error)
        null
    }

    private fun failure(api: String, method: String, error: Throwable) {
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        val diagnostic = "ecarxHotspot api=$api method=$method failed=${cause.javaClass.simpleName}"
        if (failures.add(diagnostic)) log(diagnostic)
    }

    private fun routeToClient(peer: InetAddress): Pair<String, InetAddress>? = runCatching {
        if (!(peer is Inet4Address && peer.isSiteLocalAddress ||
                peer is Inet6Address && peer.isLinkLocalAddress && peer.scopeId > 0)) {
            log("LOCAL_NETWORK route peer=${peer.hostAddress} result=unsupported_address_or_missing_scope")
            return null
        }
        val routed = runCatching { DatagramSocket().use { socket ->
            // connect resolves a local route without sending traffic or binding the process.
            socket.connect(peer, 9)
            val host = socket.localAddress
            log("LOCAL_NETWORK route peer=${peer.hostAddress} local=${host.hostAddress} result=socket_route")
            val iface = NetworkInterface.getByInetAddress(host) ?: return@use null
            if (!iface.isUp || iface.isLoopback) return@use null
            val direct = if (peer is Inet6Address) peer.scopeId == iface.index else
                iface.interfaceAddresses.any { entry ->
                    val address = entry.address
                    address is Inet4Address && address.address.contentEquals(host.address) &&
                        sameSubnet(address.address, peer.address, entry.networkPrefixLength.toInt())
                }
            if (!direct) null else iface.name to host
        } }.getOrNull()
        if (routed != null) return@runCatching routed
        // carlito: Vendor APs can lack a usable default socket route. Accept only a unique
        // local subnet match; never infer an AP from an unrelated default-network address.
        if (peer !is Inet4Address) return@runCatching null
        val matches = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface -> iface.interfaceAddresses.mapNotNull { entry ->
                val host = entry.address
                if (host is Inet4Address && host.isSiteLocalAddress &&
                    sameSubnet(host.address, peer.address, entry.networkPrefixLength.toInt()))
                    iface.name to host else null
            } }.distinct()
        log("LOCAL_NETWORK route peer=${peer.hostAddress} subnetMatches=${matches.size} " +
            "result=${if (matches.size == 1) "unique_subnet" else if (matches.isEmpty()) "no_local_subnet" else "ambiguous_subnet"}")
        matches.singleOrNull()
    }.getOrNull()

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        drivers.orEmpty().forEach { driver ->
            driver.connection?.let { connection ->
                runCatching { connection.getMethod("disconnect").invoke(driver.instance) }
                    .onFailure { failure(driver.api.simpleName, "disconnect", it) }
            }
        }
        drivers = null
    }

    companion object {
        private val API_NAMES = listOf(
            "com.ecarx.xui.adaptapi.wifiap.WifiAp",
            "com.ecarx.xui.adaptapi.wifiap.Wifi6Ap",
        )
        private const val CLIENT_API = "com.ecarx.xui.adaptapi.wifiap.IWifiApClient"
        private const val HOST_API = "com.ecarx.xui.adaptapi.wifiap.IWifiAPHost"
        @Volatile private var cachedLoader: ClassLoader? = null

        private fun apiLoader(context: Context): ClassLoader {
            cachedLoader?.let { return it }
            val parent = context.classLoader
            if (API_NAMES.all { runCatching { Class.forName(it, false, parent) }.isSuccess }) {
                return parent.also { cachedLoader = it }
            }
            // Some ROMs expose the SDK to system apps without adding it to an installed app's loader.
            val jars = listOf("/system/framework", "/system_ext/framework", "/vendor/framework")
                .flatMap { directory -> runCatching { File(directory).listFiles()?.toList() }.getOrNull().orEmpty() }
                .filter { file -> file.isFile && file.canRead() && file.extension == "jar" &&
                    (file.name.contains("ecarx", true) || file.name.contains("adaptapi", true)) }
                .sortedBy { it.absolutePath }
            val loader = if (jars.isEmpty()) parent else runCatching {
                PathClassLoader(jars.joinToString(File.pathSeparator) { it.absolutePath }, parent)
            }.getOrDefault(parent)
            return loader.also { cachedLoader = it }
        }

        fun available(context: Context): Boolean = API_NAMES.any { name ->
            runCatching { Class.forName(name, false, apiLoader(context)) }.isSuccess
        }

        private fun numericAddress(value: String?): InetAddress? {
            val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val ipv4 = raw.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) &&
                raw.split('.').all { it.toInt() in 0..255 }
            val ipv6 = ':' in raw && raw.substringBefore('%').matches(Regex("[0-9a-fA-F:.]+")) &&
                raw.substringAfter('%', "").matches(Regex("[a-zA-Z0-9_.-]*"))
            return if (ipv4 || ipv6) runCatching { InetAddress.getByName(raw) }.getOrNull() else null
        }

        private fun sameSubnet(first: ByteArray, second: ByteArray, prefix: Int): Boolean {
            if (first.size != second.size || prefix !in 1..first.size * 8) return false
            return (0 until prefix).all { bit ->
                val mask = 1 shl (7 - bit % 8)
                (first[bit / 8].toInt() and mask) == (second[bit / 8].toInt() and mask)
            }
        }
    }
}
