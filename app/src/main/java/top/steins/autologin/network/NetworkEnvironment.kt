package top.steins.autologin.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 网络状态的入口抽象，把 ViewModel 与系统 API 解耦，便于单元测试注入替身。
 */
interface NetworkEnvironment {
    fun observeDefaultNetworkChanges(): Flow<DefaultNetworkChange>

    fun fetchCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo

    suspend fun fetchLoginStatus(): LoginStatus

    suspend fun performLogin(
        username: String,
        password: String,
        wlanUserIp: String
    ): LoginResult
}

class DefaultNetworkEnvironment(context: Context) : NetworkEnvironment {

    private val appContext = context.applicationContext

    override fun observeDefaultNetworkChanges(): Flow<DefaultNetworkChange> =
        observeDefaultNetwork(appContext)

    override fun fetchCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo =
        getCurrentNetworkInfo(appContext, canReadWifiName)

    override suspend fun fetchLoginStatus(): LoginStatus =
        checkLoginStatus(appContext)

    override suspend fun performLogin(
        username: String,
        password: String,
        wlanUserIp: String
    ): LoginResult = login(appContext, username, password, wlanUserIp)
}

enum class DefaultNetworkChange {
    INITIAL,
    IP_ADDRESS_CHANGED,
    SSID_CHANGED,
    IP_ADDRESS_AND_SSID_CHANGED
}

internal data class NetworkRefreshSnapshot(
    val ipAddress: String,
    val ssid: String?,
    val isSsidReadable: Boolean = true
)

internal fun detectDefaultNetworkChange(
    previous: NetworkRefreshSnapshot?,
    current: NetworkRefreshSnapshot
): DefaultNetworkChange? {
    if (previous == null) return DefaultNetworkChange.INITIAL

    val ipAddressChanged = previous.ipAddress != current.ipAddress
    val ssidChanged = previous.isSsidReadable && current.isSsidReadable &&
            previous.ssid != current.ssid
    return when {
        ipAddressChanged && ssidChanged -> DefaultNetworkChange.IP_ADDRESS_AND_SSID_CHANGED
        ipAddressChanged -> DefaultNetworkChange.IP_ADDRESS_CHANGED
        ssidChanged -> DefaultNetworkChange.SSID_CHANGED
        else -> null
    }
}

private fun observeDefaultNetwork(context: Context): Flow<DefaultNetworkChange> = callbackFlow {
    val connectivityManager = context.applicationContext.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as ConnectivityManager
    val snapshotLock = Any()
    var previousSnapshot: NetworkRefreshSnapshot? = null

    fun notifyNetworkStateChanged() {
        val canReadWifiName = hasWifiLocationPermission(context)
        val currentSnapshot = runCatching {
            val networkInfo = getCurrentNetworkInfo(context, canReadWifiName)
            NetworkRefreshSnapshot(
                ipAddress = networkInfo.ipAddress,
                ssid = networkInfo.wifiName.takeIf { networkInfo.isWifi && canReadWifiName },
                isSsidReadable = canReadWifiName
            )
        }.getOrNull() ?: return

        val change = synchronized(snapshotLock) {
            detectDefaultNetworkChange(previousSnapshot, currentSnapshot).also {
                previousSnapshot = currentSnapshot
            }
        }
        change?.let(::trySend)
    }

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = notifyNetworkStateChanged()

        override fun onLost(network: Network) = notifyNetworkStateChanged()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            notifyNetworkStateChanged()
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties
        ) {
            notifyNetworkStateChanged()
        }
    }

    try {
        connectivityManager.registerDefaultNetworkCallback(callback)
        notifyNetworkStateChanged()
    } catch (error: SecurityException) {
        close(error)
    }

    awaitClose {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
