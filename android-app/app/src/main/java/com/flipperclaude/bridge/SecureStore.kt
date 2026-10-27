package com.flipperclaude.bridge

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("bridge_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = try {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_SIZE))
        String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun putSecret(name: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(name).apply()
        } else {
            prefs.edit().putString(name, encrypt(value)).apply()
        }
    }

    fun getSecret(name: String): String = prefs.getString(name, null)?.let { decrypt(it) } ?: ""

    fun hasSecret(name: String): Boolean = getSecret(name).isNotEmpty()

    var apiKey: String
        get() = getSecret(API_KEY)
        set(value) = putSecret(API_KEY, value)

    var model: String
        get() = getSecret(MODEL).ifEmpty { DEFAULT_MODEL }
        set(value) = putSecret(MODEL, value)

    var extraPrompt: String
        get() = getSecret(EXTRA_PROMPT)
        set(value) = putSecret(EXTRA_PROMPT, value)

    var bleAddress: String
        get() = getSecret(BLE_ADDRESS)
        set(value) = putSecret(BLE_ADDRESS, value)

    var bleName: String
        get() = getSecret(BLE_NAME)
        set(value) = putSecret(BLE_NAME, value)

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        private const val KEY_ALIAS = "flipper_claude_bridge_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val API_KEY = "api_key"
        private const val MODEL = "model"
        private const val EXTRA_PROMPT = "extra_prompt"
        private const val BLE_ADDRESS = "ble_address"
        private const val BLE_NAME = "ble_name"
    }
}
