package com.hermeticacomms.sdk

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

/** FCM token registration (the server's Klaviyo-compatible push-token body) and notification-open tracking. */
internal class Push(private val core: Core) {
    fun token(): String? = core.prefs.getString(K_TOKEN, null)

    /** Registers the token with the current profile; true when the server took it. Runs on the SDK thread. */
    fun register(token: String): Boolean {
        val status = permissionStatus()
        val ok = core.http.post("/client/push-tokens", body(token, "push-token", status), uuid()) in 200..299
        if (ok) core.prefs.edit().putString(K_TOKEN, token).putString(K_STATUS, status).apply()
        return ok
    }

    fun unregister(): Boolean {
        val token = token() ?: return true
        val ok = core.http.post("/client/push-token-unregister", body(token, "push-token-unregister", permissionStatus()), uuid()) in 200..299
        if (ok) core.prefs.edit().remove(K_TOKEN).remove(K_STATUS).apply()
        return ok
    }

    /** The user can switch notifications on or off in system settings at any time: re-register when the status moved. */
    fun refreshIfChanged() {
        val token = token() ?: return
        if (core.prefs.getString(K_STATUS, null) != permissionStatus()) core.executor.execute { register(token) }
    }

    /** AUTHORIZED when notifications can be shown (POST_NOTIFICATIONS granted on Android 13+ and not blocked in settings), else DENIED. */
    fun permissionStatus(): String {
        if (Build.VERSION.SDK_INT >= 33 && core.app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "DENIED"
        val nm = core.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled()) "AUTHORIZED" else "DENIED"
    }

    internal fun body(token: String, type: String, status: String): JSONObject {
        val p = core.profile()
        val app = core.app
        val info: ApplicationInfo = app.applicationInfo
        val pkg: PackageInfo? = try { app.packageManager.getPackageInfo(app.packageName, 0) } catch (_: PackageManager.NameNotFoundException) { null }
        val build = when {
            pkg == null -> ""
            Build.VERSION.SDK_INT >= 28 -> pkg.longVersionCode.toString()
            else -> @Suppress("DEPRECATION") pkg.versionCode.toString()
        }
        val meta = JSONObject()
            .put("device_id", p.anonymousId)
            .put("sdk_version", SDK_VERSION)
            .put("device_model", Build.MODEL ?: "")
            .put("os_name", "android")
            .put("os_version", Build.VERSION.RELEASE ?: "")
            .put("app_name", info.loadLabel(app.packageManager).toString())
            .put("app_version", pkg?.versionName ?: "")
            .put("app_build", build)
            .put("app_id", app.packageName)
            // The server's push environments are sandbox | production.
            .put("environment", if (info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "sandbox" else "production")
        val who = JSONObject()
        if (p.email != null) who.put("email", p.email)
        if (p.phone != null) who.put("phone_number", p.phone)
        if (p.externalId != null) who.put("external_id", p.externalId)
        who.put("anonymous_id", p.anonymousId)
        val attrs = JSONObject()
            .put("token", token)
            .put("platform", "android")
            .put("vendor", "fcm")
            .put("enablement_status", status)
            .put("background", background())
            .put("device_metadata", meta)
            .put("profile", JSONObject().put("data", JSONObject().put("type", "profile").put("attributes", who)))
        return JSONObject().put("data", JSONObject().put("type", type).put("attributes", attrs))
    }

    private fun background(): String {
        val am = core.app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (Build.VERSION.SDK_INT >= 28 && am.isBackgroundRestricted) "RESTRICTED" else "AVAILABLE"
    }

    /** Tracks `$opened_push` for a Hermetica notification and returns its deep link. Handles each intent once. */
    fun handleOpened(intent: Intent?): String? {
        val extras = intent?.extras ?: return null
        val messageId = extras.getString(MESSAGE_ID)?.takeIf { it.isNotEmpty() }
        if (messageId != null) {
            core.track("\$opened_push", mapOf(MESSAGE_ID to messageId), null, null, trigger = false)
            intent.removeExtra(MESSAGE_ID) // an activity recreated with the same intent must not count a second open
        }
        return listOf("deep_link", "url", "hermetica_url").firstNotNullOfOrNull { k -> extras.getString(k)?.takeIf { it.isNotEmpty() } }
    }

    companion object {
        const val MESSAGE_ID = "hermetica_message_id"
        const val K_TOKEN = "hm_push_token"
        const val K_STATUS = "hm_push_status"
    }
}
