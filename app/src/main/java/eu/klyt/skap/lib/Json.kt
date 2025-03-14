package eu.klyt.skap.lib

import com.google.gson.Gson

fun jsonStringify(obj: Map<String, Any?>): String {
    return Gson().toJson(obj)
}