package eu.klyt.skap.autofill

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.content.pm.ApplicationInfo
import eu.klyt.skap.autofill.crypto.AutofillCryptoManager
import eu.klyt.skap.autofill.db.AutofillDatabaseHelper
import javax.crypto.Cipher

class AutofillAuthActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
            val usernameId: AutofillId? = intent.getParcelableExtra(EXTRA_USERNAME_ID)
            val emailId: AutofillId? = intent.getParcelableExtra(EXTRA_EMAIL_ID)
            val passwordId: AutofillId? = intent.getParcelableExtra(EXTRA_PASSWORD_ID)

            if (accountId.isNullOrEmpty()) {
                finishWithCancel()
                return
            }

            // Load encrypted record
            val db = AutofillDatabaseHelper.getInstance(this)
            val record = db.getById(accountId)
            if (record == null) {
                finishWithCancel()
                return
            }

            // Log database summaries and target record
            val summaries = db.getAllSummaries()
            Log.d("AutofillAuth", "Prompt arrived for account '${record.label}' (id=${accountId}). Available credentials (${summaries.size}): ${summaries.map { it.second }}")

            // Ensure keystore key exists
            AutofillCryptoManager.ensureKeyExists()

            // Authenticate first to unlock keystore; create decrypt ciphers after auth
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = BiometricPrompt(
                this,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            // Create decrypt ciphers within auth validity window and decrypt
                            val passwordCipher = AutofillCryptoManager.createDecryptionCipher(record.passwordIv)
                            val passwordBytes = passwordCipher.doFinal(record.passwordEnc)
                            val password = String(passwordBytes, Charsets.UTF_8)

                            val usernameCipher = AutofillCryptoManager.createDecryptionCipher(record.usernameIv)
                            val usernameBytes = usernameCipher.doFinal(record.usernameEnc)
                            val username = String(usernameBytes, Charsets.UTF_8)

                            val label = record.label

                            // Log decrypted credentials (password redacted in release)
                            val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                            val safePasswordLog = if (isDebug) password else "•••• (redacted)"
                            Log.d("AutofillAuth", "Decrypted credentials for '${label}': username='${username}', password='${safePasswordLog}'")

                            val presentationUser = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                                setTextViewText(android.R.id.text1, "$label • $username")
                            }
                            val presentationPass = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                                setTextViewText(android.R.id.text1, "Password for $label • $username")
                            }

                            val datasetBuilder = android.service.autofill.Dataset.Builder()
                            usernameId?.let { datasetBuilder.setValue(it, AutofillValue.forText(username), presentationUser) }
                            emailId?.let { datasetBuilder.setValue(it, AutofillValue.forText(username), presentationUser) }
                            passwordId?.let { datasetBuilder.setValue(it, AutofillValue.forText(password), presentationPass) }
                            val dataset = datasetBuilder.build()

                            val resultIntent = intent
                            resultIntent.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        } catch (t: Throwable) {
                            Log.e("AutofillAuth", "Decryption failed after auth", t)
                            finishWithCancel()
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        finishWithCancel()
                    }

                    override fun onAuthenticationFailed() {
                        // Stay on prompt; user can retry
                    }
                }
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock credentials")
                .setSubtitle("Authenticate to autofill selected account")
                .setNegativeButtonText("Cancel")
                .build()

            Log.d("AutofillAuth", "Showing biometric prompt for '${record.label}'")
            prompt.authenticate(promptInfo)
        } catch (t: Throwable) {
            finishWithCancel()
        }
    }

    private fun finishWithCancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_ACCOUNT_ID = "extra_account_id"
        const val EXTRA_USERNAME_ID = "extra_username_id"
        const val EXTRA_EMAIL_ID = "extra_email_id"
        const val EXTRA_PASSWORD_ID = "extra_password_id"
    }
}