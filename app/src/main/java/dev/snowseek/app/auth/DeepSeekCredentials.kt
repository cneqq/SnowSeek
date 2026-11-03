package dev.snowseek.app.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only the web token is exported on explicit user action; Android Keystore protects it at rest. */
class DeepSeekCredentials private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("deepseek_session", Context.MODE_PRIVATE)
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private var cached: String? = load()
    @Synchronized fun token(): String? = cached
    @Synchronized fun mayAttachWebCookies(token: String): Boolean = cached == token && preferences.getBoolean("web_cookies", true)
    @Synchronized fun save(token: String, webCookies: Boolean = true) {
        require(token.isNotBlank() && token.length < 16_384)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("encrypted_token", Base64.encodeToString(value, Base64.NO_WRAP))
            .putBoolean("web_cookies", webCookies).commit()) { "Session could not be saved" }
        cached = token
        revision.value++
    }
    @Synchronized fun clear() {
        check(preferences.edit().remove("encrypted_token").remove("web_cookies").commit()) { "Session could not be cleared" }
        cached = null
        revision.value++
    }
    private fun load(): String? = try {
        preferences.getString("encrypted_token", null)?.let { encoded ->
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    } catch (_: Exception) { null } // Lost/invalidated keys require an explicit new website login.
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object {
        private const val ALIAS = "snowseek.deepseek.web-session.v1"
        @Volatile private var instance: DeepSeekCredentials? = null
        fun get(context: Context): DeepSeekCredentials = instance ?: synchronized(this) {
            instance ?: DeepSeekCredentials(context).also { instance = it }
        }
    }
}
