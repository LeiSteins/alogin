package top.steins.autologin.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TargetWifiConfigChangeType {
    ADDED,
    REMOVED
}

data class TargetWifiConfigChange(
    val type: TargetWifiConfigChangeType,
    val ssid: String
)

enum class AppearanceMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * 凭据保存结果。Keystore 和本地加密路径都不可用时不落盘。
 */
enum class CredentialSaveResult {
    SAVED,
    ENCRYPTION_UNAVAILABLE
}

/**
 * 设置数据的读取与写入入口，屏蔽 SharedPreferences / Keystore 细节，
 * 使 ViewModel 与界面层不直接依赖存储实现，也便于单元测试注入替身。
 */
interface SettingsGateway {
    val targetWifis: StateFlow<List<String>>
    val targetWifiConfigChanges: SharedFlow<TargetWifiConfigChange>
    val username: StateFlow<String>
    val password: StateFlow<String>
    val appearanceMode: StateFlow<AppearanceMode>
    val credentialResetPending: StateFlow<Boolean>

    fun addAutoDetectedTargetWifi(ssid: String): Boolean

    fun addTargetWifi(ssid: String)

    fun removeTargetWifi(ssid: String)

    fun saveCredentials(username: String, password: String): CredentialSaveResult

    fun saveAppearanceMode(mode: AppearanceMode)

    fun acknowledgeCredentialReset()
}

class SettingsRepository(context: Context) : SettingsGateway {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(
        "alogin_settings",
        Context.MODE_PRIVATE
    )
    private val securePrefs: SharedPreferences = appContext.getSharedPreferences(
        "alogin_secure",
        Context.MODE_PRIVATE
    )
    private val credentialCipher = CredentialCipher(appContext)

    private val _targetWifis = MutableStateFlow(getTargetWifis())
    override val targetWifis: StateFlow<List<String>> = _targetWifis.asStateFlow()

    // 自动识别发生在当前刷新任务内，无需再次刷新；这里只通知手动配置变更。
    private val _targetWifiConfigChanges = MutableSharedFlow<TargetWifiConfigChange>(
        extraBufferCapacity = 1
    )
    override val targetWifiConfigChanges: SharedFlow<TargetWifiConfigChange> =
        _targetWifiConfigChanges.asSharedFlow()

    /**
     * Keystore 密钥永久失效（设备安全设置变化）导致凭据不可恢复时为 true，
     * UI 展示提示后调用 [acknowledgeCredentialReset] 清除。
     */
    private val _credentialResetPending = MutableStateFlow(false)
    override val credentialResetPending: StateFlow<Boolean> = _credentialResetPending.asStateFlow()

    // 旧版本把凭据明文存在 alogin_settings 中，首次构造时迁移到加密的 alogin_secure。
    init {
        migrateLegacyCredentials()
    }

    private val _username = MutableStateFlow(readCredential(KEY_USERNAME))
    override val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow(readCredential(KEY_PASSWORD))
    override val password: StateFlow<String> = _password.asStateFlow()

    private val _appearanceMode = MutableStateFlow(getAppearanceMode())
    override val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    private fun getTargetWifis(): List<String> {
        val serialized = prefs.getString(KEY_TARGET_WIFIS, null) ?: return listOf(DEFAULT_WIFI)
        TargetWifiCodec.decode(serialized)?.let(::normalizeWifiList)?.let { return it }

        // 兼容旧版逗号分隔格式；旧版空字符串仍按默认 WiFi 处理。
        val legacyValues = serialized.split(',').filter(String::isNotBlank)
        return if (legacyValues.isEmpty()) {
            listOf(DEFAULT_WIFI)
        } else {
            normalizeWifiList(legacyValues)
        }
    }

    fun getAppearanceMode(): AppearanceMode {
        val storedValue = prefs.getString(KEY_APPEARANCE_MODE, null)
        return AppearanceMode.entries.firstOrNull { it.name == storedValue }
            ?: AppearanceMode.SYSTEM
    }

