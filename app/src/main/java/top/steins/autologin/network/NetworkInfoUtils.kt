package top.steins.autologin.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.Locale
import kotlin.coroutines.resume

data class CurrentNetworkInfo(
    val wifiName: String,
    val ipAddress: String,
    val isWifi: Boolean,
    val isConnected: Boolean
) {
    companion object {
        val Disconnected = CurrentNetworkInfo(
            wifiName = "未连接",
            ipAddress = "无",
            isWifi = false,
            isConnected = false
        )
    }
}

data class WifiScanResult(
    val ssid: String,
    val strength: Int,
    val isConnected: Boolean
)

sealed interface WifiScanOutcome {
    data class Success(
        val results: List<WifiScanResult>,
        val isFreshResult: Boolean
    ) : WifiScanOutcome

    data object PermissionDenied : WifiScanOutcome
    data object WifiDisabled : WifiScanOutcome
    data object NoResults : WifiScanOutcome
    data class Failure(val message: String) : WifiScanOutcome
}

fun formatFlowMb(flowMb: String): String {
    val value = flowMb.trim()
    val mb = value.toDoubleOrNull()
        ?: return if (value.contains("MB", ignoreCase = true)) value else "$value MB"
    return when {
        mb >= 1024 -> String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0)
        else -> String.format(Locale.getDefault(), "%.1f MB", mb)
    }
}

fun hasWifiLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

/**
 * 只读取当前默认网络的信息，避免在 Wi-Fi IP 缺失时误取 VPN、蜂窝或其他网卡的地址。
 */
fun getCurrentNetworkInfo(
    context: Context,
    canReadWifiName: Boolean = hasWifiLocationPermission(context)
): CurrentNetworkInfo {
    val connectivityManager = context.applicationContext.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return CurrentNetworkInfo.Disconnected
    val capabilities = connectivityManager.getNetworkCapabilities(network)
    val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val ipAddress = connectivityManager.getLinkProperties(network)
        ?.linkAddresses
        ?.asSequence()
        ?.map { it.address }
        ?.filterIsInstance<Inet4Address>()
        ?.firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress
        ?: "无"

    return CurrentNetworkInfo(
        wifiName = when {
            !isWifi -> "未连接"
            !canReadWifiName -> "需要位置权限"
            else -> readWifiSsid(context, capabilities)
        },
        ipAddress = ipAddress,
        isWifi = isWifi,
        isConnected = true
    )
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun readWifiSsid(context: Context, capabilities: NetworkCapabilities?): String {
    return try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            selectWifiSsid(
                transportSsid = (capabilities?.transportInfo as? WifiInfo)?.ssid,
                wifiManagerSsid = wifiManager.connectionInfo.ssid
            )
        } else {
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo.ssid.normalizeSsid()
        }
    } catch (_: SecurityException) {
        "需要位置权限"
    } catch (_: Exception) {
        "获取失败"
    }
}

@Suppress("DEPRECATION")
suspend fun scanNearbyWifi(context: Context): WifiScanOutcome = withContext(Dispatchers.Main.immediate) {
    if (!hasWifiLocationPermission(context)) return@withContext WifiScanOutcome.PermissionDenied

    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    if (!wifiManager.isWifiEnabled) return@withContext WifiScanOutcome.WifiDisabled

    val isFreshResult = try {
        awaitWifiScanResult(context.applicationContext, wifiManager)
    } catch (error: SecurityException) {
        return@withContext WifiScanOutcome.PermissionDenied
    } catch (error: Exception) {
        return@withContext WifiScanOutcome.Failure(error.message ?: "无法扫描附近 WiFi")
    }

    val connectedSsid = getCurrentNetworkInfo(context, canReadWifiName = true).wifiName
    val rawScanResults = try {
        wifiManager.scanResults.orEmpty()
    } catch (_: SecurityException) {
        return@withContext WifiScanOutcome.PermissionDenied
    }
    val results = rawScanResults
        .asSequence()
        .filter { it.SSID.isNotBlank() && it.SSID != UNKNOWN_SSID }
        .groupBy { it.SSID }
        .map { (ssid, scans) ->
            WifiScanResult(
                ssid = ssid,
                strength = scans.maxOf { it.level },
                isConnected = ssid == connectedSsid
            )
        }
        .sortedWith(
            compareByDescending<WifiScanResult> { it.isConnected }
                .thenByDescending { it.strength }
        )
        .toList()

    if (results.isEmpty()) WifiScanOutcome.NoResults
    else WifiScanOutcome.Success(results, isFreshResult)
}

@SuppressLint("MissingPermission", "Deprecated")
@Suppress("DEPRECATION")
private suspend fun awaitWifiScanResult(context: Context, wifiManager: WifiManager): Boolean {
    return withTimeoutOrNull(WIFI_SCAN_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            var receiverRegistered = false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                    unregisterReceiver(context, this, receiverRegistered)
                    receiverRegistered = false
                    if (continuation.isActive) {
                        continuation.resume(
                            intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                        )
                    }
                }
            }

            fun completeWithCachedResults() {
                unregisterReceiver(context, receiver, receiverRegistered)
                receiverRegistered = false
                if (continuation.isActive) continuation.resume(false)
            }

            try {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
                if (!wifiManager.startScan()) completeWithCachedResults()
            } catch (error: Exception) {
                unregisterReceiver(context, receiver, receiverRegistered)
                receiverRegistered = false
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }

            continuation.invokeOnCancellation {
                unregisterReceiver(context, receiver, receiverRegistered)
                receiverRegistered = false
            }
        }
    } ?: false
}

private fun unregisterReceiver(context: Context, receiver: BroadcastReceiver, isRegistered: Boolean) {
    if (!isRegistered) return
    runCatching { context.unregisterReceiver(receiver) }
}

/**
 * Android 12+ 可能会在 [NetworkCapabilities.transportInfo] 中返回已脱敏的 SSID。
 * 该值仍非空，不能只依赖 Elvis 运算符回退到 [WifiManager.connectionInfo]。
 */
internal fun selectWifiSsid(transportSsid: String?, wifiManagerSsid: String?): String {
    val ssid = transportSsid.takeIf { it.isReadableSsid() } ?: wifiManagerSsid
    return ssid.normalizeSsid()
}

private fun String?.isReadableSsid(): Boolean {
    val value = this?.trim('"').orEmpty()
    return value.isNotBlank() && value != UNKNOWN_SSID
}

private fun String?.normalizeSsid(): String {
    val value = this?.trim('"').orEmpty()
    return when {
        value.isBlank() -> "未连接"
        value == UNKNOWN_SSID -> "未知"
        else -> value
    }
}

private const val UNKNOWN_SSID = "<unknown ssid>"
private const val WIFI_SCAN_TIMEOUT_MS = 10_000L
