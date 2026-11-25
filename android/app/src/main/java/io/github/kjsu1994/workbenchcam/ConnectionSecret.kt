package io.github.kjsu1994.workbenchcam

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ConnectionSecret(context: Context) {
    private val prefs = context.getSharedPreferences("camera_connection", Context.MODE_PRIVATE)

    fun read(): String? {
        val saved = prefs.getString("encrypted_password", null) ?: return null
        return runCatching {
            val (iv, ciphertext) = saved.split('.', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)))
            cipher.doFinal(Base64.decode(ciphertext, Base64.DEFAULT)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun save(password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(password.toByteArray()), Base64.NO_WRAP)
        prefs.edit().putString("encrypted_password", "$iv.$ciphertext").apply()
    }

    fun forget() {
        prefs.edit().remove("encrypted_password").apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey("workbenchcam.ap", null)
        if (existing is SecretKey) return existing
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val spec = KeyGenParameterSpec.Builder("workbenchcam.ap", purposes)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(spec)
        }.generateKey()
    }
}
