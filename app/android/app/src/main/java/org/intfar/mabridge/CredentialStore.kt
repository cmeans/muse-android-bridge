package org.intfar.mabridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's ntfy password, encrypted with an AES-256-GCM key that lives in
 * the Android Keystore and can't be exported. Only the IV and ciphertext are
 * kept, in the app's private preferences. The plaintext is never stored and
 * is never shown again after entry.
 */
class CredentialStore(ctx: Context) {
    private val prefs = Config.prefs(ctx)

    init {
        // The spike kept the password in plain text under "password"; never keep it.
        if (prefs.contains(LEGACY_KEY)) prefs.edit().remove(LEGACY_KEY).apply()
    }

    fun isSet(): Boolean = prefs.contains(CT_KEY) && prefs.contains(IV_KEY)

    fun save(password: CharArray) {
        val plain = encode(password)
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(plain)
            prefs.edit()
                .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(CT_KEY, Base64.encodeToString(ct, Base64.NO_WRAP))
                .apply()
        } finally {
            plain.fill(0)
        }
    }

    /** The password, or null if none is saved or it can't be decrypted (e.g. the key was lost). */
    fun load(): CharArray? {
        val iv = prefs.getString(IV_KEY, null) ?: return null
        val ct = prefs.getString(CT_KEY, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val plain = cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP))
            try {
                Charsets.UTF_8.decode(ByteBuffer.wrap(plain)).let { buf -> CharArray(buf.remaining()).also { buf.get(it) } }
            } finally {
                plain.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().remove(IV_KEY).remove(CT_KEY).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encode(chars: CharArray): ByteArray {
        val buf = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        return ByteArray(buf.remaining()).also { buf.get(it) }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "mab_cred_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_KEY = "cred_iv"
        private const val CT_KEY = "cred_ct"
        private const val LEGACY_KEY = "password"
    }
}
