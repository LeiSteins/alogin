package top.steins.autologin.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("alogin_settings", Context.MODE_PRIVATE)

    private val _targetWifis = MutableStateFlow(getTargetWifis())
    val targetWifis: Flow<List<String>> = _targetWifis.asStateFlow()

    private val _username = MutableStateFlow(getUsername())
    val username: Flow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow(getPassword())
    val password: Flow<String> = _password.asStateFlow()

    fun getTargetWifis(): List<String> {
        val joined = prefs.getString(KEY_TARGET_WIFIS, null)
        return if (joined.isNullOrEmpty()) {
            listOf(DEFAULT_WIFI)
        } else {
            joined.split(",").filter { it.isNotBlank() }
        }
    }

    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""

    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""

    fun addTargetWifi(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isEmpty()) return
        val current = getTargetWifis().toMutableList()
        if (current.contains(trimmed)) return
        current.add(trimmed)
        persistWifis(current)
    }

    fun removeTargetWifi(ssid: String) {
        val current = getTargetWifis().toMutableList()
        current.remove(ssid)
        persistWifis(current)
    }

    fun isTargetWifi(ssid: String): Boolean = getTargetWifis().contains(ssid)

    private fun persistWifis(list: List<String>) {
        val joined = list.joinToString(",")
        prefs.edit().putString(KEY_TARGET_WIFIS, joined).apply()
        _targetWifis.value = list
    }

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
