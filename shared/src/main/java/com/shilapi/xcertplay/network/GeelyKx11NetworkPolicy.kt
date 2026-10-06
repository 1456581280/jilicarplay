// carlito | KX11 Android 9 factory hotspot gateway, isolated from generic IP eligibility.
package com.shilapi.xcertplay.network

import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

internal object GeelyKx11NetworkPolicy {
    fun supported(): Boolean = Build.VERSION.SDK_INT == 28 &&
        Build.MODEL.equals("KX11", ignoreCase = true) && Build.DEVICE.startsWith("kx11", ignoreCase = true)

    fun routedAddress(name: String, addresses: List<InetAddress>): Inet4Address? =
        if (!name.startsWith("eth", ignoreCase = true)) null else addresses.filterIsInstance<Inet4Address>()
            .firstOrNull { (it.address[0].toInt() and 255) == 198 && (it.address[1].toInt() and 255) == 18 }

    fun hasRoutedInterface(): Boolean = supported() && runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any {
            it.isUp && !it.isLoopback && it.index > 0 && routedAddress(it.name, it.inetAddresses.toList()) != null
        }
    }.getOrDefault(false)
}
