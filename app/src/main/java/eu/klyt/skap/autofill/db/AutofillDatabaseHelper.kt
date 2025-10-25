package eu.klyt.skap.autofill.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import android.net.Uri

data class AutofillCredential(
    val id: String,
    val label: String,
    val usernameHint: String?,
    val usernameEnc: ByteArray,
    val usernameIv: ByteArray,
    val passwordEnc: ByteArray,
    val passwordIv: ByteArray,
    val url: String?,
    val appId: String?,
    val updatedAt: Long
)

class AutofillDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "autofill.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        Log.d("AutofillDB", "onCreate: creating schema and indexes")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS credentials(
              id TEXT PRIMARY KEY,
              label TEXT NOT NULL,
              username_hint TEXT,
              username_enc BLOB NOT NULL,
              username_iv BLOB NOT NULL,
              password_enc BLOB NOT NULL,
              password_iv BLOB NOT NULL,
              url TEXT,
              app_id TEXT,
              updated_at INTEGER NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_credentials_label ON credentials(label);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE credentials ADD COLUMN username_hint TEXT;")
        }
    }

    fun insertAll(creds: List<AutofillCredential>) {
        Log.d("AutofillDB", "insertAll: replacing ${creds.size} records")
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("credentials", null, null)
            for (c in creds) {
                val v = ContentValues().apply {
                    put("id", c.id)
                    put("label", c.label)
                    put("username_hint", c.usernameHint)
                    put("username_enc", c.usernameEnc)
                    put("username_iv", c.usernameIv)
                    put("password_enc", c.passwordEnc)
                    put("password_iv", c.passwordIv)
                    put("url", c.url)
                    put("app_id", c.appId)
                    put("updated_at", c.updatedAt)
                }
                db.insert("credentials", null, v)
            }
            db.setTransactionSuccessful()
            Log.d("AutofillDB", "insertAll: transaction successful")
        } finally {
            db.endTransaction()
            Log.d("AutofillDB", "insertAll: transaction ended")
        }
    }

    fun getAllSummaries(): List<Pair<String, String>> { // id, label
        val list = mutableListOf<Pair<String, String>>()
        val c = readableDatabase.rawQuery("SELECT id, label FROM credentials ORDER BY label ASC", null)
        c.use {
            while (it.moveToNext()) {
                list.add(Pair(it.getString(0), it.getString(1)))
            }
        }
        Log.d("AutofillDB", "getAllSummaries: count=${list.size}")
        return list
    }

    data class AutofillSummary(
        val id: String,
        val label: String,
        val usernameHint: String?
    )

    fun getSummariesForTarget(appId: String?, domain: String?): List<AutofillSummary> {
        fun normalizeHost(input: String?): String? {
            if (input.isNullOrBlank()) return null
            var s = input.trim().lowercase()
            try {
                val uri = Uri.parse(s)
                if (!uri.host.isNullOrBlank()) s = uri.host!!
            } catch (_: Throwable) {}
            s = s.removePrefix("www.")
            s = s.substringBefore(":")
            s = s.trimEnd('/')
            return if (s.isBlank()) null else s
        }
        fun brandFromHost(host: String?): String? {
            if (host.isNullOrBlank()) return null
            val parts = host.split('.')
            if (parts.size >= 2) return parts[parts.size - 2]
            return parts.firstOrNull()
        }
        fun appContainsBrand(appPkg: String?, brand: String?): Boolean {
            if (appPkg.isNullOrBlank() || brand.isNullOrBlank()) return false
            val segs = appPkg.lowercase().split('.')
            return segs.contains(brand.lowercase())
        }

        val normalizedDomain = normalizeHost(domain)
        val query = "SELECT id, label, username_hint, url, app_id FROM credentials ORDER BY label ASC"
        val results = LinkedHashMap<String, AutofillSummary>()
        val c = readableDatabase.rawQuery(query, null)
        c.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val label = it.getString(1)
                val hint = it.getString(2)
                val url = it.getString(3)
                val rowAppId = it.getString(4)
                var match = false

                if (normalizedDomain != null) {
                    val host = normalizeHost(url)
                    if (!host.isNullOrEmpty()) {
                        if (normalizedDomain == host || normalizedDomain.endsWith(".$host") || normalizedDomain.endsWith(host)) {
                            match = true
                        }
                    }
                    if (!match && !url.isNullOrEmpty()) {
                        val contains = url.contains(normalizedDomain, ignoreCase = true) || url.contains(domain ?: "", ignoreCase = true)
                        if (contains) match = true
                    }
                } else if (!appId.isNullOrEmpty()) {
                    // Native app: match by stored appId OR brand derived from URL host
                    if (!rowAppId.isNullOrEmpty() && rowAppId == appId) {
                        match = true
                    } else {
                        val host = normalizeHost(url)
                        val brand = brandFromHost(host)
                        if (appContainsBrand(appId, brand)) {
                            match = true
                        }
                    }
                } else {
                    match = true
                }

                if (match) {
                    results[id] = AutofillSummary(id = id, label = label, usernameHint = hint)
                }
            }
        }
        Log.d("AutofillDB", "getSummariesForTarget: appId=$appId domain=$domain (norm=$normalizedDomain) count=${results.size}")
        return results.values.toList()
    }

    fun getById(id: String): AutofillCredential? {
        val c = readableDatabase.rawQuery(
            "SELECT id, label, username_hint, username_enc, username_iv, password_enc, password_iv, url, app_id, updated_at FROM credentials WHERE id=?",
            arrayOf(id)
        )
        c.use {
            if (it.moveToFirst()) {
                Log.d("AutofillDB", "getById: found id=$id label=${it.getString(1)}")
                return AutofillCredential(
                    it.getString(0),
                    it.getString(1),
                    it.getString(2),
                    it.getBlob(3),
                    it.getBlob(4),
                    it.getBlob(5),
                    it.getBlob(6),
                    it.getString(7),
                    it.getString(8),
                    it.getLong(9)
                )
            }
        }
        Log.d("AutofillDB", "getById: id=$id not found")
        return null
    }

    fun clearAll() {
        Log.d("AutofillDB", "clearAll: deleting all credentials")
        writableDatabase.delete("credentials", null, null)
    }

    companion object {
        @Volatile private var instance: AutofillDatabaseHelper? = null
        fun getInstance(context: Context): AutofillDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: AutofillDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}