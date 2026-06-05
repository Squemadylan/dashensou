package com.dashensou.app.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin wrapper around org.json that prefers a friendlier exception
 * type. The previous codebase threw raw `org.json.JSONException`
 * subclasses and either swallowed them silently or wrapped them
 * generically; sources would also write their own
 * `try { JSONObject(s) } catch (e: Exception) { ... }` blocks.
 *
 * Note: org.json's String ctor is a facade over [JSONTokener]; it
 * does NOT throw on empty / null input — it returns an empty
 * JSONObject or a parsed result respectively. We keep that behaviour
 * and only add the friendly wrap for malformed payloads.
 */
object Json {

    @Throws(IllegalArgumentException::class)
    fun parseObject(text: String): JSONObject {
        require(text.isNotBlank()) { "JSON text is blank" }
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON object: ${e.message}", e)
        }
    }

    @Throws(IllegalArgumentException::class)
    fun parseArray(text: String): JSONArray {
        require(text.isNotBlank()) { "JSON text is blank" }
        return try {
            JSONArray(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON array: ${e.message}", e)
        }
    }
}