    override fun addTargetWifi(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isEmpty() || trimmed in _targetWifis.value) return
        persistWifis(_targetWifis.value + trimmed)
        _targetWifiConfigChanges.tryEmit(
            TargetWifiConfigChange(TargetWifiConfigChangeType.ADDED, trimmed)
        )
    }

    /**
     * 将识别到的北工大宿舍 Wi-Fi 自动加入目标列表。调用方已在当前刷新任务内，
     * 因此不发送配置变更事件，以避免重复刷新。
     */
    override fun addAutoDetectedTargetWifi(ssid: String): Boolean {
        val trimmed = ssid.trim()
        if (!isBjutDormitoryWifi(trimmed) || trimmed in _targetWifis.value) return false
        persistWifis(_targetWifis.value + trimmed)
        return true
    }

    override fun removeTargetWifi(ssid: String) {
        val updatedWifis = _targetWifis.value.filterNot { it == ssid }
        if (updatedWifis == _targetWifis.value) return
        persistWifis(updatedWifis)
        _targetWifiConfigChanges.tryEmit(
            TargetWifiConfigChange(TargetWifiConfigChangeType.REMOVED, ssid)
        )
    }

    private fun persistWifis(list: List<String>) {
        val normalized = normalizeWifiList(list)
        prefs.edit().putString(KEY_TARGET_WIFIS, TargetWifiCodec.encode(normalized)).apply()
        _targetWifis.value = normalized
    }

    private fun normalizeWifiList(values: List<String>): List<String> = values
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    override fun saveCredentials(username: String, password: String): CredentialSaveResult {
        val encryptedUsername = credentialCipher.encrypt(KEY_USERNAME, username)
        val encryptedPassword = credentialCipher.encrypt(KEY_PASSWORD, password)
        if (encryptedUsername == null || encryptedPassword == null) {
            // 两条加密路径都不可用时拒绝保存，绝不把凭据降级为明文落盘。
            return CredentialSaveResult.ENCRYPTION_UNAVAILABLE
        }

        securePrefs.edit()
            .putString(KEY_USERNAME, encryptedUsername)
            .putString(KEY_PASSWORD, encryptedPassword)
            .apply()
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
        _username.value = username
        _password.value = password
        return CredentialSaveResult.SAVED
    }

    override fun saveAppearanceMode(mode: AppearanceMode) {
        prefs.edit().putString(KEY_APPEARANCE_MODE, mode.name).apply()
        _appearanceMode.value = mode
    }

    override fun acknowledgeCredentialReset() {
        _credentialResetPending.value = false
    }

    private fun readCredential(key: String): String {
        val stored = securePrefs.getString(key, null)
            ?: return prefs.getString(key, "") ?: ""
        return when (val outcome = credentialCipher.decrypt(key, stored)) {
            is CredentialDecryptOutcome.Success -> outcome.value
            CredentialDecryptOutcome.KeyInvalidated -> {
                handleCredentialKeyInvalidation()
                ""
            }

            CredentialDecryptOutcome.Corrupt,
            CredentialDecryptOutcome.NotEncrypted -> ""
        }
    }

    private fun migrateLegacyCredentials() {
        val removedLegacyKeys = mutableListOf<String>()
        val secureEditor = securePrefs.edit()
        for (key in listOf(KEY_USERNAME, KEY_PASSWORD)) {
            if (!securePrefs.contains(key)) {
                prefs.getString(key, null)?.let { legacy ->
                    credentialCipher.encrypt(key, legacy)?.let { encrypted ->
                        secureEditor.putString(key, encrypted)
                        removedLegacyKeys += key
                    }
                }
            }
        }
        if (removedLegacyKeys.isNotEmpty()) {
            secureEditor.apply()
            val legacyEditor = prefs.edit()
            removedLegacyKeys.forEach { legacyEditor.remove(it) }
            legacyEditor.apply()
        }
    }

    private fun handleCredentialKeyInvalidation() {
        // 密钥已经没了，密文无法再解开：连同密钥集、旧密文和可能的明文残留一起清除，
        // 下次保存时生成全新密钥。界面收到事件后提示用户重新填写。
        credentialCipher.resetAfterInvalidation()
        securePrefs.edit().clear().apply()
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
        _credentialResetPending.value = true
    }

    companion object {
        private const val KEY_TARGET_WIFIS = "target_wifis"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_APPEARANCE_MODE = "appearance_mode"
        private const val DEFAULT_WIFI = "bjut_wifi"
    }
}
