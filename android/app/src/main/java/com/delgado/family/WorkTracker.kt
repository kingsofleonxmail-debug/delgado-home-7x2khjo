package com.delgado.family

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * "Approach mode": when Mom's phone gets within about a mile of a workplace, or steps out of one,
 * this turns on precise GPS for a few minutes to pin down the exact moment she arrives or leaves,
 * then turns GPS off again. Android requires a small notification while it runs.
 */
class WorkTrackerService : Service() {

    companion object {
        const val MODE_ARRIVE = "arrive"
        const val MODE_LEAVE = "leave"
        private const val CHANNEL = "work-tracker"
        private const val NOTE_ID = 4207

        fun start(ctx: Context, place: String, mode: String) {
            val app = ctx.applicationContext
            if (!Prefs.workTracking(app) || !Geofences.hasFine(app)) return
            val i = Intent(app, WorkTrackerService::class.java).putExtra("place", place).putExtra("mode", mode)
            try {
                app.startForegroundService(i)
            } catch (e: Exception) {
                // Android refused to start it in the background — the regular zone alerts still work as a backup.
            }
        }

        fun stop(ctx: Context, place: String? = null) {
            val app = ctx.applicationContext
            try { app.startService(Intent(app, WorkTrackerService::class.java).putExtra("stop", place ?: "*")) } catch (_: Exception) {}
        }

        /** Distance in meters from a fix to a workplace's saved spot, or null if we don't know the spot. */
        fun distanceTo(ctx: Context, place: String, loc: Location): Float? {
            val c = Prefs.coords(ctx, place) ?: return null
            val out = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, c.first, c.second, out)
            return out[0]
        }

        /** Send arrive/leave to the family script (with retries), remember it, refresh the widget. Blocking. */
        fun report(ctx: Context, place: String, event: String): Boolean {
            val app = ctx.applicationContext
            if (Prefs.lastWorkEvent(app, place) == event && System.currentTimeMillis() - Prefs.lastWorkTime(app, place) < 12 * 3600_000L) return true
            for (attempt in 1..3) {
                val res = Api.post(app, JSONObject().put("action", "work").put("event", event).put("loc", place))
                if (res != null) {
                    Prefs.setLastWork(app, place, event)
                    DashboardWidget.refreshNow(app)
                    return true
                }
                Thread.sleep(4000L * attempt)
            }
            return false
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var place = ""
    private var mode = MODE_ARRIVE
    private var startedAt = 0L
    private var insideSince = 0L
    private var outsideFixes = 0
    private var lastDistance = -1f
    private var done = false
    private var tracking = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stopFor = intent?.getStringExtra("stop")
        if (stopFor != null) {
            if (stopFor == "*" || stopFor == place) finish()
            return START_NOT_STICKY
        }
        val p = intent?.getStringExtra("place") ?: return START_NOT_STICKY.also { finish() }
        val m = intent.getStringExtra("mode") ?: MODE_ARRIVE
        goForeground(p, m)
        // Already doing the same job? Keep going.
        if (tracking && p == place && m == mode) return START_NOT_STICKY
        place = p; mode = m; startedAt = System.currentTimeMillis()
        insideSince = 0L; outsideFixes = 0; lastDistance = -1f; done = false
        startGps()
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ onTimeout() }, if (mode == MODE_ARRIVE) Config.APPROACH_TIMEOUT_MS else Config.LEAVE_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    private fun goForeground(p: String, m: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Work arrival tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows briefly while the app pins down arriving at or leaving work"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (m == MODE_ARRIVE) "Heading to $p…" else "Leaving $p…")
            .setContentText(if (m == MODE_ARRIVE) "Checking for arrival — GPS turns off when you get there" else "Confirming you've left — GPS turns off in a moment")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTE_ID, note)
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (tracking) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, Config.TRACK_INTERVAL_MS)
            .setMinUpdateIntervalMillis(Config.TRACK_INTERVAL_MS / 2)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(req, callback, Looper.getMainLooper())
            tracking = true
        } catch (e: Exception) {
            finish()
        }
    }

    private fun onFix(loc: Location) {
        if (done) return
        // Ignore fuzzy fixes (Wi-Fi/cell guesses) — those are what caused false alerts before.
        if (loc.hasAccuracy() && loc.accuracy > Config.MAX_FIX_ERROR_M) return
        val d = distanceTo(this, place, loc) ?: return finish()
        lastDistance = d
        val now = System.currentTimeMillis()
        if (mode == MODE_ARRIVE) {
            if (d <= Config.GEOFENCE_RADIUS_M) {
                if (insideSince == 0L) insideSince = now
                if (now - insideSince >= Config.CONFIRM_INSIDE_MS) send(MODE_ARRIVE)
            } else {
                insideSince = 0L
                if (d > Config.APPROACH_RADIUS_M * 1.4f) finish()        // drove away instead
            }
        } else {
            if (d >= Config.LEAVE_CONFIRM_M) {
                outsideFixes++
                if (outsideFixes >= 2) send(MODE_LEAVE)
            } else {
                outsideFixes = 0
                if (d <= Config.GEOFENCE_RADIUS_M) {
                    if (insideSince == 0L) insideSince = now
                    if (now - insideSince >= 2 * 60_000L) finish()      // still at work — false "left"
                }
            }
        }
    }

    private fun onTimeout() {
        if (done) return
        // Leaving: if the last good fix was well outside, count it as left; otherwise do nothing.
        if (mode == MODE_LEAVE && lastDistance >= Config.LEAVE_CONFIRM_M) send(MODE_LEAVE) else finish()
    }

    private fun send(event: String) {
        if (done) return
        done = true
        stopGps()
        val p = place
        thread {
            try { report(this, p, event) } finally { main.post { finish() } }
        }
    }

    private fun stopGps() {
        if (!tracking) return
        try { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(callback) } catch (_: Exception) {}
        tracking = false
    }

    private fun finish() {
        done = true
        main.removeCallbacksAndMessages(null)
        stopGps()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopGps()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
