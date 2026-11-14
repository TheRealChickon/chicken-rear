package dev.kb896.aiusage.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Text kept under a key that never leaves the Android Keystore: AES/GCM, the blob `iv:ciphertext`
 * in base64, fit for a preferences file. What TokenStore does for an account's sign-in, as a
 * thing of its own, for the secrets that are not sign-ins — each kind under its own [alias], so
 * clearing one never orphans another.
 */
class SecretBox(private val alias: String) {

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** Null for anything that does not open: a blob from another key, a damaged one. */
    fun open(blob: String): String? = try {
        val (iv, ct) = blob.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Exception) { null }

    // Synchronized for TokenStore's reason: building a key under an alias REPLACES the one there,
    // so two first uses racing would leave what the first sealed unopenable for good.
    private fun key(): SecretKey = synchronized(LOCK) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        gen.generateKey()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        val LOCK = Any()
    }
}
