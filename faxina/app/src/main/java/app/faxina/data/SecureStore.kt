package app.faxina.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Guarda segredos (senha de app do e-mail) cifrados com uma chave que nunca sai do Android Keystore. */
class SecureStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("faxina_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_SIZE))
            String(cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private companion object {
        const val ALIAS = "faxina_mail_key"
        const val IV_SIZE = 12
    }
}
