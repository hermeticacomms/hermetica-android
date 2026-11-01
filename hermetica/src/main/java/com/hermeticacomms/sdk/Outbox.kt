package com.hermeticacomms.sdk

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The offline queue, persisted in SharedPreferences so nothing is lost when the app is killed or offline.
 * Items keep their order: identify calls go one by one, runs of events go in batches.
 */
internal class Outbox(private val prefs: SharedPreferences, private val http: Http, private val cfg: Config) {
    private val lock = Any()
    private val items: MutableList<JSONObject> = load()

    private fun load(): MutableList<JSONObject> = try {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        MutableList(a.length()) { a.getJSONObject(it) }
    } catch (_: JSONException) {
        mutableListOf()
    }

    private fun save() = prefs.edit().putString(KEY, JSONArray(items).toString()).apply()

    /** Returns the queue length after adding; the oldest items go once the queue is full. */
    fun add(kind: String, body: JSONObject): Int = synchronized(lock) {
        items += JSONObject().put("id", uuid()).put("kind", kind).put("body", body)
        if (items.size > cfg.maxQueue) items.subList(0, items.size - cfg.maxQueue).clear()
        save()
        items.size
    }

    fun size(): Int = synchronized(lock) { items.size }

    fun clear() = synchronized(lock) { items.clear(); save() }

    /** Sends everything queued. Stops at the first failure and keeps the rest for the next flush. Returns true when the queue is empty. */
    fun flush(consent: JSONObject?): Boolean {
        while (true) {
            val batch = synchronized(lock) { head() } ?: return true
            val first = batch.first()
            val code = if (first.getString("kind") == IDENTIFY) {
                http.post("/ingest/v1/identify", withConsent(JSONObject(first.getJSONObject("body").toString()), consent), first.getString("id"))
            } else {
                val body = JSONObject().put("events", JSONArray(batch.map { it.getJSONObject("body") }))
                // Same head and size means the same items, so a retry after a restart reuses the key.
                http.post("/ingest/v1/batch", withConsent(body, consent), "batch-${first.getString("id")}-${batch.size}")
            }
            // 4xx will not get better on retry: drop those items rather than block the queue forever.
            if (code !in 200..499 || code == 429) return false
            val sent = batch.map { it.getString("id") }.toSet()
            synchronized(lock) { items.removeAll { it.getString("id") in sent }; save() }
        }
    }

    private fun head(): List<JSONObject>? {
        val first = items.firstOrNull() ?: return null
        if (first.getString("kind") == IDENTIFY) return listOf(first)
        return items.asSequence().takeWhile { it.getString("kind") == EVENT }.take(cfg.batchSize).toList()
    }

    private fun withConsent(o: JSONObject, consent: JSONObject?) = if (consent == null) o else o.put("consent", consent)

    companion object {
        const val KEY = "hm_outbox"
        const val EVENT = "event"
        const val IDENTIFY = "identify"
    }
}
