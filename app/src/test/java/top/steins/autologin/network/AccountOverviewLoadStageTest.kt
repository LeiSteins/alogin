package top.steins.autologin.network

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountOverviewLoadStageTest {

    @Test
    fun unexpectedErrorMessages_identifyEveryAccountOverviewLoadStage() {
        val messages = SelfServiceRepository.AccountOverviewLoadStage.entries.associateWith {
            it.unexpectedErrorMessage
        }

        assertEquals(
            "请求自助服务登录凭证时发生异常，请稍后重试",
            messages[SelfServiceRepository.AccountOverviewLoadStage.REQUEST_SSO_CREDENTIALS]
        )
        assertEquals(
            "解析自助服务登录凭证时发生异常，请稍后重试",
            messages[SelfServiceRepository.AccountOverviewLoadStage.PARSE_SSO_CREDENTIALS]
        )
        assertEquals(
            "建立自助服务会话时发生异常，请稍后重试",
            messages[SelfServiceRepository.AccountOverviewLoadStage.OPEN_SELF_SERVICE_SESSION]
        )
        assertEquals(
            "请求账号信息页面时发生异常，请稍后重试",
            messages[SelfServiceRepository.AccountOverviewLoadStage.REQUEST_ACCOUNT_PAGE]
        )
        assertEquals(
            "解析账号基本信息时发生异常，请稍后重试",
            messages[SelfServiceRepository.AccountOverviewLoadStage.PARSE_ACCOUNT_PAGE]
        )
        assertEquals(
            "请求设备列表时发生异常，请稍后重试",
            messages[SelfServiceRepository.AccountOverviewLoadStage.REQUEST_DEVICE_LIST]
        )
        assertEquals(
            "解析设备列表时发生异常，请稍后重试",
            messages[SelfServiceRepository.AccountOverviewLoadStage.PARSE_DEVICE_LIST]
        )
        assertEquals(
            "整理账号信息时发生异常，请稍后重试",
            messages[SelfServiceRepository.AccountOverviewLoadStage.BUILD_ACCOUNT_OVERVIEW]
        )
    }
}
