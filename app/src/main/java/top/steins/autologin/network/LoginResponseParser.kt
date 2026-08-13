package top.steins.autologin.network

internal sealed interface LoginParseResult {
    data object Success : LoginParseResult
    data class Failure(val serverMessage: String?) : LoginParseResult
    data object Unknown : LoginParseResult
}

/** 兼容 Dr.COM JSONP 与部分门户直接返回的 JSON/文本响应。 */
internal object LoginResponseParser {
    private val resultPattern = Regex(
        """[\"']?result[\"']?\s*:\s*[\"']?(\d+)[\"']?""",
        RegexOption.IGNORE_CASE
    )
    private val messagePattern = Regex(
        """[\"']?(?:msg|message)[\"']?\s*:\s*[\"']([^\"']+)[\"']""",
        RegexOption.IGNORE_CASE
    )

    fun parse(responseText: String): LoginParseResult {
        val normalized = responseText.replace("\\\"", "\"")
        return when (resultPattern.find(normalized)?.groupValues?.getOrNull(1)) {
            "1" -> LoginParseResult.Success
            "0" -> LoginParseResult.Failure(extractServerMessage(normalized))
            else -> when {
                normalized.contains("认证成功") || normalized.contains("登录成功") ->
                    LoginParseResult.Success

                normalized.contains("认证失败") || normalized.contains("登录失败") -> {
                    LoginParseResult.Failure(extractServerMessage(normalized))
                }

                else -> LoginParseResult.Unknown
            }
        }
    }

    private fun extractServerMessage(response: String): String? =
        messagePattern.find(response)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
}
