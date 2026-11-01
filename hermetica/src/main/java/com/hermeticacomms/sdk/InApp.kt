package com.hermeticacomms.sdk

import java.util.Collections
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class InAppButton(val label: String, val action: String, val value: String?, val style: String?)
data class InAppBlock(val type: String, val text: String?, val url: String?, val alt: String?, val buttons: List<InAppButton>)
data class InAppStyle(val background: String, val textColor: String, val accentColor: String, val radius: Int, val dismissible: Boolean)

/** [layout] is modal, banner_top, banner_bottom, fullscreen or bottom_sheet; [blocks] are heading, text, image or buttons. */
data class InAppMessage(
    val id: String, val variant: String, val layout: String, val queued: Boolean, val priority: Int,
    val blocks: List<InAppBlock>, val html: String?, val style: InAppStyle,
)

/** The moments that can show a message: app open, a screen, or any tracked event. */
sealed class InAppTrigger(internal val type: String) {
    object AppOpen : InAppTrigger("app_open")
    data class Screen(val name: String) : InAppTrigger("screen_view")
    data class Event(val name: String, val properties: Map<String, Any?> = emptyMap()) : InAppTrigger("event")
}

interface InAppListener {
    /** Return true when you show the message yourself; then report impression/click/dismiss with [Hermetica.reportInAppEvent]. */
    fun onMessage(message: InAppMessage): Boolean
    /** A deep_link, url or event button was tapped in the default presenter. Return true to skip the default (open the link, track the event). */
    fun onAction(action: String, value: String, message: InAppMessage): Boolean
}

/** Fetch, report and queue in-app messages. Eligibility and frequency caps are decided by the server per session. */
internal class InApp(private val core: Core) {
    @Volatile var listener: InAppListener? = null
    private val seen = Collections.synchronizedSet(HashSet<String>())
    private val waiting = ArrayDeque<InAppMessage>() // main thread only
    private var showing = false

    /** The messages that fit this moment, best first. Empty on any failure: a missing message must never break the app. */
    fun fetch(t: InAppTrigger): List<InAppMessage> {
        if (core.optedOut) return emptyList()
        val p = core.profile()
        val res = core.http.get("/client/in-app-messages", mapOf(
            "platform" to "android", "trigger" to t.type, "session_id" to core.sessionId,
            "name" to when (t) { is InAppTrigger.Screen -> t.name; is InAppTrigger.Event -> t.name; else -> null },
            "properties" to (t as? InAppTrigger.Event)?.properties?.takeIf { it.isNotEmpty() }?.let { JSONObject(it).toString() },
            "external_id" to p.externalId, "email" to p.email, "phone_number" to p.phone, "anonymous_id" to p.anonymousId,
        ))
        if (res == null || res.code !in 200..299) return emptyList()
        return try { parseMessages(JSONObject(res.body).getJSONArray("items")) } catch (_: JSONException) { emptyList() }
    }

    /** Fetches for a trigger and shows the best new message. Skipped when nothing could show it (no listener, app in background). */
    fun trigger(t: InAppTrigger) {
        if (core.optedOut || (listener == null && !core.lifecycle.inForeground)) return
        core.executor.execute {
            val best = fetch(t).firstOrNull() ?: return@execute
            if (seen.add(best.id)) core.main.post { enqueue(best) }
        }
    }

    private fun enqueue(m: InAppMessage) {
        if (listener?.onMessage(m) == true) return
        if (showing) { waiting.addLast(m); return }
        val activity = core.lifecycle.resumed ?: run { seen.remove(m.id); return } // try again at the next trigger
        showing = true
        InAppPresenter(activity, m, report = { type, button -> report(m, type, button) }, onButton = { onButton(m, it) }, onClosed = {
            showing = false
            waiting.removeFirstOrNull()?.let { enqueue(it) }
        }).show()
    }

    private fun onButton(m: InAppMessage, b: InAppButton) {
        val value = b.value?.takeIf { it.isNotEmpty() } ?: return
        if (b.action == "dismiss" || listener?.onAction(b.action, value, m) == true) return
        if (b.action == "event") core.track(value, mapOf("hermetica_message_id" to m.id), null, null)
        else Hermetica.openDeepLink(core.lifecycle.resumed ?: core.app, value)
    }

    fun report(m: InAppMessage, type: String, button: String? = null) = core.executor.execute { reportNow(m, type, button) }

    fun reportNow(m: InAppMessage, type: String, button: String?): Boolean {
        if (core.optedOut) return false
        val p = core.profile()
        val ev = JSONObject().put("messageId", m.id).put("variant", m.variant).put("type", type).put("platform", "android").put("sessionId", core.sessionId)
        if (button != null) ev.put("button", button)
        val who = JSONObject().put("anonymousId", p.anonymousId)
        if (p.externalId != null) who.put("externalId", p.externalId)
        if (p.email != null) who.put("email", p.email)
        if (p.phone != null) who.put("phoneNumber", p.phone)
        return core.http.post("/client/in-app-events", JSONObject().put("events", JSONArray().put(ev)).put("profile", who), uuid()) in 200..299
    }
}

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)
private fun <T> JSONArray?.list(f: (JSONObject) -> T): List<T> = if (this == null) emptyList() else List(length()) { f(getJSONObject(it)) }

internal fun parseMessages(items: JSONArray): List<InAppMessage> = items.list { o ->
    val s = o.getJSONObject("style")
    InAppMessage(
        id = o.getString("id"), variant = o.optString("variant", "a"), layout = o.optString("layout", "modal"),
        queued = o.optBoolean("queued"), priority = o.optInt("priority"),
        blocks = o.getJSONObject("content").optJSONArray("blocks").list { b ->
            InAppBlock(b.getString("type"), b.str("text"), b.str("url"), b.str("alt"), b.optJSONArray("buttons").list { x ->
                InAppButton(x.getString("label"), x.optString("action", "dismiss"), x.str("value"), x.str("style"))
            })
        },
        html = o.getJSONObject("content").str("html"),
        style = InAppStyle(s.optString("background", "#ffffff"), s.optString("textColor", "#111111"), s.optString("accentColor", "#111111"), s.optInt("radius", 12), s.optBoolean("dismissible", true)),
    )
}
