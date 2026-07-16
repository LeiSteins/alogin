package top.steins.autologin.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "alogin_settings",
        Context.MODE_PRIVATE
    )

    private val _targetWifis = MutableStateFlow(getTargetWifis())
    val targetWifis: StateFlow<List<String>> = _targetWifis.asStateFlow()

    private val _username = MutableStateFlow(getUsername())
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow(getPassword())
    val password: StateFlow<String> = _password.asStateFlow()

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

    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""

    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""

    fun addTargetWifi(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isEmpty() || trimmed in _targetWifis.value) return
        persistWifis(_targetWifis.value + trimmed)
    }

    fun removeTargetWifi(ssid: String) {
        persistWifis(_targetWifis.value.filterNot { it == ssid })
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

    fun saveUsername(value: String) {
        prefs.edit().putString(KEY_USERNAME, value).apply()
        _username.value = value
    }

    fun savePassword(value: String) {
        prefs.edit().putString(KEY_PASSWORD, value).apply()
        _password.value = value
    }

    fun saveCredentials(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
        _username.value = username
        _password.value = password
    }

    companion object {
        private const val KEY_TARGET_WIFIS = "target_wifis"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val DEFAULT_WIFI = "bjut_wifi"
    }
}
