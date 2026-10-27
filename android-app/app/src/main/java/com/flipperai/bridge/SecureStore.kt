package com.flipperai.bridge

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
        get() = getSecret(MODEL).let { if (it in MODEL_IDS) it else DEFAULT_MODEL }
        set(value) = putSecret(MODEL, value)

    var effort: String
        get() = getSecret(EFFORT).let { if (it in EFFORTS) it else DEFAULT_EFFORT }
        set(value) = putSecret(EFFORT, value)

    var betaUpdates: Boolean
        get() = getSecret(BETA_UPDATES) == "1"
        set(value) = putSecret(BETA_UPDATES, if (value) "1" else "")

    var serviceWanted: Boolean
        get() = getSecret(SERVICE_WANTED) == "1"
        set(value) = putSecret(SERVICE_WANTED, if (value) "1" else "")

    var lastNotifiedTag: String
        get() = getSecret(LAST_NOTIFIED)
        set(value) = putSecret(LAST_NOTIFIED, value)

    var flipperFapTag: String
        get() = getSecret(FAP_TAG)
        set(value) = putSecret(FAP_TAG, value)

    var requestLog: String
        get() = getSecret(REQUEST_LOG)
        set(value) = putSecret(REQUEST_LOG, value)

    var flipperFapSha: String
        get() = getSecret(FAP_SHA)
        set(value) = putSecret(FAP_SHA, value)

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
        const val DEFAULT_EFFORT = "low"
        val MODEL_IDS = listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5")
        val EFFORTS = listOf("low", "medium", "high")
        private const val EFFORT = "effort"
        private const val BETA_UPDATES = "beta_updates"
        private const val FAP_SHA = "flipper_fap_sha"
        private const val FAP_TAG = "flipper_fap_tag"
        private const val REQUEST_LOG = "request_log"
        private const val SERVICE_WANTED = "service_wanted"
        private const val LAST_NOTIFIED = "last_notified_tag"
        private const val KEY_ALIAS = "flipper_ai_bridge_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val API_KEY = "api_key"
        private const val MODEL = "model"
        private const val EXTRA_PROMPT = "extra_prompt"
        private const val BLE_ADDRESS = "ble_address"
        private const val BLE_NAME = "ble_name"
    }
}
