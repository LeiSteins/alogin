package top.steins.autologin.network

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

internal data class DeviceRow(
    val mac: String,
    val status: String,
    val ipAddress: String,
    val isOnline: Boolean?
)

internal sealed interface JsonObjectExtraction {
    data class Found(val text: String) : JsonObjectExtraction

    /** 页面中没有找到 JSON 对象。 */
    data object Missing : JsonObjectExtraction

    /** 找到了 `{` 但没有匹配的 `}`。 */
    data object Incomplete : JsonObjectExtraction
}

/**
 * 自助服务页面解析的纯函数集合，不依赖 Android 上下文，便于单元测试。
 * 异常消息的本地化与提示由调用方负责。
 */
internal object SelfServiceParsing {
    private val CSRF_TOKEN_PATTERN = Regex(
        """ajaxCsrfToken.*?([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})""",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * 从 JSONP / 内嵌脚本混合文本中定位第一个平衡的 JSON 对象。
     * [marker] 非空时从该标记最后一次出现的位置开始搜索。
     */
    fun findJsonObject(text: String, marker: String? = null): JsonObjectExtraction {
        val searchStart = marker?.let { text.indexOf(it).takeIf { index -> index >= 0 } } ?: 0
        val objectStart = text.indexOf('{', searchStart)
        if (objectStart < 0) return JsonObjectExtraction.Missing

        var depth = 0
        var inString = false
        var isEscaped = false
        for (index in objectStart until text.length) {
            val char = text[index]
            when (char) {
                '\\' -> if (inString) isEscaped = !isEscaped
                '"' -> if (!isEscaped) inString = !inString
                '{' -> if (!inString) depth += 1
                '}' -> if (!inString) {
                    depth -= 1
                    if (depth == 0) {
                        return JsonObjectExtraction.Found(text.substring(objectStart, index + 1))
                    }
                }
            }
            if (char != '\\') isEscaped = false
        }
        return JsonObjectExtraction.Incomplete
    }

    fun extractCsrfToken(text: String): String? =
        CSRF_TOKEN_PATTERN.find(text)?.groupValues?.getOrNull(1)

    /**
     * 解析 `getMacList` 返回的行数组。格式非法时返回 null，
     * 缺少 `rows` 或行为空时返回空列表。
     */
    fun parseDeviceRows(response: String): List<DeviceRow>? {
        val rows = try {
            JSONObject(response).optJSONArray("rows") ?: JSONArray()
        } catch (_: JSONException) {
            return null
        }
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONArray(index) ?: continue
                val mac = canonicalMac(row.optString(1)) ?: continue
                val status = row.optString(0).trim()
                add(
                    DeviceRow(
                        mac = mac,
                        status = status,
                        ipAddress = row.optString(4).trim(),
                        isOnline = statusToOnlineState(status)
                    )
                )
            }
        }
    }

    /**
     * 合并账号页的 MAC 列表与设备列表。账号页条目缺少状态信息时使用
     * [unknownStatusLabel]；设备列表数据优先覆盖同名条目。
     */
    fun mergeDevices(
        accountMacs: String,
        rows: List<DeviceRow>,
        unknownStatusLabel: String
    ): List<AccountDevice> {
        val devices = linkedMapOf<String, AccountDevice>()

        accountMacs.split(';')
            .mapNotNull(::canonicalMac)
            .forEach { mac ->
                devices[mac] = AccountDevice(
                    macAddress = formatMac(mac),
                    status = unknownStatusLabel,
                    ipAddress = "",
                    isOnline = null
                )
            }

        rows.forEach { row ->
            devices[row.mac] = AccountDevice(
                macAddress = formatMac(row.mac),
                status = row.status.ifBlank { unknownStatusLabel },
                ipAddress = row.ipAddress,
                isOnline = row.isOnline
            )
        }

        return devices.values.sortedWith(
            compareByDescending<AccountDevice> { it.isOnline == true }
                .thenBy { it.macAddress }
        )
    }

    fun canonicalMac(value: String): String? {
        val mac = value.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
        return mac.takeIf {
            it.length == 12 && it.all { char -> char in '0'..'9' || char in 'A'..'F' }
        }
    }

    fun formatMac(mac: String): String = mac.chunked(2).joinToString(":")

    fun statusToOnlineState(value: String): Boolean? = when {
        value.trim() == "1" -> true
        value.trim() == "0" -> false
        value.contains("离线") -> false
        value.contains("在线") -> true
        else -> null
    }

    fun xorEncode(value: String): String = buildString(value.length * 2) {
        value.forEach { char ->
            append((char.code xor 0x16).toString(16).padStart(2, '0'))
        }
    }
}
