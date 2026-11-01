package com.hermeticacomms.sdk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The Hermetica SDK. Call [initialize] once in `Application.onCreate`; every other call is safe from any thread and
 * returns at once (network work runs on a background thread). Calls made before [initialize] are ignored.
 */
object Hermetica {
    @Volatile internal var core: Core? = null
        private set

    @JvmStatic
    fun initialize(context: Context, siteKey: String, baseUrl: String) = initialize(context, siteKey, baseUrl, Config(), Executors.newSingleThreadExecutor())

    @Synchronized
    internal fun initialize(context: Context, siteKey: String, baseUrl: String, cfg: Config, executor: Executor) {
        if (core != null) return
        require(siteKey.isNotBlank()) { "siteKey is required" }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) { "baseUrl must be an http(s) URL" }
        val c = Core(context, siteKey, baseUrl, cfg, executor)
        core = c
        if (hasClass("com.google.firebase.messaging.FirebaseMessaging")) FcmToken.fetch { setPushToken(it) }
    }

    /** Tests only: forget the instance so the next [initialize] starts fresh. */
    internal fun shutdown() = synchronized(this) { core = null }

    private inline fun <T> withCore(default: T, f: Core.() -> T): T {
        val c = core ?: run { Log.w(TAG, "Hermetica.initialize has not been called"); return default }
        return c.f()
    }

    // ---- identity and events ----

    /** Names the profile (any of external id, email, phone) and links this device's anonymous history to it. */
    @JvmStatic @JvmOverloads
    fun identify(externalId: String?, email: String? = null, phone: String? = null, traits: Map<String, Any?> = emptyMap()) =
        withCore(Unit) { identify(externalId, email, phone, traits) }

    /** Profile properties (first_name, plan, ...). Sent with the current identity; held until the first [identify] if there is none yet. */
    @JvmStatic
    fun setProfileAttributes(attributes: Map<String, Any?>) = withCore(Unit) { sendIdentify(attributes) }

    /** [value] is in minor currency units (cents) with an ISO 4217 [currency]. Events are queued, batched and retried. */
    @JvmStatic @JvmOverloads
    fun track(name: String, properties: Map<String, Any?> = emptyMap(), value: Long? = null, currency: String? = null) =
        withCore(Unit) { track(name, properties, value, currency) }

    /** A screen view: a `page_view` with the screen name, and a screen trigger for in-app messages. */
    @JvmStatic @JvmOverloads
    fun screen(name: String, properties: Map<String, Any?> = emptyMap()) = withCore(Unit) {
        track("page_view", properties + ("screen" to name), null, null, trigger = false)
        inApp.trigger(InAppTrigger.Screen(name))
    }

    /** Logout: forget the profile and start a new anonymous id. The push token moves to the new anonymous visitor. */
    @JvmStatic
    fun reset() = withCore(Unit) { reset() }

    @JvmStatic
    fun anonymousId(): String? = withCore(null) { anonymousId() }

    /**
     * Opted out: nothing is tracked or sent (queued events are dropped, in-app messages are not fetched) until opted back in.
     * Push tokens are still registered: opting out of tracking does not stop the messages the person chose to receive.
     */
    @JvmStatic
    fun setTrackingOptOut(optOut: Boolean) = withCore(Unit) { setOptOut(optOut) }

    @JvmStatic
    fun isTrackingOptedOut(): Boolean = withCore(false) { optedOut }

    /** For workspaces that require consent: what the person agreed to. analytics = false is the same as opting out. */
    @JvmStatic
    fun setConsent(analytics: Boolean, marketing: Boolean) = withCore(Unit) { setConsent(analytics, marketing) }

    /** Sends the queue now (it is also sent every few seconds, at 20 events, and when the app goes to the background). */
    @JvmStatic
    fun flush() = withCore(Unit) { flushAsync() }

    // ---- push ----

    /** Registers an FCM token with the profile. [HermeticaMessagingService] calls this for you. */
    @JvmStatic
    fun setPushToken(token: String) = withCore(Unit) { executor.execute { push.register(token) } }

    /** Stops pushes to this device (call on logout before [reset] if the next user must not get the previous user's pushes). */
    @JvmStatic
    fun unregisterPushToken() = withCore(Unit) { executor.execute { push.unregister() } }

    /** AUTHORIZED or DENIED: whether this app may show notifications (POST_NOTIFICATIONS on Android 13+). Never asks. */
    @JvmStatic
    fun pushPermissionStatus(): String = withCore("DENIED") { push.permissionStatus() }

    /** Call from the activity a notification opens (onCreate and onNewIntent). Tracks the open and returns the deep link, or null. */
    @JvmStatic
    fun handlePushOpened(intent: Intent?): String? = withCore(null) { push.handleOpened(intent) }

    /** Opens a deep link or URL with ACTION_VIEW. False when no app can open it. */
    @JvmStatic
    fun openDeepLink(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
    }

    // ---- in-app ----

    /** Render messages yourself (return true from onMessage), or watch button actions of the default presenter. */
    @JvmStatic
    fun setInAppListener(listener: InAppListener?) = withCore(Unit) { inApp.listener = listener }

    /** Fetch the messages for a trigger without showing them. The callback runs on a background thread. */
    @JvmStatic
    fun fetchInAppMessages(trigger: InAppTrigger, callback: (List<InAppMessage>) -> Unit) =
        withCore(Unit) { executor.execute { callback(inApp.fetch(trigger)) } }

    /** [type] is impression, click or dismiss; [button] is the tapped button's label. Only needed when you render messages yourself. */
    @JvmStatic @JvmOverloads
    fun reportInAppEvent(message: InAppMessage, type: String, button: String? = null) = withCore(Unit) { inApp.report(message, type, button) }

    // ---- geofences ----

    /** Opt-in. Registers the workspace's geofences with Play Services; needs ACCESS_FINE_LOCATION (and ACCESS_BACKGROUND_LOCATION for background transitions), which you request. */
    @JvmStatic
    fun startGeofencing() = withCore(Unit) { geofences.start() }

    @JvmStatic
    fun stopGeofencing() = withCore(Unit) { geofences.stop() }

    /** For apps with their own geofencing: the fences to monitor. The callback runs on a background thread. */
    @JvmStatic
    fun fetchGeofences(callback: (List<Geofence>) -> Unit) = withCore(Unit) { executor.execute { callback(geofences.fetch()) } }

    /** For apps with their own geofencing: [type] is enter, exit or dwell. */
    @JvmStatic
    fun reportGeofence(type: String, geofenceId: String) = withCore(Unit) { geofences.report(type, geofenceId) }

    internal fun onGeofenceTransitions(events: List<Pair<String, String>>, done: () -> Unit) {
        val c = core ?: return done()
        for ((type, id) in events) c.geofences.report(type, id)
        c.executor.execute { try { c.flushNow() } finally { done() } }
    }

    private fun hasClass(name: String) = try { Class.forName(name); true } catch (_: ClassNotFoundException) { false }
}
