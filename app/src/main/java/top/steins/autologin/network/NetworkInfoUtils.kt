package top.steins.autologin.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

data class WifiScanResult(
    val ssid: String,
    val strength: Int,
    val isConnected: Boolean
)

fun formatFlow(flowKb: String): String {
    val kb = flowKb.trim().toLongOrNull() ?: return flowKb
    return when {
        kb >= 1_048_576 -> "${"%.1f".format(kb / 1_048_576.0)} GB"
        kb >= 1024 -> "${"%.1f".format(kb / 1024.0)} MB"
        else -> "$kb KB"
    }
}

fun getWifiSSID(context: Context): String {
    return try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wifiManager.getConnectionInfo()
        } else {
            wifiManager.connectionInfo
        }
        var ssid = wifiInfo.ssid ?: "未知"
        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        when {
            ssid == "<unknown ssid>" -> "未知"
            ssid.isEmpty() -> "未连接"
            else -> ssid
        }
    } catch (e: Exception) {
        "获取失败"
    }
}

@SuppressLint("DefaultLocale")
fun getDeviceIP(context: Context): String {
    return try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ipInt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wifiManager.getConnectionInfo().ipAddress
        } else {
            wifiManager.connectionInfo.ipAddress
        }
        if (ipInt != 0) {
            return String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        }
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    val host = address.hostAddress
                    if (host != null && !host.startsWith("127.")) {
                        return host
                    }
                }
            }
        }
        "无"
    } catch (e: Exception) {
        "获取失败"
    }
}

@Suppress("DEPRECATION")
fun scanNearbyWifi(context: Context): List<WifiScanResult> {
    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED
    ) {
        return emptyList()
    }
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    if (!wifiManager.isWifiEnabled) {
        return emptyList()
    }
    val connectedSsid = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        wifiManager.connectionInfo.ssid
    } else {
        wifiManager.connectionInfo.ssid
    })?.trim('"') ?: ""
    val results = wifiManager.scanResults ?: return emptyList()
    return results
        .filter { it.SSID.isNotBlank() && it.SSID != "<unknown ssid>" }
        .groupBy { it.SSID }
        .map { (ssid, scans) ->
            WifiScanResult(
                ssid = ssid,
                strength = scans.maxOf { it.level },
                isConnected = ssid == connectedSsid
            )
        }
        .sortedByDescending { it.isConnected }
        .let { sorted ->
            val connected = sorted.filter { it.isConnected }
            val others = sorted.filter { !it.isConnected }.sortedByDescending { it.strength }
            connected + others
        }
}
