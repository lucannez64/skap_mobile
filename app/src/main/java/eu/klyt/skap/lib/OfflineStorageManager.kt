package eu.klyt.skap.lib

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.GeneralSecurityException
import java.io.IOException

/**
 * Manages offline storage of encrypted user data using the same encryption protocol
 * as server communication (XChaCha20Poly1305) with the client's kyQ key
 */
class OfflineStorageManager private constructor(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "skap_offline_storage"
        private const val KEY_OFFLINE_MODE_ENABLED = "offline_mode_enabled"
        private const val KEY_ENCRYPTED_PASSWORDS = "encrypted_passwords"
        private const val KEY_ENCRYPTED_SHARED_PASSWORDS = "encrypted_shared_passwords"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_EX = "client_ex"
        
        @Volatile
        private var INSTANCE: OfflineStorageManager? = null
        
        fun getInstance(context: Context): OfflineStorageManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OfflineStorageManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            Log.e("OfflineStorage", "Error creating encrypted preferences", e)
            throw e
        } catch (e: IOException) {
            Log.e("OfflineStorage", "Error creating encrypted preferences", e)
            throw e
        }
    }
    
    private val cipher = XChaCha20Poly1305Cipher()
    private val gson = Gson()
    
    /**
     * Data class for storing offline password data
     */
    data class OfflinePasswordData(
        val passwords: List<Pair<String, String>>, // Pair<EncryptedPassword, UuidString>
        val sharedPasswords: List<Quadruple<String, String, String, Int>> // Quadruple<EncryptedPassword, UuidString, SharerUuidString, StatusInt>
    )
    
    /**
     * Checks if offline mode is enabled
     */
    fun isOfflineModeEnabled(): Boolean {
        return try {
            encryptedPrefs.getBoolean(KEY_OFFLINE_MODE_ENABLED, false)
        } catch (e: Exception) {
            Log.e("OfflineStorage", "Error checking offline mode status", e)
            false
        }
    }
    
    /**
     * Enables or disables offline mode
     */
    fun setOfflineModeEnabled(enabled: Boolean) {
        try {
            encryptedPrefs.edit()
                .putBoolean(KEY_OFFLINE_MODE_ENABLED, enabled)
                .apply()
        } catch (e: Exception) {
            Log.e("OfflineStorage", "Error setting offline mode status", e)
            throw e
        }
    }
    
    /**
     * Saves user data offline with encryption using the client's kyQ key
     */
    fun saveOfflineData(
        passwords: List<Pair<Password, Uuid>>,
        sharedPasswords: List<Quadruple<Password, Uuid, Uuid, ShareStatus>>,
        userEmail: String,
        clientId: String,
        clientEx: ClientEx
    ): Result<Unit> {
        return try {
            val kyQ = clientEx.c.kyQ
            if (kyQ.isEmpty()) {
                Log.e("OfflineStorage", "Invalid encryption key provided")
                return Result.failure(Exception("Invalid encryption key provided"))
            }
            
            // Encode and store ClientEx for consistent key usage
            val encoder = BincodeEncoder()
            val encodedClientEx = encoder.encodeClientEx(clientEx)
            
            if (passwords.isEmpty() && sharedPasswords.isEmpty()) {
                Log.w("OfflineStorage", "No data to save offline")
                return Result.success(Unit)
            }
            val encryptionKey = blake3(kyQ)
            
            // Encrypt and serialize passwords
            val encryptedPasswords = passwords.mapIndexed { index, (password, uuid) ->
                try {
                    val encoder = BincodeEncoder()
                    val passwordBytes = encoder.encodePassword(password)
                    val encryptionResult = cipher.encrypt(passwordBytes, encryptionKey)
                    val encryptedData = gson.toJson(mapOf(
                        "ciphertext" to encryptionResult.ciphertext.toUint8Array(),
                        "nonce" to encryptionResult.nonce.toUint8Array()
                    ))
                    // Password serialized successfully
                    Pair(encryptedData, uuid.toString())
                } catch (e: Exception) {
                    Log.e("OfflineStorage", "Failed to encrypt password $index", e)
                    throw e
                }
            }
            
            // Encrypt and serialize shared passwords
            val encryptedSharedPasswords = sharedPasswords.mapIndexed { index, (password, uuid, sharerUuid, status) ->
                try {
                    // Encrypting shared password
                    val encoder = BincodeEncoder()
                    val passwordBytes = encoder.encodePassword(password)
                    val encryptionResult = cipher.encrypt(passwordBytes, encryptionKey)
                    val encryptedData = gson.toJson(mapOf(
                        "ciphertext" to encryptionResult.ciphertext.toUint8Array(),
                        "nonce" to encryptionResult.nonce.toUint8Array()
                    ))
                    // Shared password encrypted successfully
                    Quadruple(
                        encryptedData,
                        uuid.toString(),
                        sharerUuid.toString(),
                        when(status) {
                            ShareStatus.Pending -> 0
                            ShareStatus.Accepted -> 1
                            ShareStatus.Rejected -> 2
                        }
                    )
                } catch (e: Exception) {
                    Log.e("OfflineStorage", "Failed to encrypt shared password $index", e)
                    throw e
                }
            }
            
            val offlineData = OfflinePasswordData(encryptedPasswords, encryptedSharedPasswords)
            val serializedData = gson.toJson(offlineData)
            
            // Save to encrypted preferences
            val editor = encryptedPrefs.edit()
            editor.putString(KEY_ENCRYPTED_PASSWORDS, serializedData)
            editor.putString(KEY_USER_EMAIL, userEmail)
            editor.putString(KEY_CLIENT_ID, clientId)
            editor.putString(KEY_CLIENT_EX, encodedClientEx.toUint8Array().joinToString(","))
            val success = editor.commit() // Use commit() instead of apply() for immediate verification
            
            if (success) {
                Log.i("OfflineStorage", "Successfully saved ${passwords.size} passwords and ${sharedPasswords.size} shared passwords offline")
                
                // Immediate verification
                val verificationData = encryptedPrefs.getString(KEY_ENCRYPTED_PASSWORDS, null)
                if (verificationData == null) {
                    Log.e("OfflineStorage", "Verification failed - no data found after saving")
                    return Result.failure(Exception("Data verification failed after saving"))
                }
                
                Result.success(Unit)
            } else {
                Log.e("OfflineStorage", "Failed to commit data to encrypted preferences")
                Result.failure(Exception("Failed to save data to encrypted preferences"))
            }
            
        } catch (e: Exception) {
            Log.e("OfflineStorage", "Error saving offline data", e)
            Result.failure(e)
        }
    }
    
    /**
     * Loads and decrypts offline data using the stored client's kyQ key
     */
    fun loadOfflineData(): Result<Pair<List<Pair<Password, Uuid>>, List<Quadruple<Password, Uuid, Uuid, ShareStatus>>>> {
        return try {
            // Starting loadOfflineData
            
            // Retrieve and decode stored ClientEx
            val encodedClientExString = encryptedPrefs.getString(KEY_CLIENT_EX, null)
            if (encodedClientExString == null) {
                Log.e("OfflineStorage", "No ClientEx data found in encrypted preferences")
                return Result.failure(Exception("No ClientEx data found - cannot decrypt without proper key"))
            }
            
            val encodedClientEx = encodedClientExString.split(",").map { it.toInt().toByte() }.toByteArray()
            val clientEx = Decoded.decodeClientEx(encodedClientEx)
            if (clientEx == null) {
                Log.e("OfflineStorage", "Failed to decode ClientEx data")
                return Result.failure(Exception("Failed to decode ClientEx data"))
            }
            
            val kyQ = clientEx.c.kyQ
            // ClientEx decoded successfully
            
            if (kyQ.isEmpty()) {
                Log.e("OfflineStorage", "Invalid decryption key - kyQ is empty")
                return Result.failure(Exception("Invalid decryption key"))
            }
            
            if (!hasOfflineData()) {
                Log.e("OfflineStorage", "No offline data available - hasOfflineData() returned false")
                return Result.failure(Exception("No offline data available"))
            }
            
            val serializedData = encryptedPrefs.getString(KEY_ENCRYPTED_PASSWORDS, null)
            if (serializedData == null) {
                Log.e("OfflineStorage", "No offline data found in encrypted preferences")
                return Result.failure(Exception("No offline data found"))
            }
            
            val encryptionKey = blake3(kyQ)
            
            // Parsing JSON data
            val offlineData = try {
                gson.fromJson(serializedData, OfflinePasswordData::class.java)
            } catch (e: Exception) {
                Log.e("OfflineStorage", "Failed to parse JSON data", e)
                throw Exception("Failed to parse offline data: ${e.message}", e)
            }
            
            
            // Decrypt passwords
            val passwords = offlineData.passwords.mapIndexedNotNull { index, (encryptedData, uuidString) ->
                try {
                    val encryptedMap = gson.fromJson(encryptedData, Map::class.java) as Map<String, List<Double>>
                    val ciphertext = encryptedMap["ciphertext"]!!.map { it.toInt().toByte() }.toByteArray()
                    val nonce = encryptedMap["nonce"]!!.map { it.toInt().toByte() }.toByteArray()
                    
                    val decryptedBytes = cipher.decrypt(ciphertext, nonce, encryptionKey)
                    
                    val password = Decoded.decodePassword(decryptedBytes)
                    val uuid = createUuid(uuidString)
                    
                    if (password != null) {
                        Pair(password, uuid)
                    } else {
                        Log.w("OfflineStorage", "Failed to decode password for UUID: $uuidString")
                        null
                    }
                } catch (e: Exception) {
                    Log.e("OfflineStorage", "Failed to decrypt password $index for UUID: $uuidString", e)
                    null
                }
            }
            
            // Decrypt shared passwords
            val sharedPasswords = offlineData.sharedPasswords.mapIndexedNotNull { index, (encryptedData, uuidString, sharerUuidString, statusInt) ->
                try {
                    // Decrypting shared password
                    val encryptedMap = gson.fromJson(encryptedData, Map::class.java) as Map<String, List<Double>>
                    val ciphertext = encryptedMap["ciphertext"]!!.map { it.toInt().toByte() }.toByteArray()
                    val nonce = encryptedMap["nonce"]!!.map { it.toInt().toByte() }.toByteArray()
                    
                    val decryptedBytes = cipher.decrypt(ciphertext, nonce, encryptionKey)
                    val password = Decoded.decodePassword(decryptedBytes)
                    val uuid = createUuid(uuidString)
                    val sharerUuid = createUuid(sharerUuidString)
                    val status = when(statusInt) {
                        0 -> ShareStatus.Pending
                        1 -> ShareStatus.Accepted
                        2 -> ShareStatus.Rejected
                        else -> ShareStatus.Pending
                    }
                    
                    if (password != null) {
                        // Shared password decoded successfully
                        Quadruple(password, uuid, sharerUuid, status)
                    } else {
                        Log.w("OfflineStorage", "Failed to decode shared password for UUID: $uuidString")
                        null
                    }
                } catch (e: Exception) {
                    Log.e("OfflineStorage", "Failed to decrypt shared password $index for UUID: $uuidString", e)
                    null
                }
            }
            Log.d("OfflineStorage", "Shared password decryption completed - ${sharedPasswords.size} shared passwords successfully decrypted")
            
            Log.i("OfflineStorage", "Successfully loaded ${passwords.size} passwords and ${sharedPasswords.size} shared passwords from offline storage")
            Result.success(Pair(passwords, sharedPasswords))
            
        } catch (e: Exception) {
            Log.e("OfflineStorage", "Error loading offline data", e)
            Result.failure(e)
        }
    }
    
    /**
     * Gets stored user email
     */
    fun getUserEmail(): String? {
        return try {
            encryptedPrefs.getString(KEY_USER_EMAIL, null)
        } catch (e: Exception) {
            Log.e("OfflineStorage", "Error getting user email", e)
            null
        }
    }
    
    /**
     * Gets stored client ID
     */
    fun getClientId(): String? {
        return try {
            encryptedPrefs.getString(KEY_CLIENT_ID, null)
        } catch (e: Exception) {
            Log.e("OfflineStorage", "Error getting client ID", e)
            null
        }
    }
    
    /**
     * Clears all offline data
     */
    fun clearOfflineData(): Result<Unit> {
        return try {
            encryptedPrefs.edit()
                .remove(KEY_ENCRYPTED_PASSWORDS)
                .remove(KEY_USER_EMAIL)
                .remove(KEY_CLIENT_ID)
                .remove(KEY_CLIENT_EX)
                .putBoolean(KEY_OFFLINE_MODE_ENABLED, false)
                .apply()
            
            Log.i("OfflineStorage", "Successfully cleared offline data")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e("OfflineStorage", "Error clearing offline data", e)
            Result.failure(e)
        }
    }
    
    /**
     * Checks if offline data exists
     */
    fun hasOfflineData(): Boolean {
        return try {
            encryptedPrefs.contains(KEY_ENCRYPTED_PASSWORDS)
        } catch (e: Exception) {
            Log.e("OfflineStorage", "Error checking offline data existence", e)
            false
        }
    }
}