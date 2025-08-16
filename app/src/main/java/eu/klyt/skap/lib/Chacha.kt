package eu.klyt.skap.lib

import android.util.ArraySet
import android.util.Log
import java.security.SecureRandom
import java.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.XChaCha20Poly1305Key
import com.google.crypto.tink.aead.XChaCha20Poly1305Parameters
import com.google.crypto.tink.util.SecretBytes
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyStatus
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import org.bouncycastle.util.Arrays
import java.nio.ByteBuffer


class XChaCha20Poly1305Cipher {
    // Key should be 32 bytes (256 bits)
    // Nonce should be 24 bytes (192 bits) for XChaCha20
    companion object {
        private const val KEY_SIZE = 32
        private const val NONCE_SIZE = 24
        private const val TAG_SIZE = 16  // Poly1305 authentication tag
    }

    init {
        AeadConfig.register()
    }

    fun generateKey(): ByteArray {
        val key = ByteArray(KEY_SIZE)
        SecureRandom().nextBytes(key)
        return key
    }

    fun encrypt(plaintext: ByteArray, key: ByteArray): EncryptionResult {
        require(key.size == KEY_SIZE)

        val access = InsecureSecretKeyAccess.get()
        val keyp = SecretBytes.copyFrom(key, access)
        val tinkKey = XChaCha20Poly1305Key.create(keyp)
        val aead = XChaCha20Poly1305.create(tinkKey)
        val c = aead.encrypt(plaintext, ByteArray(0))
        val nonce = c.copyOf(NONCE_SIZE)
        val ciphertext = c.copyOfRange(NONCE_SIZE, c.size)


        return EncryptionResult(ciphertext, nonce)
    }

    fun decrypt(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_SIZE)
        require(nonce.size == NONCE_SIZE)

        val access = InsecureSecretKeyAccess.get()
        val keyp = SecretBytes.copyFrom(key, access)
        val tinkKey = XChaCha20Poly1305Key.create(keyp)
        val newcipher = nonce+ciphertext
        val aead = XChaCha20Poly1305.create(tinkKey)
        return aead.decrypt(newcipher, ByteArray(0))
    }

    data class EncryptionResult(val ciphertext: ByteArray, val nonce: ByteArray) {
        fun toBase64(): Base64Result {
            return Base64Result(
                Base64.getEncoder().encodeToString(ciphertext),
                Base64.getEncoder().encodeToString(nonce)
            )
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EncryptionResult

            if (!ciphertext.contentEquals(other.ciphertext)) return false
            if (!nonce.contentEquals(other.nonce)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = ciphertext.contentHashCode()
            result = 31 * result + nonce.contentHashCode()
            return result
        }
    }

    data class Base64Result(val ciphertext: String, val nonce: String)
}