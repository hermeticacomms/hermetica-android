package com.hermeticacomms.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONException
import org.json.JSONObject
import com.google.android.gms.location.Geofence as GmsGeofence

/** A circle to monitor; [radius] in metres. */
data class Geofence(val id: String, val name: String, val latitude: Double, val longitude: Double, val radius: Double)

internal class Geofences(private val core: Core) {
    /** The active geofences. Empty when the request fails. */
    fun fetch(): List<Geofence> {
        val res = core.http.get("/client/geofences")
        if (res == null || res.code !in 200..299) return emptyList()
        return try {
            val data = JSONObject(res.body).getJSONArray("data")
            List(data.length()) { i ->
                val g = data.getJSONObject(i)
                val a = g.getJSONObject("attributes")
                Geofence(g.getString("id"), a.optString("name"), a.getDouble("latitude"), a.getDouble("longitude"), a.getDouble("radius"))
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    /** `$geofence_enter` / `_exit` / `_dwell` as a normal event, so event-triggered journeys fire on it. */
    fun report(type: String, geofenceId: String) = core.track("\$geofence_$type", mapOf("geofence_id" to geofenceId), null, null, trigger = false)

    /** Never asks for permission: does nothing until the app holds ACCESS_FINE_LOCATION and has Play Services Location. */
    fun start() {
        if (!playServices() || !permitted()) { Log.w(TAG, "geofencing needs play-services-location and ACCESS_FINE_LOCATION"); return }
        core.executor.execute { GmsGeofences.register(core.app, fetch()) }
    }

    fun stop() { if (playServices()) GmsGeofences.unregister(core.app) }

    private fun permitted() = Build.VERSION.SDK_INT < 23 || core.app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun playServices() = try { Class.forName("com.google.android.gms.location.LocationServices"); true } catch (_: ClassNotFoundException) { false }
}

/** Every Play Services reference lives here, so the class is only loaded when the library is present. */
internal object GmsGeofences {
    private const val MAX = 100 // Android's limit of active geofences per app

    @SuppressLint("MissingPermission") // Geofences.start checked ACCESS_FINE_LOCATION
    fun register(context: Context, fences: List<Geofence>) {
        val client = LocationServices.getGeofencingClient(context)
        val pi = pendingIntent(context)
        client.removeGeofences(pi).addOnCompleteListener {
            if (fences.isEmpty()) return@addOnCompleteListener
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(fences.take(MAX).map {
                    GmsGeofence.Builder()
                        .setRequestId(it.id)
                        .setCircularRegion(it.latitude, it.longitude, it.radius.toFloat())
                        .setExpirationDuration(GmsGeofence.NEVER_EXPIRE)
                        .setTransitionTypes(GmsGeofence.GEOFENCE_TRANSITION_ENTER or GmsGeofence.GEOFENCE_TRANSITION_EXIT)
                        .build()
                })
                .build()
            client.addGeofences(request, pi).addOnFailureListener { e -> Log.w(TAG, "geofences not registered", e) }
        }
    }

    fun unregister(context: Context) { LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context)) }

    // Play Services fills in the event extras, so the intent must be mutable on Android 12+.
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, HermeticaGeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    fun transitions(intent: Intent): List<Pair<String, String>> {
        val e = GeofencingEvent.fromIntent(intent) ?: return emptyList()
        if (e.hasError()) return emptyList()
        val type = when (e.geofenceTransition) {
            GmsGeofence.GEOFENCE_TRANSITION_ENTER -> "enter"
            GmsGeofence.GEOFENCE_TRANSITION_EXIT -> "exit"
            GmsGeofence.GEOFENCE_TRANSITION_DWELL -> "dwell"
            else -> return emptyList()
        }
        return e.triggeringGeofences.orEmpty().map { type to it.requestId }
    }
}

/** Receives transitions for the fences registered by [Hermetica.startGeofencing] and sends them before the process may die. */
class HermeticaGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val events = GmsGeofences.transitions(intent)
        if (events.isEmpty()) return
        val pending = goAsync()
        Hermetica.onGeofenceTransitions(events) { pending.finish() }
    }
}

internal const val TAG = "Hermetica"
