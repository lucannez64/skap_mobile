package eu.klyt.skap.lib

import org.junit.Test
import org.junit.Assert.*

class XChaCha20Poly1305CipherTest {

    @Test
    fun testEncryptDecryptCycle() {
        val cipher = XChaCha20Poly1305Cipher()
        val key = cipher.generateKey()
        val plaintext = "Hello, World! This is a test message for encryption.".toByteArray()
        
        // Encrypt the data
        val encryptionResult = cipher.encrypt(plaintext, key)
        
        // Verify encryption result has proper components
        assertNotNull("Ciphertext should not be null", encryptionResult.ciphertext)
        assertNotNull("Nonce should not be null", encryptionResult.nonce)
        assertEquals("Nonce should be 24 bytes", 24, encryptionResult.nonce.size)
        assertTrue("Ciphertext should not be empty", encryptionResult.ciphertext.isNotEmpty())
        
        // Decrypt the data
        val decryptedData = cipher.decrypt(encryptionResult.ciphertext, encryptionResult.nonce, key)
        
        // Verify decryption worked correctly
        assertArrayEquals("Decrypted data should match original plaintext", plaintext, decryptedData)
        
        val decryptedString = String(decryptedData)
        assertEquals("Decrypted string should match original", "Hello, World! This is a test message for encryption.", decryptedString)
    }
    
    @Test
    fun testMultipleEncryptDecryptCycles() {
        val cipher = XChaCha20Poly1305Cipher()
        val key = cipher.generateKey()
        
        val testMessages = listOf(
            "Short message",
            "A much longer message that contains various characters: !@#$%^&*()_+-=[]{}|;':,.<>?",
            "Unicode test: 你好世界 🌍 émojis 🎉",
            "" // Empty string
        )
        
        for (message in testMessages) {
            val plaintext = message.toByteArray()
            
            // Encrypt
            val encryptionResult = cipher.encrypt(plaintext, key)
            
            // Decrypt
            val decryptedData = cipher.decrypt(encryptionResult.ciphertext, encryptionResult.nonce, key)
            
            // Verify
            assertArrayEquals("Message '$message' should decrypt correctly", plaintext, decryptedData)
        }
    }
}