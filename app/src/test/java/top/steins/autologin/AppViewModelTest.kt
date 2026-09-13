package top.steins.autologin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import top.steins.autologin.data.AppearanceMode
import top.steins.autologin.data.CredentialSaveResult
import top.steins.autologin.data.SettingsGateway
import top.steins.autologin.data.TargetWifiConfigChange
import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.AccountOverviewResult
import top.steins.autologin.network.CurrentNetworkInfo
import top.steins.autologin.network.DefaultNetworkChange
import top.steins.autologin.network.DeviceLogoutResult
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.LoginStatus
import top.steins.autologin.network.NetworkEnvironment
import top.steins.autologin.network.SelfServiceGateway
import top.steins.autologin.network.update.UpdateDownloadResult
import top.steins.autologin.network.update.UpdateGateway
import top.steins.autologin.network.update.UpdateInfo
import top.steins.autologin.network.update.UpdateState

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = FakeSettingsGateway()
    private val selfService = FakeSelfServiceGateway()
    private val updates = FakeUpdateGateway()
    private val network = FakeNetworkEnvironment()

    private fun createViewModel(
        hasLocationPermission: () -> Boolean = { true }
    ): AppViewModel = AppViewModel(
        strings = AppStrings { resId, _ -> "msg:$resId" },
        settings = settings,
        selfService = selfService,
        updates = updates,
        network = network,
        hasLocationPermission = hasLocationPermission
    )

    @Test
    fun refreshStatus_publishesNetworkAndAccountOverviewForLoggedInTargetWifi() {
        network.info = CurrentNetworkInfo(
            wifiName = "bjut_wifi",
            ipAddress = "10.1.2.3",
            isWifi = true,
            isCellular = false,
            isConnected = true
        )
        network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
        val overview = AccountOverview("2021001", "100", "900", "20", emptyList())
        selfService.overviewResult = AccountOverviewResult.Success(overview)
        val viewModel = createViewModel()

        viewModel.refreshStatus()

        val state = viewModel.uiState.value
        assertEquals("bjut_wifi", state.wifiName)
        assertEquals("10.1.2.3", state.ipAddress)
        assertTrue(state.isOnline)
        assertEquals(overview, state.accountOverview)
    }

    @Test
    fun defaultNetworkChange_retriesRetryableAccountFailure() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
            val overview = AccountOverview("2021001", "100", "900", "20", emptyList())
            selfService.overviewResults.add(
                AccountOverviewResult.Failure("timeout", isRetryable = true)
            )
            selfService.overviewResults.add(AccountOverviewResult.Success(overview))
            val viewModel = createViewModel()

            network.emitDefaultNetworkChange(DefaultNetworkChange.IP_ADDRESS_CHANGED)
            advanceUntilIdle()

            assertEquals(2, selfService.overviewLoadCount)
            assertEquals(overview, viewModel.uiState.value.accountOverview)
            assertEquals("", viewModel.uiState.value.accountInfoError)
        }

    @Test
    fun defaultNetworkChange_doesNotRetryNonRetryableAccountFailure() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
            selfService.overviewResult = AccountOverviewResult.Failure(
                "invalid response",
                isRetryable = false
            )
            val viewModel = createViewModel()

            network.emitDefaultNetworkChange(DefaultNetworkChange.IP_ADDRESS_CHANGED)
            advanceUntilIdle()

            assertEquals(1, selfService.overviewLoadCount)
            assertEquals("invalid response", viewModel.uiState.value.accountInfoError)
        }

    @Test
    fun defaultNetworkChange_showsRetryableFailureAfterAttemptsAreExhausted() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
            selfService.overviewResult = AccountOverviewResult.Failure(
                "timeout",
                isRetryable = true
            )
            val viewModel = createViewModel()

            network.emitDefaultNetworkChange(DefaultNetworkChange.IP_ADDRESS_CHANGED)
            advanceUntilIdle()

            assertEquals(AppViewModel.NETWORK_REFRESH_ATTEMPTS, selfService.overviewLoadCount)
            assertFalse(viewModel.uiState.value.isAccountInfoLoading)
            assertEquals("timeout", viewModel.uiState.value.accountInfoError)
        }

    @Test
    fun refreshStatus_clearsSessionAndAccountStateForNonTargetWifi() {
        network.info = CurrentNetworkInfo(
            wifiName = "other_wifi",
            ipAddress = "10.1.2.3",
            isWifi = true,
            isCellular = false,
            isConnected = true
        )
        val viewModel = createViewModel()

        viewModel.refreshStatus()

        val state = viewModel.uiState.value
        assertFalse(state.isOnline)
        assertNull(state.accountOverview)
        assertTrue(selfService.cleared)
    }

    @Test
    fun login_returnsFailureWhenCredentialsMissing() = runTest {
        val viewModel = createViewModel()

        val result = viewModel.login()

        assertEquals(
            LoginResult.Failure("msg:${R.string.login_no_credentials}"),
            result
        )
    }

    @Test
    fun login_returnsFailureOnNonTargetWifi() = runTest {
        settings.setCredentials("2021001", "secret")
        network.info = CurrentNetworkInfo(
            wifiName = "other_wifi",
            ipAddress = "10.1.2.3",
            isWifi = true,
            isCellular = false,
            isConnected = true
        )
        val viewModel = createViewModel()

        val result = viewModel.login()

        assertEquals(
            LoginResult.Failure("msg:${R.string.login_wrong_network}"),
            result
        )
    }

    @Test
    fun login_delegatesToNetworkOnTargetWifi() = runTest {
        settings.setCredentials("2021001", "secret")
        network.info = CurrentNetworkInfo(
            wifiName = "bjut_wifi",
            ipAddress = "10.1.2.3",
            isWifi = true,
            isCellular = false,
            isConnected = true
        )
        network.loginResult = LoginResult.Success
        val viewModel = createViewModel()

        val result = viewModel.login()

        assertEquals(LoginResult.Success, result)
        assertEquals(
            Triple("2021001", "secret", "10.1.2.3"),
            network.performedLogin
        )
    }

    @Test
    fun checkForUpdates_publishesAvailableStateForNewerVersion() {
        val update = UpdateInfo(
            version = "0.2.0",
            fileName = "alogin-v0.2.0.apk",
            downloadUrl = "https://aloginupdate.steins.top/alogin-v0.2.0.apk"
        )
        updates.fetchResult = Result.success(update)
        val viewModel = createViewModel()

        viewModel.checkForUpdates(manual = true)

        assertEquals(UpdateState.Available(update), viewModel.updateState.value)
    }

    @Test
    fun saveCredentials_forwardsResultAndDoesNotStoreOnFailure() {
        settings.saveResult = CredentialSaveResult.ENCRYPTION_UNAVAILABLE
        val viewModel = createViewModel()

        assertEquals(
            CredentialSaveResult.ENCRYPTION_UNAVAILABLE,
            viewModel.saveCredentials("2021001", "secret")
        )
        assertNull(settings.saved)

        settings.saveResult = CredentialSaveResult.SAVED
        assertEquals(
            CredentialSaveResult.SAVED,
            viewModel.saveCredentials("2021001", "secret")
        )
        assertEquals("2021001" to "secret", settings.saved)
    }

    @Test
    fun onLocationPermissionChanged_updatesStateAndTriggersRefresh() {
        var granted = true
        val viewModel = createViewModel(hasLocationPermission = { granted })

        granted = false
        viewModel.onLocationPermissionChanged()

        assertFalse(viewModel.uiState.value.hasLocationPermission)
    }
}

