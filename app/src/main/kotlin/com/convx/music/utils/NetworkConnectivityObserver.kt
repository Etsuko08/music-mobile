/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

/**
 * Reports validated internet connectivity through a flow and an on-demand check.
 *
 * A single network going away does not mean the device is offline: Wi-Fi can
 * hand over to cellular (or vice versa) while the old callback is still being
 * delivered. Every callback therefore re-checks the *current* state instead of
 * blindly emitting `false` from [onLost].
 */
class NetworkConnectivityObserver(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkStatus = Channel<Boolean>(Channel.CONFLATED)
    val networkStatus = _networkStatus.receiveAsFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publishCurrentState()
        }

        override fun onLost(network: Network) {
            publishCurrentState()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            publishCurrentState()
        }

        override fun onUnavailable() {
            publishCurrentState()
        }
    }

    init {
        val registered = runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        }.isSuccess

        if (!registered) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            runCatching {
                connectivityManager.registerNetworkCallback(request, networkCallback)
            }.onFailure {
                Timber.tag(TAG).w(it, "Network callback registration failed")
            }
        }

        publishCurrentState()
    }

    fun unregister() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            .onFailure { Timber.tag(TAG).d(it, "Network callback was already unregistered") }
    }

    private fun publishCurrentState() {
        _networkStatus.trySend(isCurrentlyConnected())
    }

    /**
     * Returns whether any current network can carry internet traffic.
     *
     * Validation is preferred, but some carriers, VPNs and private Wi-Fi
     * networks never report `NET_CAPABILITY_VALIDATED`. Treating those as
     * offline left playback waiting forever for a connection that was already
     * there, so a plain internet-capable network also counts as connected.
     */
    fun isCurrentlyConnected(): Boolean = try {
        val activeNetwork = connectivityManager.activeNetwork
        hasValidatedInternet(activeNetwork) ||
            connectivityManager.allNetworks.any(::hasValidatedInternet) ||
            hasInternetCapability(activeNetwork)
    } catch (_: Exception) {
        false
    }

    private fun hasValidatedInternet(network: Network?): Boolean {
        val capabilities = capabilitiesOf(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun hasInternetCapability(network: Network?): Boolean {
        val capabilities = capabilitiesOf(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }

    private fun capabilitiesOf(network: Network?): NetworkCapabilities? {
        if (network == null) return null
        return connectivityManager.getNetworkCapabilities(network)
    }

    private companion object {
        const val TAG = "NetworkConnectivity"
    }
}
