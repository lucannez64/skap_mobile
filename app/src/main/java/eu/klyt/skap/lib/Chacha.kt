package eu.klyt.skap.lib

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.SecureRandom
import java.util.Base64

class XChaCha20Poly1305Cipher {
    // Key should be 32 bytes (256 bits)
    // Nonce should be 24 bytes (192 bits) for XChaCha20
    
    companion object {
        private const val KEY_SIZE = 32
        private const val NONCE_SIZE = 24
        private const val TAG_SIZE = 16  // Poly1305 authentication tag
    }
    
    fun generateKey(): ByteArray {
        val key = ByteArray(KEY_SIZE)
        SecureRandom().nextBytes(key)
        return key
    }
    
    fun encrypt(plaintext: ByteArray, key: ByteArray): EncryptionResult {
        // Generate random nonce
        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)
        
        // Set up cipher
        val cipher = ChaCha7539Engine()
        val params = ParametersWithIV(KeyParameter(key), nonce)
        
        // Encrypt
        val ciphertext = ByteArray(plaintext.size + TAG_SIZE)
        cipher.init(true, params)
        val len = cipher.processBytes(plaintext, 0, plaintext.size, ciphertext, 0)

        return EncryptionResult(ciphertext, nonce)
    }
    
    fun decrypt(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        // Set up cipher
        val cipher = ChaCha7539Engine()
        val params = ParametersWithIV(KeyParameter(key), nonce)
        
        // Decrypt
        val plaintext = ByteArray(ciphertext.size - TAG_SIZE)
        cipher.init(false, params)
        val len = cipher.processBytes(ciphertext, 0, ciphertext.size, plaintext, 0)
        return plaintext
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
