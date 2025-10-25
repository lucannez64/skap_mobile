package eu.klyt.skap.autofill

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.content.Intent
import android.app.PendingIntent
import eu.klyt.skap.autofill.db.AutofillDatabaseHelper
import android.text.InputType

class SkapAutofillService : AutofillService() {

    private data class FormFields(
        var usernameId: AutofillId? = null,
        var emailId: AutofillId? = null,
        var passwordId: AutofillId? = null,
        var webDomain: String? = null,
        var appPackage: String? = null,
    )

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        // Verbose start log to trace timing and context count
        Log.d("SkapAutofillService", "onFillRequest: fillContexts=${request.fillContexts.size}")
        try {
            val context = this
            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null) {
                Log.w("SkapAutofillService", "No AssistStructure in fill request")
                callback.onSuccess(null)
                return
            }
            val fields = findFormFields(structure)
            fields.appPackage = structure.activityComponent?.packageName

            // If no relevant fields, return no response
            if (fields.usernameId == null && fields.emailId == null && fields.passwordId == null) {
                Log.d(
                    "SkapAutofillService",
                    "No username/email/password fields; appPackage=${fields.appPackage}; webDomain=${fields.webDomain}; windows=${structure.windowNodeCount}. Returning no response"
                )
                callback.onSuccess(null)
                return
            }

            Log.d(
                "SkapAutofillService",
                "onFillRequest: hints username=${fields.usernameId != null}, email=${fields.emailId != null}, password=${fields.passwordId != null}; appPackage=${fields.appPackage}; webDomain=${fields.webDomain}"
            )

            val db = AutofillDatabaseHelper.getInstance(context)
            val summaries = db.getSummariesForTarget(fields.appPackage, fields.webDomain)
            Log.d("SkapAutofillService", "Filtered datasets count=${summaries.size}, labels=${summaries.map { it.label }}")

            // If no datasets available in DB, return no response
            if (summaries.isEmpty()) {
                callback.onSuccess(null)
                return
            }