private class FakeSettingsGateway : SettingsGateway {
    private val _targetWifis = MutableStateFlow(listOf("bjut_wifi"))
    override val targetWifis: StateFlow<List<String>> = _targetWifis.asStateFlow()

    private val _targetWifiConfigChanges = MutableSharedFlow<TargetWifiConfigChange>(
        extraBufferCapacity = 1
    )
    override val targetWifiConfigChanges: SharedFlow<TargetWifiConfigChange> =
        _targetWifiConfigChanges.asSharedFlow()

    private val _username = MutableStateFlow("")
    override val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    override val password: StateFlow<String> = _password.asStateFlow()

    private val _appearanceMode = MutableStateFlow(AppearanceMode.SYSTEM)
    override val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    private val _credentialResetPending = MutableStateFlow(false)
    override val credentialResetPending: StateFlow<Boolean> = _credentialResetPending.asStateFlow()

    var saveResult = CredentialSaveResult.SAVED
    var saved: Pair<String, String>? = null

    fun setCredentials(username: String, password: String) {
        _username.value = username
        _password.value = password
    }

    override fun addAutoDetectedTargetWifi(ssid: String): Boolean {
        if (!ssid.startsWith("bjut-sushe-")) return false
        if (ssid in _targetWifis.value) return false
        _targetWifis.value += ssid
        return true
    }

