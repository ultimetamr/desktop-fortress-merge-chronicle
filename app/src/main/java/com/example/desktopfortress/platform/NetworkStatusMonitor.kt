package com.example.desktopfortress.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

class NetworkStatusMonitor(
    context: Context,
    private val onChanged: (Boolean) -> Unit,
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publishCurrent()
        override fun onLost(network: Network) = publishCurrent()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publishCurrent()
    }
    private var registered = false

    fun start() {
        if (registered) return
        registered = runCatching {
            manager.registerDefaultNetworkCallback(callback)
            true
        }.getOrDefault(false)
        publishCurrent()
    }

    fun stop() {
        if (!registered) return
        runCatching { manager.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun publishCurrent() {
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        onChanged(
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        )
    }
}
