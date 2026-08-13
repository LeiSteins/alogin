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

class SettingsRepository(context: Context) {

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
    val targetWifis: StateFlow<List<String>> = _targetWifis.asStateFlow()

    // 自动识别发生在当前刷新任务内，无需再次刷新；这里只通知手动配置变更。
    private val _targetWifiConfigChanges = MutableSharedFlow<TargetWifiConfigChange>(
        extraBufferCapacity = 1
    )
    val targetWifiConfigChanges: SharedFlow<TargetWifiConfigChange> =
        _targetWifiConfigChanges.asSharedFlow()

    /**
     * Keystore 密钥永久失效（设备安全设置变化）导致凭据不可恢复时为 true，
     * UI 展示提示后调用 [acknowledgeCredentialReset] 清除。
     */
    private val _credentialResetPending = MutableStateFlow(false)
    val credentialResetPending: StateFlow<Boolean> = _credentialResetPending.asStateFlow()

    // 旧版本把凭据明文存在 alogin_settings 中，首次构造时迁移到加密的 alogin_secure。
    init {
        migrateLegacyCredentials()
    }

    private val _username = MutableStateFlow(readCredential(KEY_USERNAME))
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow(readCredential(KEY_PASSWORD))
    val password: StateFlow<String> = _password.asStateFlow()

    private val _appearanceMode = MutableStateFlow(getAppearanceMode())
    val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    fun getTargetWifis(): List<String> {
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

    fun getUsername(): String = readCredential(KEY_USERNAME)

    fun getPassword(): String = readCredential(KEY_PASSWORD)

    fun getAppearanceMode(): AppearanceMode {
        val storedValue = prefs.getString(KEY_APPEARANCE_MODE, null)
        return AppearanceMode.entries.firstOrNull { it.name == storedValue }
            ?: AppearanceMode.SYSTEM
    }

    fun addTargetWifi(ssid: String) {
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
    fun addAutoDetectedTargetWifi(ssid: String): Boolean {
        val trimmed = ssid.trim()
        if (!isBjutDormitoryWifi(trimmed) || trimmed in _targetWifis.value) return false
        persistWifis(_targetWifis.value + trimmed)
        return true
    }

    fun removeTargetWifi(ssid: String) {
        val updatedWifis = _targetWifis.value.filterNot { it == ssid }
        if (updatedWifis == _targetWifis.value) return
        persistWifis(updatedWifis)
        _targetWifiConfigChanges.tryEmit(
            TargetWifiConfigChange(TargetWifiConfigChangeType.REMOVED, ssid)
        )
    }

    fun isTargetWifi(ssid: String): Boolean = ssid in _targetWifis.value

    private fun persistWifis(list: List<String>) {
        val normalized = normalizeWifiList(list)
        prefs.edit().putString(KEY_TARGET_WIFIS, TargetWifiCodec.encode(normalized)).apply()
        _targetWifis.value = normalized
    }

    private fun normalizeWifiList(values: List<String>): List<String> = values
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    fun saveCredentials(username: String, password: String) {
        val encryptedUsername = credentialCipher.encrypt(KEY_USERNAME, username)
        val encryptedPassword = credentialCipher.encrypt(KEY_PASSWORD, password)
        if (encryptedUsername != null && encryptedPassword != null) {
            securePrefs.edit()
                .putString(KEY_USERNAME, encryptedUsername)
                .putString(KEY_PASSWORD, encryptedPassword)
                .apply()
            prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
        } else {
            // 加密不可用时的罕见降级：退回旧版明文存储，保证认证功能仍然可用。
            prefs.edit()
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .apply()
            securePrefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
        }
        _username.value = username
        _password.value = password
    }

    fun saveAppearanceMode(mode: AppearanceMode) {
        prefs.edit().putString(KEY_APPEARANCE_MODE, mode.name).apply()
        _appearanceMode.value = mode
    }

    fun acknowledgeCredentialReset() {
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
