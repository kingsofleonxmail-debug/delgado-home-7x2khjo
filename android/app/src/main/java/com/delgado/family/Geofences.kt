package com.delgado.family

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

/** Watches Mom's two workplaces and reports arrive/leave to the family script. */
object Geofences {

    fun hasFine(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 7, Intent(ctx, GeofenceReceiver::class.java), flags)
    }

    /** Look up any workplace that has no saved spot yet. Blocking — call from a background thread. */
    @Suppress("DEPRECATION")
    fun geocodeMissing(ctx: Context): List<String> {
        val failed = mutableListOf<String>()
        if (!Geocoder.isPresent()) return Config.WORKPLACES.filter { Prefs.coords(ctx, it.name) == null }.map { it.name }
        val geo = Geocoder(ctx, Locale.US)
        for (w in Config.WORKPLACES) {
            if (Prefs.coords(ctx, w.name) != null) continue
            try {
                val hit = geo.getFromLocationName(w.address, 1)?.firstOrNull()
                if (hit != null) Prefs.setCoords(ctx, w.name, hit.latitude, hit.longitude) else failed.add(w.name)
            } catch (e: Exception) {
                failed.add(w.name)
            }
        }
        return failed
    }

    /** Start watching. Calls back on a background thread with a short status message. */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context, done: (String) -> Unit = {}) {
        val app = ctx.applicationContext
        thread {
            if (!Prefs.workTracking(app)) { done("Work alerts are off."); return@thread }
            if (!hasFine(app) || !hasBackground(app)) { done("Location permission needed — tap the buttons above."); return@thread }
            val failed = geocodeMissing(app)
            val fences = Config.WORKPLACES.mapNotNull { w ->
                val c = Prefs.coords(app, w.name) ?: return@mapNotNull null
                Geofence.Builder()
                    .setRequestId(w.name)
                    .setCircularRegion(c.first, c.second, Config.GEOFENCE_RADIUS_M)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    // "Arrived" only after staying inside a few minutes (DWELL); "left" as soon as Android notices (EXIT).
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setLoiteringDelay(Config.ARRIVE_AFTER_MS)
                    .setNotificationResponsiveness(0)       // ask Android for its fastest updates
                    .build()
            }
            if (fences.isEmpty()) { done("Couldn't find the workplaces — use the \"I'm here now\" buttons."); return@thread }
            val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
            LocationServices.getGeofencingClient(app).addGeofences(request, pendingIntent(app))
                .addOnSuccessListener {
                    val names = fences.joinToString(" and ") { it.requestId }
                    done("Watching $names." + if (failed.isNotEmpty()) " Couldn't find ${failed.joinToString()} — use its \"I'm here now\" button." else "")
                }
                .addOnFailureListener { e -> done("Couldn't start watching: ${e.message}. Check Location is on.") }
        }
    }

    fun stop(ctx: Context) {
        LocationServices.getGeofencingClient(ctx.applicationContext).removeGeofences(pendingIntent(ctx.applicationContext))
    }
}

/** Android calls this when Mom's phone enters or leaves a workplace. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        val event = when (ev.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_DWELL -> "arrive"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "leave"
            else -> return
        }
        val places = ev.triggeringGeofences?.map { it.requestId } ?: return
        val pending = goAsync()
        val app = context.applicationContext
        thread {
            try {
                for (place in places) {
                    var ok = false
                    for (attempt in 1..3) {             // phones often wake with a weak signal — retry a little
                        val res = Api.post(app, JSONObject().put("action", "work").put("event", event).put("loc", place))
                        if (res != null) { ok = true; break }
                        Thread.sleep(5000L * attempt)
                    }
                    if (!ok) break
                }
                DashboardWidget.refreshNow(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Geofences are cleared when the phone restarts or the app updates, so set them up again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.workTracking(context)) Geofences.start(context)
        DashboardWidget.requestRefresh(context)
    }
}
