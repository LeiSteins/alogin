package top.steins.autologin.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("alogin_settings", Context.MODE_PRIVATE)

    private val _targetWifi = MutableStateFlow(getTargetWifi())
    val targetWifi: Flow<String> = _targetWifi.asStateFlow()

    private val _username = MutableStateFlow(getUsername())
    val username: Flow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow(getPassword())
    val password: Flow<String> = _password.asStateFlow()

    fun getTargetWifi(): String = prefs.getString(KEY_TARGET_WIFI, DEFAULT_WIFI) ?: DEFAULT_WIFI

    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""

    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""

    fun saveTargetWifi(value: String) {
        prefs.edit().putString(KEY_TARGET_WIFI, value).apply()
        _targetWifi.value = value
    }

    fun saveUsername(value: String) {
        prefs.edit().putString(KEY_USERNAME, value).apply()
        _username.value = value
    }

    fun savePassword(value: String) {
        prefs.edit().putString(KEY_PASSWORD, value).apply()
        _password.value = value
    }

    fun saveAll(targetWifi: String, username: String, password: String) {
        prefs.edit()
            .putString(KEY_TARGET_WIFI, targetWifi)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
        _targetWifi.value = targetWifi
        _username.value = username
        _password.value = password
    }

    companion object {
        private const val KEY_TARGET_WIFI = "target_wifi"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val DEFAULT_WIFI = "bjut_wifi"
    }
}
