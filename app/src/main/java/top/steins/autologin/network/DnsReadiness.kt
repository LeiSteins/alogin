package top.steins.autologin.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 等待默认网络收到至少一个 DNS 服务器。
 *
 * Wi-Fi 已连接并不代表 DHCP 已完成 DNS 配置。这里监听 LinkProperties，避免 lgn
 * 检查在 DNS 尚未可用时过早发起。超时后由调用方以普通网络错误处理。
 */
internal suspend fun awaitDefaultNetworkDnsReady(context: Context): Boolean {
    val connectivityManager = context.applicationContext.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as ConnectivityManager

    if (connectivityManager.activeNetwork.hasDnsServers(connectivityManager)) return true

    return withTimeoutOrNull(DNS_READY_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val isRegistered = AtomicBoolean(false)
            var defaultNetwork: Network? = connectivityManager.activeNetwork

            fun unregisterCallback(callback: ConnectivityManager.NetworkCallback) {
                if (isRegistered.compareAndSet(true, false)) {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
            }

            lateinit var callback: ConnectivityManager.NetworkCallback
            fun complete() {
                unregisterCallback(callback)
                if (continuation.isActive) continuation.resume(true)
            }

            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    defaultNetwork = network
                }

                override fun onLost(network: Network) {
                    if (network == defaultNetwork) defaultNetwork = null
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties
                ) {
                    if (network == defaultNetwork && linkProperties.dnsServers.isNotEmpty()) {
                        complete()
                    }
                }
            }

            try {
                connectivityManager.registerDefaultNetworkCallback(callback)
                isRegistered.set(true)

                // 注册期间网络可能已完成 DHCP，重新检查以消除这一竞态。
                if (connectivityManager.activeNetwork.hasDnsServers(connectivityManager)) {
                    complete()
                }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(false)
            }

            continuation.invokeOnCancellation { unregisterCallback(callback) }
        }
    } ?: false
}

private fun Network?.hasDnsServers(connectivityManager: ConnectivityManager): Boolean =
    this != null && connectivityManager.getLinkProperties(this)?.dnsServers?.isNotEmpty() == true

private const val DNS_READY_TIMEOUT_MS = 2_000L
