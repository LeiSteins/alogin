package top.steins.autologin.network

import java.util.Locale

internal sealed interface DeviceLogoutResponse {
    data object Success : DeviceLogoutResponse
    data class Failure(val message: String?) : DeviceLogoutResponse
    data object Unknown : DeviceLogoutResponse
}

internal object DeviceLogoutResponseParser {

    fun parse(response: String): DeviceLogoutResponse {
        val normalized = response.replace("\\\"", "\"")
        val result = RESULT_PATTERN.find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)

        when (result) {
            "1", "true" -> return DeviceLogoutResponse.Success
            "0", "false" -> return DeviceLogoutResponse.Failure(null)
        }

        when {
            normalized.contains("操作成功") || normalized.contains("解绑成功") -> {
                return DeviceLogoutResponse.Success
            }

            normalized.contains("操作失败") || normalized.contains("解绑失败") -> {
                return DeviceLogoutResponse.Failure(null)
            }
        }

        val pageMessage = PAGE_MESSAGE_PATTERN.find(normalized)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            .orEmpty()
        return when {
            pageMessage == "成功" -> DeviceLogoutResponse.Success
            pageMessage.isNotEmpty() -> DeviceLogoutResponse.Failure(pageMessage)
            else -> DeviceLogoutResponse.Unknown
        }
    }

    private val RESULT_PATTERN = Regex(
        """["']?result["']?\s*:\s*["']?(1|0|true|false)["']?""",
        RegexOption.IGNORE_CASE
    )

    // jfself 实际返回完整“我的设备”HTML，并用该立即执行函数展示解绑结果：
    // (function (msg) { ... })('成功');
    private val PAGE_MESSAGE_PATTERN = Regex(
        """\(function\s*\(\s*msg\s*\).*?\}\)\s*\(\s*(['"])(.*?)\1\s*\)\s*;""",
        RegexOption.DOT_MATCHES_ALL
    )
}
