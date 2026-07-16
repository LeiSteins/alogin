package top.steins.autologin.network

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

    fun parse(responseText: String): LoginResult {
        val normalized = responseText.replace("\\\"", "\"")
        return when (resultPattern.find(normalized)?.groupValues?.getOrNull(1)) {
            "1" -> LoginResult.Success
            "0" -> LoginResult.Failure(extractFailureMessage(normalized))
            else -> when {
                normalized.contains("认证成功") || normalized.contains("登录成功") -> LoginResult.Success
                normalized.contains("认证失败") || normalized.contains("登录失败") -> {
                    LoginResult.Failure(extractFailureMessage(normalized))
                }

                else -> LoginResult.Failure("服务器返回未知响应")
            }
        }
    }

    private fun extractFailureMessage(response: String): String {
        val serverMessage = messagePattern.find(response)?.groupValues?.getOrNull(1)?.trim()
        return serverMessage?.takeIf(String::isNotBlank)?.let { "登录失败：$it" }
            ?: "登录失败，请检查用户名和密码"
    }
}
