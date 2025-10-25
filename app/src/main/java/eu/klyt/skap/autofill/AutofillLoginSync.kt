package eu.klyt.skap.autofill

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import eu.klyt.skap.autofill.crypto.AutofillCryptoManager
import eu.klyt.skap.autofill.db.AutofillCredential
import eu.klyt.skap.autofill.db.AutofillDatabaseHelper
import eu.klyt.skap.lib.ClientEx
import eu.klyt.skap.lib.Decoded
import eu.klyt.skap.lib.getAll
import eu.klyt.skap.lib.Password
import eu.klyt.skap.lib.Uuid
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object AutofillLoginSync {
    suspend fun run(activity: AppCompatActivity, clientEx: ClientEx, token: String): Result<Int> {
        return try {
            // Prepare keystore
            AutofillCryptoManager.ensureKeyExists()

            // Authenticate once to unlock encryption window
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Secure Autofill storage")
                .setSubtitle("Authenticate to create/update local Autofill database")
                .setNegativeButtonText("Cancel")
                .build()

            // Authenticate first to unlock keystore window (no crypto object)
            val authResult = authenticateNoCrypto(activity, promptInfo)
            if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)

            // Fetch passwords from server
            val ownerUuid = clientEx.id.id ?: return Result.failure(Exception("Missing client UUID"))
            Log.d("AutofillLoginSync", "Starting sync for ownerUuid=$ownerUuid")
            val allResult = getAll(token, ownerUuid, clientEx.c)
            if (allResult.isFailure) return Result.failure(allResult.exceptionOrNull()!!)
            val all = allResult.getOrNull() ?: return Result.failure(Exception("No passwords returned"))
            Log.d("AutofillLoginSync", "Server returned ${all.passwords.size} passwords")
            // Encrypt and store
            val db = AutofillDatabaseHelper.getInstance(activity)
            val creds = all.passwords.map { (p, uuid) ->
                val encUserCipher = AutofillCryptoManager.createEncryptionCipher()
                val encPassCipher = AutofillCryptoManager.createEncryptionCipher()
                val usernameBytes = p.username.toByteArray(Charsets.UTF_8)
                val passwordBytes = p.password.toByteArray(Charsets.UTF_8)
                val encUser = encUserCipher.doFinal(usernameBytes)
                val encPass = encPassCipher.doFinal(passwordBytes)
                val label = p.url ?: p.app_id ?: p.username
                AutofillCredential(
                    id = Decoded.uuidToString(uuid),
                    label = label ?: p.username,
                    usernameHint = p.username,
                    usernameEnc = encUser,
                    usernameIv = encUserCipher.iv,
                    passwordEnc = encPass,
                    passwordIv = encPassCipher.iv,
                    url = p.url,
                    appId = p.app_id,
                    updatedAt = System.currentTimeMillis()
                )
            }
            Log.d("AutofillLoginSync", "Encrypting and inserting ${creds.size} credentials")
            db.insertAll(creds)
            val summariesCount = db.getAllSummaries().size
            Log.d("AutofillLoginSync", "Inserted ${creds.size}; summaries now $summariesCount")
            Result.success(creds.size)
        } catch (t: Throwable) {
            Log.e("AutofillLoginSync", "Sync failed", t)
            Result.failure(t)
        }
    }

    suspend fun runWithPasswords(activity: AppCompatActivity, passwords: List<Pair<Password, Uuid>>): Result<Int> {
        return try {
            // Prepare keystore and authenticate to unlock window
            AutofillCryptoManager.ensureKeyExists()
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Secure Autofill storage")
                .setSubtitle("Authenticate to update local Autofill database")
                .setNegativeButtonText("Cancel")
                .build()
            val authResult = authenticateNoCrypto(activity, promptInfo)
            if (authResult.isFailure) return Result.failure(authResult.exceptionOrNull()!!)
        
            val db = AutofillDatabaseHelper.getInstance(activity)
            val creds = passwords.map { (p, uuid) ->
                val encUserCipher = AutofillCryptoManager.createEncryptionCipher()
                val encPassCipher = AutofillCryptoManager.createEncryptionCipher()
                val usernameBytes = p.username.toByteArray(Charsets.UTF_8)
                val passwordBytes = p.password.toByteArray(Charsets.UTF_8)
                val encUser = encUserCipher.doFinal(usernameBytes)
                val encPass = encPassCipher.doFinal(passwordBytes)
                val label = p.url ?: p.app_id ?: p.username
                AutofillCredential(
                    id = Decoded.uuidToString(uuid),
                    label = label ?: p.username,
                    usernameHint = p.username,
                    usernameEnc = encUser,
                    usernameIv = encUserCipher.iv,
                    passwordEnc = encPass,
                    passwordIv = encPassCipher.iv,
                    url = p.url,
                    appId = p.app_id,
                    updatedAt = System.currentTimeMillis()
                )
            }
            Log.d("AutofillLoginSync", "Encrypting and inserting ${creds.size} credentials (preloaded)")
            db.insertAll(creds)
            val summariesCount = db.getAllSummaries().size
            Log.d("AutofillLoginSync", "Inserted ${creds.size}; summaries now $summariesCount (preloaded)")
            Result.success(creds.size)
        } catch (t: Throwable) {
            Log.e("AutofillLoginSync", "Sync failed (preloaded)", t)
            Result.failure(t)
        }
    }

    private suspend fun authenticateNoCrypto(activity: AppCompatActivity, promptInfo: BiometricPrompt.PromptInfo): Result<Unit> {
        return suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    cont.resume(Result.success(Unit))
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    cont.resume(Result.failure(Exception("Auth error: $errString")))
                }
                override fun onAuthenticationFailed() {
                    cont.resume(Result.failure(Exception("Authentication failed")))
                }
            })
            prompt.authenticate(promptInfo)
        }
    }
}