package com.hermeticacomms.sdk

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import org.json.JSONObject

internal data class Profile(val externalId: String?, val email: String?, val phone: String?, val anonymousId: String)

/** All SDK state. Network work runs on [executor] (one background thread), never on the caller's thread. */
internal class Core(context: Context, val siteKey: String, baseUrl: String, val cfg: Config, val executor: Executor) {
    val app: Context = context.applicationContext
    val prefs = app.getSharedPreferences("hermetica", Context.MODE_PRIVATE)
    @Volatile var optedOut = prefs.getBoolean(K_OPT_OUT, false)
        private set
    val http = Http(baseUrl, siteKey, cfg) { optedOut }
    val outbox = Outbox(prefs, http, cfg)
    val sessionId = "s" + uuid().replace("-", "").take(16)
    val main = Handler(Looper.getMainLooper())
    val push = Push(this)
    val inApp = InApp(this)
    val geofences = Geofences(this)
    val lifecycle = Lifecycle(onForeground = { inApp.trigger(InAppTrigger.AppOpen); push.refreshIfChanged() }, onBackground = { flushAsync() })
    private var flushScheduled = false

    init {
        (app as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        flushAsync() // whatever was left from the last run
    }

    @Synchronized
    fun anonymousId(): String = prefs.getString(K_AID, null) ?: uuid().also { prefs.edit().putString(K_AID, it).apply() }

    fun profile() = Profile(prefs.getString(K_EXT, null), prefs.getString(K_EMAIL, null), prefs.getString(K_PHONE, null), anonymousId())

    /** [trigger] = false for SDK events (push opens, geofences) that must not fetch in-app messages, as in the React Native SDK. */
    fun track(name: String, props: Map<String, Any?>, value: Long?, currency: String?, trigger: Boolean = true) {
        if (optedOut) return
        val ev = JSONObject()
            .put("name", name)
            .put("properties", JSONObject(props).put("hm_sdk", SDK_VERSION))
            .put("dedupeKey", uuid())
            .put("occurredAt", now())
            .put("anonymousId", anonymousId())
        if (value != null) ev.put("value", value)
        if (currency != null) ev.put("currency", currency)
        if (outbox.add(Outbox.EVENT, ev) >= cfg.batchSize) flushAsync() else scheduleFlush()
        if (trigger) inApp.trigger(InAppTrigger.Event(name, props))
    }

    fun identify(externalId: String?, email: String?, phone: String?, traits: Map<String, Any?>) {
        val e = prefs.edit()
        if (externalId != null) e.putString(K_EXT, externalId)
        if (email != null) e.putString(K_EMAIL, email)
        if (phone != null) e.putString(K_PHONE, phone)
        e.apply()
        sendIdentify(traits)
        reRegisterPush()
    }

    /** Traits set before any identifier is known wait for the first identify (the server needs an email, phone or external id). */
    fun sendIdentify(traits: Map<String, Any?>) {
        if (optedOut) return
        val p = profile()
        val all = JSONObject(prefs.getString(K_TRAITS, "{}") ?: "{}")
        val add = JSONObject(traits)
        for (k in add.keys()) all.put(k, add.get(k))
        val ids = JSONObject()
        if (p.externalId != null) ids.put("externalId", p.externalId)
        if (p.email != null) ids.put("email", p.email)
        if (p.phone != null) ids.put("phone", p.phone)
        if (ids.length() == 0) { prefs.edit().putString(K_TRAITS, all.toString()).apply(); return }
        prefs.edit().remove(K_TRAITS).apply()
        outbox.add(Outbox.IDENTIFY, JSONObject().put("anonymousId", p.anonymousId).put("identifiers", ids).put("traits", all))
        flushAsync()
    }

    /** A new visitor: forget the profile and anonymous id. Queued events keep the id they were tracked with. */
    fun reset() {
        prefs.edit().remove(K_EXT).remove(K_EMAIL).remove(K_PHONE).remove(K_AID).remove(K_TRAITS).apply()
        reRegisterPush()
    }

    fun setOptOut(out: Boolean) {
        optedOut = out
        prefs.edit().putBoolean(K_OPT_OUT, out).apply()
        if (out) outbox.clear() // never sent, as in the browser SDK when analytics consent is withdrawn
    }

    fun setConsent(analytics: Boolean, marketing: Boolean) {
        prefs.edit().putString(K_CONSENT, JSONObject().put("analytics", analytics).put("marketing", marketing).toString()).apply()
        if (!analytics) setOptOut(true) else if (optedOut) setOptOut(false)
        flushAsync()
    }

    private fun consent(): JSONObject? = prefs.getString(K_CONSENT, null)?.let { JSONObject(it) }

    fun flushNow(): Boolean = optedOut || outbox.flush(consent())

    fun flushAsync() = executor.execute { flushNow() }

    private fun scheduleFlush() {
        synchronized(this) {
            if (flushScheduled) return
            flushScheduled = true
        }
        main.postDelayed({ synchronized(this) { flushScheduled = false }; flushAsync() }, cfg.flushIntervalMs)
    }

    private fun reRegisterPush() {
        val token = push.token() ?: return
        executor.execute { push.register(token) }
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    companion object {
        const val K_AID = "hm_aid"
        const val K_EXT = "hm_external_id"
        const val K_EMAIL = "hm_email"
        const val K_PHONE = "hm_phone"
        const val K_TRAITS = "hm_pending_traits"
        const val K_OPT_OUT = "hm_opt_out"
        const val K_CONSENT = "hm_consent"
    }
}
