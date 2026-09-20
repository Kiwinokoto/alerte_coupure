package com.kiwinokoto.powerwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

enum class InternetReachability {
    UNAVAILABLE,
    UNKNOWN,
    CONNECTED_WITHOUT_INTERNET,
    UNVALIDATED,
    VALIDATED
}

enum class NetworkTransport(val displayName: String) {
    WIFI("Wi-Fi"),
    CELLULAR("données mobiles"),
    ETHERNET("Ethernet"),
    VPN("VPN"),
    BLUETOOTH("Bluetooth"),
    OTHER("autre")
}

data class NetworkSnapshot(
    val reachability: InternetReachability,
    val transports: Set<NetworkTransport> = emptySet()
) {
    fun transportLabel(): String? =
        transports
            .sortedBy { it.ordinal }
            .joinToString(" + ") { it.displayName }
            .takeIf { it.isNotBlank() }

    companion object {
        fun read(context: Context): NetworkSnapshot {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager.activeNetwork
                ?: return NetworkSnapshot(InternetReachability.UNAVAILABLE)
            val capabilities = manager.getNetworkCapabilities(network)
                ?: return NetworkSnapshot(InternetReachability.UNKNOWN)

            val transports = buildSet {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    add(NetworkTransport.WIFI)
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    add(NetworkTransport.CELLULAR)
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    add(NetworkTransport.ETHERNET)
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    add(NetworkTransport.VPN)
                }
                @Suppress("DEPRECATION")
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
                    add(NetworkTransport.BLUETOOTH)
                }
                if (isEmpty()) add(NetworkTransport.OTHER)
            }

            val reachability = when {
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                    InternetReachability.CONNECTED_WITHOUT_INTERNET
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                    InternetReachability.VALIDATED
                else -> InternetReachability.UNVALIDATED
            }

            return NetworkSnapshot(reachability, transports)
        }
    }
}
