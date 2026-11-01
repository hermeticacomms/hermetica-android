package com.hermeticacomms.sdk

import android.os.Build
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONObject

internal data class Config(
    val batchSize: Int = 20,
    val flushIntervalMs: Long = 2_000,
    val maxQueue: Int = 200,
    val maxRetries: Int = 5,
    val retryBaseMs: Long = 500,
)

internal const val SDK_VERSION = "android-" + BuildConfig.SDK_VERSION
internal fun uuid(): String = UUID.randomUUID().toString()

internal class Http(private val baseUrl: String, private val siteKey: String, private val cfg: Config, private val optedOut: () -> Boolean) {
    class Response(val code: Int, val body: String)

    /** Null on a network failure. */
    fun get(path: String, query: Map<String, String?> = emptyMap()): Response? =
        try { request("GET", path, query, null, null) } catch (_: IOException) { null }

    /**
     * Retries network errors, 429 and 5xx with exponential backoff, always with the same Idempotency-Key.
     * Returns the last status code, or -1 when the network never answered.
     */
    fun post(path: String, body: JSONObject, idempotencyKey: String): Int {
        var code = -1
        for (attempt in 0 until cfg.maxRetries) {
            code = try { request("POST", path, emptyMap(), body.toString(), idempotencyKey).code } catch (_: IOException) { -1 }
            if (code in 200..499 && code != 429) return code
            if (attempt < cfg.maxRetries - 1) Thread.sleep(cfg.retryBaseMs * (1L shl attempt) + (Math.random() * cfg.retryBaseMs).toLong())
        }
        return code
    }

    private fun request(method: String, path: String, query: Map<String, String?>, body: String?, key: String?): Response {
        // The /client endpoints take the public key as company_id (Klaviyo-compatible); ingest reads the bearer token.
        val q = (if (path.startsWith("/client/")) mapOf("company_id" to siteKey) + query else query)
            .filterValues { !it.isNullOrEmpty() }
            .entries.joinToString("&") { "${enc(it.key)}=${enc(it.value!!)}" }
        val conn = URL(baseUrl.trimEnd('/') + path + if (q.isEmpty()) "" else "?$q").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $siteKey")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "hermetica-android/${BuildConfig.SDK_VERSION} (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})")
            // The server drops everything from a visitor who sends Global Privacy Control.
            if (optedOut()) conn.setRequestProperty("Sec-GPC", "1")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (key != null) conn.setRequestProperty("Idempotency-Key", key)
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
