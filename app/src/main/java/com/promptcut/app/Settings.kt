package com.promptcut.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts API keys with a key that never leaves the phone's secure hardware (Android Keystore, AES-GCM). */
private object KeyVault {
    private const val ALIAS = "promptcut_api_keys"
    private const val PREFIX = "enc1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Returns null if the value is stored in plain text (older versions) so the caller can re-save it encrypted. */
    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return runCatching {
            val all = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            String(c.doFinal(all.copyOfRange(12, all.size)))
        }.getOrDefault("")
    }
}

/** Keys stay on this phone only, in the app's private storage. They are sent only to the provider they belong to. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("promptcut", Context.MODE_PRIVATE)

    var provider: Provider
        get() = runCatching { Provider.valueOf(prefs.getString("provider", null) ?: "") }.getOrDefault(Provider.GEMINI)
        set(v) = prefs.edit().putString("provider", v.name).apply()

    fun key(p: Provider): String {
        val stored = prefs.getString("key_${p.name}", "") ?: ""
        if (stored.isEmpty()) return ""
        return KeyVault.decrypt(stored) ?: stored.also { setKey(p, it) } // migrate plain keys from older versions
    }

    fun setKey(p: Provider, key: String) =
        prefs.edit().putString("key_${p.name}", runCatching { KeyVault.encrypt(key.trim()) }.getOrDefault(key.trim())).apply()

    fun model(p: Provider): String? = prefs.getString("model_${p.name}", null)
    fun setModel(p: Provider, model: String?) = prefs.edit().putString("model_${p.name}", model).apply()

    /** Set after the fast one-pass render failed once on this phone. */
    var safeRender: Boolean
        get() = prefs.getBoolean("safe_render", false)
        set(v) = prefs.edit().putBoolean("safe_render", v).apply()

    val hasActiveKey: Boolean get() = key(provider).isNotBlank()
}
