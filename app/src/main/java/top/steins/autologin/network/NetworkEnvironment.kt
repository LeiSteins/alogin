package top.steins.autologin.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 网络状态的入口抽象，把 ViewModel 与系统 API 解耦，便于单元测试注入替身。
 */
interface NetworkEnvironment : AutoCloseable {
    fun observeDefaultNetworkChanges(): Flow<DefaultNetworkChange>

    fun fetchCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo

    fun hasValidatedInternet(): Boolean

    suspend fun fetchLoginStatus(): LoginStatus

    suspend fun performLogin(
        username: String,
        password: String,
        wlanUserIp: String
    ): LoginResult

    override fun close() = Unit
}

class DefaultNetworkEnvironment(context: Context) : NetworkEnvironment {

    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as ConnectivityManager
    private val stateLock = Any()
    private val availableWifiNetworks = linkedSetOf<Network>()
    private val networkChanges = MutableSharedFlow<DefaultNetworkChange>(
        replay = 1,
        extraBufferCapacity = 4
    )

    @Volatile
    private var physicalWifiNetwork: Network? = null

    private var previousSnapshot: NetworkRefreshSnapshot? = null
    private var callbackRegistered = false

    private val physicalWifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(stateLock) {
                availableWifiNetworks += network
                physicalWifiNetwork = network
            }
            notifyNetworkStateChanged()
        }

        override fun onLost(network: Network) {
            val selectedNetworkChanged = synchronized(stateLock) {
                availableWifiNetworks -= network
                if (physicalWifiNetwork == network) {
                    physicalWifiNetwork = availableWifiNetworks.lastOrNull()
                    true
                } else {
                    false
                }
            }
            if (selectedNetworkChanged) notifyNetworkStateChanged()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == physicalWifiNetwork) notifyNetworkStateChanged()
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties
        ) {
            if (network == physicalWifiNetwork) notifyNetworkStateChanged()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        callbackRegistered = runCatching {
            connectivityManager.registerNetworkCallback(request, physicalWifiCallback)
        }.isSuccess

        // 无 Wi-Fi 时不会收到 onAvailable，仍需要一次初始状态以更新界面。
        notifyNetworkStateChanged()
    }

    override fun observeDefaultNetworkChanges(): Flow<DefaultNetworkChange> =
        networkChanges.asSharedFlow()

    override fun fetchCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo =
        getCurrentNetworkInfo(appContext, canReadWifiName, physicalWifiNetwork)

    override fun hasValidatedInternet(): Boolean {
        val connectivityManager = appContext.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override suspend fun fetchLoginStatus(): LoginStatus =
        checkLoginStatus(appContext)

    override suspend fun performLogin(
        username: String,
        password: String,
        wlanUserIp: String
    ): LoginResult = login(appContext, username, password, wlanUserIp)

    override fun close() {
        if (!callbackRegistered) return
        callbackRegistered = false
        runCatching { connectivityManager.unregisterNetworkCallback(physicalWifiCallback) }
    }

    private fun notifyNetworkStateChanged() {
        val canReadWifiName = hasWifiLocationPermission(appContext)
        val networkInfo = runCatching {
            getCurrentNetworkInfo(appContext, canReadWifiName, physicalWifiNetwork)
        }.getOrNull() ?: return
        val currentSnapshot = NetworkRefreshSnapshot(
            ipAddress = networkInfo.ipAddress,
            ssid = networkInfo.wifiName.takeIf { networkInfo.isWifi && canReadWifiName },
            isSsidReadable = canReadWifiName
        )

        val change = synchronized(stateLock) {
            detectDefaultNetworkChange(previousSnapshot, currentSnapshot).also {
                previousSnapshot = currentSnapshot
            }
        }
        change?.let(networkChanges::tryEmit)
    }
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
