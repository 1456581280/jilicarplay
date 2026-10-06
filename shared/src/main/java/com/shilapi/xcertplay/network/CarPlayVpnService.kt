package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.isInternalAirPlayPeer
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val additionalAddresses: List<InetAddress> = emptyList(),
        val listenerIdentity: AirPlayListenerIdentity? = null,
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var additionalServers: List<ServerSocket> = emptyList()
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0
    private var wirelessSessionOwner: AirPlaySession? = null

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .setBlocking(true)
                // An empty app list routes every UID through this VPN. Scope it before establish;
                // rejection must reach the existing attachment cleanup, never an unscoped retry.
                .addAllowedApplication(packageName)
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd

            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                onTransportError(generation, listener, error)
            }
            ipv6Bridge.start()
            bridge = ipv6Bridge

            startAirPlayServer(
                generation,
                AirPlayAttachment(address, config, identity, pairings, mfi, listener, media),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        additionalBindAddresses: List<InetAddress> = emptyList(),
        listenerIdentity: AirPlayListenerIdentity? = null,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, listener, media,
                    additionalBindAddresses, listenerIdentity),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    @Synchronized
    fun detachWireless(owner: AirPlayListenerIdentity) {
        if (attachment?.listenerIdentity === owner) releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    /** Port the AirPlay listener actually bound, which may differ from the configured port. */
    fun boundPort(): Int? = attachment?.config?.port

    @Synchronized
    fun boundWirelessAddresses(owner: AirPlayListenerIdentity): List<InetAddress> =
        if (attachment?.listenerIdentity !== owner) emptyList() else
            (listOfNotNull(serverSocket) + additionalServers).filter { !it.isClosed }.map { it.inetAddress }

    /** carlito: Refresh listeners without tearing down established media or the USB/VPN path. */
    @Synchronized
    fun updateWirelessAddresses(owner: AirPlayListenerIdentity, addresses: List<InetAddress>): List<InetAddress> {
        val current = attachment ?: return emptyList()
        if (current.listenerIdentity !== owner || !active.get()) return emptyList()
        val wanted = addresses.distinctBy(::networkAddressKey)
        val wantedKeys = wanted.map(::networkAddressKey).toSet()
        val old = listOfNotNull(serverSocket) + additionalServers
        val servers = old.filter { !it.isClosed && networkAddressKey(it.inetAddress) in wantedKeys }.toMutableList()
        val present = servers.map { networkAddressKey(it.inetAddress) }.toSet()
        wanted.filter { networkAddressKey(it) !in present }.forEach { address ->
            bindAdditional(address, current.config.port, current)?.let(servers::add)
        }
        serverSocket = servers.firstOrNull()
        additionalServers = servers.drop(1)
        old.filter { it !in servers }.forEach { runCatching { it.close() } }
        servers.filter { it !in old }.forEach { startAcceptThread(attachGeneration, it) }
        return servers.map { it.inetAddress }
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val servers = if (replacement.listenerIdentity != null) {
            // carlito: Optional local candidates may disappear while starting. Keep healthy paths.
            val primary = AirPlayPortSelector.bind(replacement.address, replacement.config.port)
            listOf(primary) + replacement.additionalAddresses.distinctBy(::networkAddressKey)
                .filter { networkAddressKey(it) != networkAddressKey(replacement.address) }
                .mapNotNull { bindAdditional(it, primary.localPort, replacement) }
        } else if (replacement.additionalAddresses.isEmpty()) {
            listOf(AirPlayPortSelector.bind(replacement.address, replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            })
        } else {
            AirPlayPortSelector.bindAll(listOf(replacement.address) + replacement.additionalAddresses,
                replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            }
        }
        val server = servers.first()
        attachment = replacement.copy(config = replacement.config.copy(port = server.localPort))
        serverSocket = server
        additionalServers = servers.drop(1)
        servers.forEach { bound ->
            runCatching { replacement.listener.onDebugLog(
                "LOCAL_NETWORK listener ${WirelessNetworkPaths.describe(bound.inetAddress)} port=${bound.localPort} result=ready",
            ) }
            startAcceptThread(generation, bound)
        }
    }

    private fun startAcceptThread(generation: Int, server: ServerSocket) {
        Thread({ acceptLoop(generation, server) }, "airplay-accept").apply { isDaemon = true; start() }
    }

    private fun bindAdditional(address: InetAddress, port: Int, current: AirPlayAttachment): ServerSocket? {
        val server = ServerSocket()
        return try {
            server.bind(InetSocketAddress(address, port))
            current.listener.onDebugLog("LOCAL_NETWORK listener ${WirelessNetworkPaths.describe(address)} port=$port result=ready")
            server
        } catch (error: Exception) {
            runCatching { server.close() }
            runCatching { current.listener.onDebugLog("LOCAL_NETWORK listener ${WirelessNetworkPaths.describe(address)} " +
                "port=$port result=failed failure=${error.javaClass.simpleName}") }
            null
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                val acceptedAtNanos = System.nanoTime()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get() || generation != attachGeneration ||
                        (serverSocket !== server && additionalServers.none { it === server })) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    val internalPeer = isInternalAirPlayPeer(socket.inetAddress, socket.localAddress)
                    current.listenerIdentity?.let { owner ->
                        current.listener.onTcpAccepted(AirPlayTcpAccepted(
                            owner, internalPeer, acceptedAtNanos,
                        ))
                    }
                    runCatching { current.listener.onDebugLog(
                        "LOCAL_NETWORK accepted ${WirelessNetworkPaths.describe(socket.localAddress)} " +
                            "peer=${socket.inetAddress.hostAddress} port=${socket.localPort} internal=$internalPeer",
                    ) }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionActive(session: AirPlaySession) {
                                // carlito: Only an established protocol session owns wireless media.
                                if (current.listenerIdentity != null && !internalPeer) {
                                    val claimed = synchronized(this@CarPlayVpnService) {
                                        if (generation != attachGeneration || !active.get()) false
                                        else if (wirelessSessionOwner == null || wirelessSessionOwner === session ||
                                            (wirelessSessionOwner?.remoteAddress?.address?.contentEquals(
                                                session.remoteAddress?.address ?: byteArrayOf()) == true &&
                                                wirelessSessionOwner?.localAddress?.let { local ->
                                                    session.localAddress?.let { networkAddressKey(it) == networkAddressKey(local) }
                                                } == true) ||
                                            session.controllerId?.let { id -> id.isNotBlank() &&
                                                wirelessSessionOwner?.controllerId == id } == true) {
                                            wirelessSessionOwner = session
                                            true
                                        } else false
                                    }
                                    if (!claimed) { session.close(); return }
                                    current.listener.onDebugLog("LOCAL_NETWORK protocol established " +
                                        "${WirelessNetworkPaths.describe(socket.localAddress)} peer=${socket.inetAddress.hostAddress}")
                                }
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionActive(session)
                            }

                            override fun onRemoteControlMessage(
                                session: AirPlaySession,
                                streamId: Long,
                                message: Map<String, Any?>,
                            ) {
                                current.listener.onRemoteControlMessage(session, streamId, message)
                            }

                            override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
                                current.listener.onVideoPlaybackUiRequested(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                val owned = synchronized(this@CarPlayVpnService) {
                                    (wirelessSessionOwner === session).also { if (it) wirelessSessionOwner = null }
                                }
                                if (current.listenerIdentity == null || !internalPeer && owned) current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            val stillOwned = synchronized(this) {
                active.get() && generation == attachGeneration && !server.isClosed &&
                    (serverSocket === server || server in additionalServers)
            }
            if (stillOwned) {
                // carlito: Losing one optional accept loop must not drop a healthy wireless path.
                val current = synchronized(this) {
                    attachment?.takeIf { it.listenerIdentity != null && active.get() && generation == attachGeneration }?.also {
                        val remaining = (listOfNotNull(serverSocket) + additionalServers)
                            .filter { candidate -> candidate !== server && !candidate.isClosed }
                        serverSocket = remaining.firstOrNull()
                        additionalServers = remaining.drop(1)
                        runCatching { server.close() }
                    }
                }
                if (current != null && boundWirelessAddresses(current.listenerIdentity!!).isNotEmpty()) {
                    current.listener.onDebugLog("LOCAL_NETWORK listener ${WirelessNetworkPaths.describe(server.inetAddress)} " +
                        "result=stopped failure=${error.javaClass.simpleName}")
                    return
                }
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                val releasedGeneration = synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                    attachGeneration
                }
                listener.onTransportError(message)
                synchronized(this) {
                    if (attachGeneration == releasedGeneration && !active.get()) stopSelf()
                }
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        wirelessSessionOwner = null
        attachment = null
        serverSocket?.close()
        serverSocket = null
        additionalServers.forEach { it.close() }
        additionalServers = emptyList()
        closeSessionsLocked()
        bridge?.close()
        bridge = null
        tun?.close()
        tun = null
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }
}
