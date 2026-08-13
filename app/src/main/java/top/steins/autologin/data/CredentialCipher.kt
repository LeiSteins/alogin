package top.steins.autologin.data

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.nio.charset.StandardCharsets
import java.security.KeyStore

internal sealed interface CredentialDecryptOutcome {
    data class Success(val value: String) : CredentialDecryptOutcome

    /** 值没有加密前缀，属于旧版明文或未初始化。 */
    data object NotEncrypted : CredentialDecryptOutcome

    /** 密文损坏或无法解封，但密钥本身仍然可用。 */
    data object Corrupt : CredentialDecryptOutcome

    /** Keystore 密钥已永久失效（例如设备安全设置变化），需要清理后重建。 */
    data object KeyInvalidated : CredentialDecryptOutcome
}

/**
 * 使用 Tink AEAD（AES-256-GCM）加解密凭据。
 *
 * 密钥集由 [AndroidKeysetManager] 托管：密钥集本身用 Keystore 主密钥加密后
 * 存入 SharedPreferences，密钥格式与轮换由 Tink 处理。密文带 `tink-v1:` 前缀，
 * 便于与旧版明文区分并做平滑迁移。加密结果绑定字段名作为关联数据（AAD），
 * 防止把用户名字段与密码字段的密文互换。
 */
internal class CredentialCipher(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private var aead: Aead? = null

    /**
     * 加密失败（Keystore/Tink 异常）时返回 null，调用方应降级处理。
     */
    fun encrypt(field: String, plaintext: String): String? {
        val aead = obtainUsableAead() ?: return null
        return runCatching {
            ENCRYPTED_PREFIX + Base64.encodeToString(
                aead.encrypt(plaintext.toByteArray(StandardCharsets.UTF_8), aad(field)),
                Base64.NO_WRAP
            )
        }.getOrNull()
    }

    fun decrypt(field: String, serialized: String): CredentialDecryptOutcome {
        val payload = serialized.removePrefix(ENCRYPTED_PREFIX)
        if (payload.length == serialized.length) return CredentialDecryptOutcome.NotEncrypted

        val aead = when (val result = obtainAead()) {
            is AeadResult.Ready -> result.aead
            AeadResult.KeyInvalidated -> return CredentialDecryptOutcome.KeyInvalidated
            AeadResult.Unavailable -> return CredentialDecryptOutcome.Corrupt
        }
        return runCatching {
            val plaintext = aead.decrypt(Base64.decode(payload, Base64.NO_WRAP), aad(field))
            CredentialDecryptOutcome.Success(String(plaintext, StandardCharsets.UTF_8))
        }.getOrElse { error ->
            if (error.isPermanentKeyInvalidation()) {
                CredentialDecryptOutcome.KeyInvalidated
            } else {
                CredentialDecryptOutcome.Corrupt
            }
        }
    }

    /**
     * 密钥失效后清理：删除 Keystore 主密钥与加密后的 keyset，下次使用时重新生成。
     * 旧密文没有密钥可解，应由调用方连同密文一并删除。
     */
    fun resetAfterInvalidation() {
        synchronized(lock) {
            aead = null
            runCatching { deleteMasterKeyEntry() }
            runCatching { clearKeysetPrefs() }
        }
    }

    private fun obtainUsableAead(): Aead? {
        when (val result = obtainAead()) {
            is AeadResult.Ready -> return result.aead
            AeadResult.KeyInvalidated -> {
                resetAfterInvalidation()
                return (obtainAead() as? AeadResult.Ready)?.aead
            }

            AeadResult.Unavailable -> return null
        }
    }

    private fun obtainAead(): AeadResult {
        synchronized(lock) {
            aead?.let { return AeadResult.Ready(it) }
            return try {
                val created = createAead()
                aead = created
                AeadResult.Ready(created)
            } catch (error: Throwable) {
                if (error.isPermanentKeyInvalidation()) {
                    AeadResult.KeyInvalidated
                } else {
                    AeadResult.Unavailable
                }
            }
        }
    }

    private fun createAead(): Aead {
        val keysetHandle: KeysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEY_SET_NAME, KEY_SET_PREFS_FILE)
            .withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        return keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun deleteMasterKeyEntry() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            keyStore.deleteEntry(MASTER_KEY_ALIAS)
        }
    }

    private fun clearKeysetPrefs() {
        appContext.getSharedPreferences(KEY_SET_PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun aad(field: String): ByteArray =
        (AAD_PREFIX + field).toByteArray(StandardCharsets.UTF_8)

    private sealed interface AeadResult {
        data class Ready(val aead: Aead) : AeadResult
        data object KeyInvalidated : AeadResult
        data object Unavailable : AeadResult
    }

    private companion object {
        private const val ENCRYPTED_PREFIX = "tink-v1:"
        private const val MASTER_KEY_URI = "android-keystore://alogin_master_key"
        private const val MASTER_KEY_ALIAS = "alogin_master_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SET_NAME = "alogin_keyset"
        private const val KEY_SET_PREFS_FILE = "alogin_tink_keyset"
        private const val AAD_PREFIX = "alogin.credentials."
    }
}

/**
 * Tink 会把 Keystore 的永久失效异常作为 cause 包进 GeneralSecurityException 抛出，
 * 这里沿 cause 链查找是否属于该场景。
 */
private fun Throwable.isPermanentKeyInvalidation(): Boolean =
    generateSequence(this) { it.cause }
        .any { it is KeyPermanentlyInvalidatedException }
