package com.smartspend.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the few sensitive values the app keeps on the phone — the login token and the queue
 * of parsed bank payments waiting to sync — with an AES-256-GCM key held in the Android
 * Keystore. The key never leaves the Keystore (hardware-backed where the phone has it), so a
 * copy of the app's files is useless without the phone itself.
 *
 * Stored values are "v1:" + base64(iv ‖ ciphertext). Anything without the prefix is a value
 * written by an older build: [decrypt] returns it as-is so nobody is signed out or loses queued
 * payments on update, and callers re-save it encrypted.
 */
object LocalCrypto {
    private const val TAG = "LocalCrypto"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "smartspend_local_v1"
    private const val PREFIX = "v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun isEncrypted(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    /**
     * Returns the encrypted form, or the plain value if the Keystore is unusable on this phone
     * (rare, broken OEM builds) — failing closed there would lock the user out of the app.
     */
    fun encrypt(plain: String): String = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "Keystore unavailable, storing value unencrypted", e)
        plain
    }

    /** Null when the value can't be decrypted (key gone, data corrupted) — treat as absent. */
    fun decrypt(stored: String?): String? {
        if (stored == null) return null
        if (!isEncrypted(stored)) return stored
        return try {
            val bytes = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt stored value", e)
            null
        }
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }
}
