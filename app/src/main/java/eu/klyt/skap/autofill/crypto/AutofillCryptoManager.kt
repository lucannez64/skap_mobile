package eu.klyt.skap.autofill.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AutofillCryptoManager {
    private const val KEY_ALIAS = "skap_autofill_aes_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun ensureKeyExists() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        if (!ks.containsAlias(KEY_ALIAS)) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setInvalidatedByBiometricEnrollment(true)
                .setUserAuthenticationRequired(true)

            // Allow a brief window to batch encryption after a single biometric auth
            // Uses API-specific configuration
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R /* 30 */) {
                builder.setUserAuthenticationParameters(
                    30, // seconds
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            } else {
                builder.setUserAuthenticationValidityDurationSeconds(30)
            }

            kg.init(builder.build())
            kg.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        return ks.getKey(KEY_ALIAS, null) as SecretKey
    }

    fun createEncryptionCipher(): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        return cipher
    }

    fun createDecryptionCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        return cipher
    }
}