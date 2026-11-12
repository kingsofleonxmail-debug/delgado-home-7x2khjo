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
    const val NEAR = "near:"

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
            val fences = Config.WORKPLACES.flatMap { w ->
                val c = Prefs.coords(app, w.name) ?: return@flatMap emptyList<Geofence>()
                // Big "approach" ring: entering it turns on precise GPS (see WorkTracker.kt)
                val near = Geofence.Builder()
                    .setRequestId(NEAR + w.name)
                    .setCircularRegion(c.first, c.second, Config.APPROACH_RADIUS_M)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setNotificationResponsiveness(0)
                    .build()
                // Work zone itself: backup arrive (DWELL) and the trigger to confirm leaving (EXIT)
                listOf(near, Geofence.Builder()
                    .setRequestId(w.name)
                    .setCircularRegion(c.first, c.second, Config.GEOFENCE_RADIUS_M)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    // "Arrived" only after staying inside a few minutes (DWELL); "left" as soon as Android notices (EXIT).
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setLoiteringDelay(Config.ARRIVE_AFTER_MS)
                    .setNotificationResponsiveness(0)       // ask Android for its fastest updates
                    .build())
            }
            if (fences.isEmpty()) { done("Couldn't find the workplaces — use the \"I'm here now\" buttons."); return@thread }
            val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
            LocationServices.getGeofencingClient(app).addGeofences(request, pendingIntent(app))
                .addOnSuccessListener {
                    val names = fences.map { it.requestId }.filter { !it.startsWith(NEAR) }.joinToString(" and ")
                    done("Watching $names." + if (failed.isNotEmpty()) " Couldn't find ${failed.joinToString()} — use its \"I'm here now\" button." else "")
                }
                .addOnFailureListener { e -> done("Couldn't start watching: ${e.message}. Check Location is on.") }
        }
    }

    fun stop(ctx: Context) {
        WorkTrackerService.stop(ctx)
        LocationServices.getGeofencingClient(ctx.applicationContext).removeGeofences(pendingIntent(ctx.applicationContext))
    }
}

/** Android calls this when Mom's phone crosses the approach ring or the work zone. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        val app = context.applicationContext
        val ids = ev.triggeringGeofences?.map { it.requestId } ?: return
        val recentlyArrived = { place: String ->
            Prefs.lastWorkEvent(app, place) == "arrive" && System.currentTimeMillis() - Prefs.lastWorkTime(app, place) < 16 * 3600_000L }
        val backupArrivals = mutableListOf<String>()
        for (id in ids) {
            val near = id.startsWith(Geofences.NEAR)
            val place = id.removePrefix(Geofences.NEAR)
            when (ev.geofenceTransition) {
                // Getting close: precise GPS pins down the arrival (about a minute)
                Geofence.GEOFENCE_TRANSITION_ENTER -> if (near && !recentlyArrived(place)) WorkTrackerService.start(app, place, WorkTrackerService.MODE_ARRIVE)
                // Stepped out of the work zone: precise GPS confirms she really left (no more GPS-jump alerts)
                Geofence.GEOFENCE_TRANSITION_EXIT -> when {
                    !near && recentlyArrived(place) -> WorkTrackerService.start(app, place, WorkTrackerService.MODE_LEAVE)
                    near && recentlyArrived(place) -> backupArrivals.add("leave:$place")    // a mile away: definitely left
                    near -> WorkTrackerService.stop(app, place)                                // drove past without stopping
                }
                // Backup: Android's own "stayed 3 minutes" if approach mode didn't catch it
                Geofence.GEOFENCE_TRANSITION_DWELL -> if (!near && !recentlyArrived(place)) backupArrivals.add("arrive:$place")
            }
        }
        if (backupArrivals.isEmpty()) return
        val pending = goAsync()
        thread {
            try {
                for (item in backupArrivals) {
                    val (event, place) = item.split(":", limit = 2)
                    WorkTrackerService.report(app, place, event)
                }
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