    override fun addTargetWifi(ssid: String) {
        if (ssid !in _targetWifis.value) _targetWifis.value += ssid
    }

    override fun removeTargetWifi(ssid: String) {
        _targetWifis.value = _targetWifis.value.filterNot { it == ssid }
    }

    override fun saveCredentials(username: String, password: String): CredentialSaveResult {
        if (saveResult == CredentialSaveResult.SAVED) {
            saved = username to password
        }
        return saveResult
    }

    override fun saveAppearanceMode(mode: AppearanceMode) {
        _appearanceMode.value = mode
    }

    override fun acknowledgeCredentialReset() {
        _credentialResetPending.value = false
    }
}

private class FakeSelfServiceGateway : SelfServiceGateway {
    var overviewResult: AccountOverviewResult = AccountOverviewResult.Failure("fail")
    val overviewResults = ArrayDeque<AccountOverviewResult>()
    var overviewLoadCount = 0
    var logoutResult: DeviceLogoutResult = DeviceLogoutResult.Failure("fail")
    var cleared = false

    override suspend fun loadAccountOverview(
        lgnUsername: String,
        wlanUserIp: String
    ): AccountOverviewResult {
        overviewLoadCount += 1
        return overviewResults.removeFirstOrNull() ?: overviewResult
    }

    override suspend fun logoutDevice(macAddress: String): DeviceLogoutResult = logoutResult

    override suspend fun clearSession() {
        cleared = true
    }
}

private class FakeNetworkEnvironment : NetworkEnvironment {
    private val defaultNetworkChanges = MutableSharedFlow<DefaultNetworkChange>(
        extraBufferCapacity = 1
    )
    var info = CurrentNetworkInfo(
        wifiName = "",
        ipAddress = "",
        isWifi = false,
        isCellular = false,
        isConnected = false
    )
    var loginStatus = LoginStatus(isLoggedIn = false)
    var loginResult: LoginResult = LoginResult.Failure("")
    var performedLogin: Triple<String, String, String>? = null

    override fun observeDefaultNetworkChanges(): Flow<DefaultNetworkChange> = defaultNetworkChanges

    fun emitDefaultNetworkChange(change: DefaultNetworkChange) {
        check(defaultNetworkChanges.tryEmit(change))
    }

    override fun fetchCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo = info

    override suspend fun fetchLoginStatus(): LoginStatus = loginStatus

    override suspend fun performLogin(
        username: String,
        password: String,
        wlanUserIp: String
    ): LoginResult {
        performedLogin = Triple(username, password, wlanUserIp)
        return loginResult
    }
}

private class FakeUpdateGateway : UpdateGateway {
    var fetchResult: Result<UpdateInfo> = Result.failure(IllegalStateException("no update"))
    var downloadResult = UpdateDownloadResult.Failed

    override suspend fun fetchLatestUpdate(currentVersion: String): UpdateInfo =
        fetchResult.getOrThrow()

    override fun downloadUpdate(update: UpdateInfo): UpdateDownloadResult = downloadResult
}