            val datasets = summaries.mapIndexed { index, s ->
                Log.d("SkapAutofillService", "Preparing dataset index=${index} id=${s.id} label=${s.label} (authentication required)")
                val datasetBuilder = Dataset.Builder()

                val usernamePresentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                    val text = if (!s.usernameHint.isNullOrEmpty()) "${s.label} • ${s.usernameHint}" else s.label
                    setTextViewText(android.R.id.text1, text)
                }
                val passwordPresentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                    val text = if (!s.usernameHint.isNullOrEmpty()) "Password for ${s.label} • ${s.usernameHint}" else "Password for ${s.label}"
                    setTextViewText(android.R.id.text1, text)
                }

                fields.usernameId?.let { datasetBuilder.setValue(it, AutofillValue.forText(""), usernamePresentation) }
                fields.emailId?.let { datasetBuilder.setValue(it, AutofillValue.forText(""), usernamePresentation) }
                fields.passwordId?.let { datasetBuilder.setValue(it, AutofillValue.forText(""), passwordPresentation) }

                val authIntent = Intent(context, AutofillAuthActivity::class.java).apply {
                    putExtra(AutofillAuthActivity.EXTRA_ACCOUNT_ID, s.id)
                    putExtra(AutofillAuthActivity.EXTRA_USERNAME_ID, fields.usernameId)
                    putExtra(AutofillAuthActivity.EXTRA_EMAIL_ID, fields.emailId)
                    putExtra(AutofillAuthActivity.EXTRA_PASSWORD_ID, fields.passwordId)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    1000 + index,
                    authIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                datasetBuilder.setAuthentication(pendingIntent.intentSender)

                datasetBuilder.build()
            }

            // If we ended up with no datasets (edge), return no response
            if (datasets.isEmpty()) {
                callback.onSuccess(null)
                return
            }

            val response = FillResponse.Builder()
                .apply { datasets.forEach { addDataset(it) } }
                .build()

            Log.d("SkapAutofillService", "Built ${datasets.size} datasets")
            callback.onSuccess(response)
        } catch (t: Throwable) {
            Log.e("SkapAutofillService", "Error building autofill datasets", t)
            callback.onFailure(t.localizedMessage)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // For now, we don't persist user-entered data.
        callback.onSuccess()
    }

    private fun findFormFields(structure: AssistStructure): FormFields {
        val result = FormFields()
        val windowCount = structure.windowNodeCount
        Log.d("SkapAutofillService", "findFormFields: windowCount=$windowCount")
        for (i in 0 until windowCount) {
            val root = structure.getWindowNodeAt(i).rootViewNode
            traverseNode(root, result)
        }
        return result
    }

    private fun traverseNode(node: AssistStructure.ViewNode?, result: FormFields) {
        if (node == null) return
        val hints = node.autofillHints
        val id = node.autofillId
        // Try to extract webDomain when available
        try {
            val domain = node.webDomain
            if (!domain.isNullOrEmpty()) result.webDomain = domain
        } catch (_: Throwable) {
            // ignore
        }
        if (hints != null && id != null) {
            hints.forEach { hint ->
                Log.v("SkapAutofillService", "detected hint=$hint id=$id")
                when (hint) {
                    View.AUTOFILL_HINT_USERNAME -> result.usernameId = id
                    View.AUTOFILL_HINT_EMAIL_ADDRESS -> {
                        result.emailId = id
                        if (result.usernameId == null) result.usernameId = id
                    }
                    View.AUTOFILL_HINT_PASSWORD -> result.passwordId = id
                }
            }
        }
        // Heuristics for WebView and non-hinted fields
        if (id != null) {
            fun isPasswordNode(n: AssistStructure.ViewNode): Boolean {
                val type = n.inputType
                if (type != 0) {
                    val isText = (type and InputType.TYPE_CLASS_TEXT) == InputType.TYPE_CLASS_TEXT
                    val isPwdVar = (type and InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                            (type and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                            (type and InputType.TYPE_NUMBER_VARIATION_PASSWORD) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    if (isText && isPwdVar) return true
                }
                val html = n.htmlInfo
                if (html != null) {
                    val attrs = html.attributes
                    if (attrs != null) {
                        for (attr in attrs) {
                            val name = attr.first?.lowercase() ?: ""
                            val value = attr.second?.lowercase() ?: ""
                            if (name == "type" && value == "password") return true
                            if ((name == "name" || name == "id" || name == "autocomplete") && value.contains("password")) return true
                        }
                    }
                }
                return false
            }
            fun isEmailNode(n: AssistStructure.ViewNode): Boolean {
                val type = n.inputType
                if (type != 0) {
                    val isText = (type and InputType.TYPE_CLASS_TEXT) == InputType.TYPE_CLASS_TEXT
                    val isEmailVar = (type and InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                            (type and InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                    if (isText && isEmailVar) return true
                }
                val html = n.htmlInfo
                if (html != null) {
                    val attrs = html.attributes
                    if (attrs != null) {
                        for (attr in attrs) {
                            val name = attr.first?.lowercase() ?: ""
                            val value = attr.second?.lowercase() ?: ""
                            if (name == "type" && (value == "email")) return true
                            if ((name == "name" || name == "id" || name == "autocomplete") &&
                                (value.contains("email") || value.contains("identifier"))) return true
                        }
                    }
                }
                return false
            }
            fun isUsernameNode(n: AssistStructure.ViewNode): Boolean {
                val type = n.inputType
                if (type != 0) {
                    val isText = (type and InputType.TYPE_CLASS_TEXT) == InputType.TYPE_CLASS_TEXT
                    val isPwdVar = (type and InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    if (isText && !isPwdVar) {
                        // leave HTML checks to confirm
                    }
                }
                val html = n.htmlInfo
                if (html != null) {
                    val attrs = html.attributes
                    if (attrs != null) {
                        for (attr in attrs) {
                            val name = attr.first?.lowercase() ?: ""
                            val value = attr.second?.lowercase() ?: ""
                            if ((name == "name" || name == "id" || name == "autocomplete") &&
                                (value.contains("user") || value.contains("username") || value.contains("login") || value.contains("identifier"))) return true
                        }
                    }
                }
                return false
            }

            if (result.passwordId == null && isPasswordNode(node)) {
                Log.v("SkapAutofillService", "heuristic password id=$id")
                result.passwordId = id
            }
            if (result.emailId == null && isEmailNode(node)) {
                Log.v("SkapAutofillService", "heuristic email id=$id")
                result.emailId = id
                if (result.usernameId == null) result.usernameId = id
            }
            if (result.usernameId == null && isUsernameNode(node)) {
                Log.v("SkapAutofillService", "heuristic username id=$id")
                result.usernameId = id
            }
        }
        for (i in 0 until node.childCount) {
            traverseNode(node.getChildAt(i), result)
        }
    }

    private fun buildDatasets(fields: FormFields): List<Dataset> {
        val db = eu.klyt.skap.autofill.db.AutofillDatabaseHelper.getInstance(this)
        val summaries = db.getSummariesForTarget(fields.appPackage, fields.webDomain)
        if (summaries.isEmpty()) return emptyList()

        return summaries.mapIndexed { index, s ->
            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                val text = if (!s.usernameHint.isNullOrEmpty()) "${s.label} • ${s.usernameHint}" else s.label
                setTextViewText(android.R.id.text1, text)
            }

            // Build dataset that requires authentication before filling
            val intent = android.content.Intent(this, eu.klyt.skap.autofill.AutofillAuthActivity::class.java).apply {
                putExtra(eu.klyt.skap.autofill.AutofillAuthActivity.EXTRA_ACCOUNT_ID, s.id)
                putExtra(eu.klyt.skap.autofill.AutofillAuthActivity.EXTRA_USERNAME_ID, fields.usernameId)
                putExtra(eu.klyt.skap.autofill.AutofillAuthActivity.EXTRA_EMAIL_ID, fields.emailId)
                putExtra(eu.klyt.skap.autofill.AutofillAuthActivity.EXTRA_PASSWORD_ID, fields.passwordId)
            }

            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                1000 + index,
                intent,
                flags
            )

            android.service.autofill.Dataset.Builder().apply {
                // Provide placeholders (empty values) and require authentication
                fields.usernameId?.let { setValue(it, AutofillValue.forText(""), presentation) }
                fields.emailId?.let { setValue(it, AutofillValue.forText(""), presentation) }
                fields.passwordId?.let { setValue(it, AutofillValue.forText(""), presentation) }
                setAuthentication(pendingIntent.intentSender)
            }.build()
        }
    }
}