package com.delgado.family

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import kotlin.concurrent.thread

/** One simple screen: family code, whose phone this is, and Mom's work alerts. */
class SettingsActivity : Activity() {
    private val cBg = Color.parseColor("#080D1B")
    private val cPanel = Color.parseColor("#131D36")
    private val cLine = Color.parseColor("#26355A")
    private val cText = Color.parseColor("#E8EEFB")
    private val cMuted = Color.parseColor("#8A98B8")
    private val cAccent = Color.parseColor("#22D3EE")

    private lateinit var keyStatus: TextView
    private lateinit var workStatus: TextView
    private lateinit var workBox: LinearLayout
    private lateinit var permStatus: TextView
    private lateinit var placeStatus: TextView
    private lateinit var ciStatus: TextView
    private var pendingCheckIn: EditText? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(40))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(cBg); addView(col) }
        setContentView(scroll)

        col.addView(title("Delgado Family settings", 22f))

        // ----- Family code -----
        val codeCard = card(col)
        codeCard.addView(label("FAMILY CODE"))
        codeCard.addView(small("The same code you set in the Google script. Needed for the widget and work alerts."))
        val codeInput = EditText(this).apply {
            setText(Prefs.key(this@SettingsActivity))
            hint = "Family code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(cText); setHintTextColor(cMuted)
            background = box(Color.parseColor("#080D1B"), cLine)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        codeCard.addView(codeInput, lp(top = 10))
        keyStatus = small("")
        codeCard.addView(button("Save and test") {
            val code = codeInput.text.toString().trim()
            Prefs.setKey(this, code)
            keyStatus.text = "Checking…"
            thread {
                val res = Api.post(this, JSONObject().put("action", "events"))
                val msg = when {
                    code.isEmpty() -> "Enter the code first."
                    res == null -> "Couldn't reach the family script. Check the internet connection."
                    res.optString("error") == "bad key" -> "That code didn't work."
                    else -> "✓ Connected."
                }
                runOnUiThread { keyStatus.text = msg }
                if (msg.startsWith("✓")) DashboardWidget.refreshNow(this)
            }
        }, lp(top = 10))
        codeCard.addView(keyStatus, lp(top = 6))

        // ----- Whose phone -----
        val whoCard = card(col)
        whoCard.addView(label("THIS PHONE BELONGS TO"))
        whoCard.addView(small("Used as \"Logging as\" on the dashboard. Dad and Mom can edit Mom's schedule."))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("Dad", "Mom").forEach { name ->
            group.addView(RadioButton(this).apply {
                text = name; setTextColor(cText); id = View.generateViewId()
                isChecked = Prefs.owner(this@SettingsActivity) == name
                setOnClickListener { Prefs.setOwner(this@SettingsActivity, name); refreshWorkVisibility() }
            })
        }
        whoCard.addView(group, lp(top = 6))

        // ----- Mom's work alerts -----
        workBox = card(col)
        workBox.addView(label("MOM'S WORK ALERTS"))
        workBox.addView(small("When this phone arrives at or leaves Wilkes-Barre or Kingston, Dad gets a notification. " +
            "Only the time and place are sent — nothing else. Turn it off any time."))
        val sw = Switch(this).apply {
            text = "Send my work arrive / leave"
            setTextColor(cText)
            isChecked = Prefs.workTracking(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setWorkTracking(this@SettingsActivity, on)
                if (on) startWatching() else { Geofences.stop(this@SettingsActivity); workStatus.text = "Work alerts are off." }
                updatePermStatus()
            }
        }
        workBox.addView(sw, lp(top = 10))
        permStatus = small("")
        workBox.addView(permStatus, lp(top = 8))
        workBox.addView(button("1. Allow location") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }, lp(top = 8))
        workBox.addView(button("2. Allow location \"all the time\"") {
            if (!Geofences.hasFine(this)) { permStatus.text = "Do step 1 first."; return@button }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }, lp(top = 8))
        workBox.addView(button("3. Don't let battery saver stop it") { askBatteryExemption() }, lp(top = 8))
        workStatus = small("")
        workBox.addView(workStatus, lp(top = 10))

        workBox.addView(label("WORKPLACE SPOTS"), lp(top = 16))
        workBox.addView(small("Found automatically from the address. If alerts are off by a lot, stand at the entrance and tap \"I'm here now\"."))
        placeStatus = small("")
        workBox.addView(placeStatus, lp(top = 6))
        Config.WORKPLACES.forEach { w ->
            workBox.addView(button("I'm at ${w.name} now — use this spot") { useCurrentSpot(w.name) }, lp(top = 8))
        }

        // ----- Check in (Dad and Mom) -----
        val ciCard = card(col)
        ciCard.addView(label("CHECK IN"))
        ciCard.addView(small("Sends your location by email, just like the boys' check-in. Nothing is saved."))
        val ciNote = EditText(this).apply {
            hint = "Add a note (optional)"
            setTextColor(cText); setHintTextColor(cMuted)
            background = box(Color.parseColor("#080D1B"), cLine)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        ciCard.addView(ciNote, lp(top = 10))
        ciStatus = small("")
        ciCard.addView(button("📍 Check in") { checkIn(ciNote) }, lp(top = 10))
        ciCard.addView(ciStatus, lp(top = 6))

        refreshWorkVisibility()
        updatePermStatus()
        updatePlaceStatus()
        if (Prefs.workTracking(this)) startWatching()
    }

    override fun onResume() {
        super.onResume()
        if (::permStatus.isInitialized) updatePermStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5) {
            val note = pendingCheckIn; pendingCheckIn = null
            if (Geofences.hasFine(this) && note != null) checkIn(note) else ciStatus.text = "Location wasn't allowed, so the check-in wasn't sent."
            return
        }
        updatePermStatus()
        if (Prefs.workTracking(this)) startWatching()
    }

    private fun refreshWorkVisibility() {
        // The work section is only meant for Mom's phone.
        workBox.visibility = if (Prefs.owner(this) == "Mom" || Prefs.workTracking(this)) View.VISIBLE else View.GONE
    }

    private fun updatePermStatus() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        permStatus.text = listOf(
            (if (Geofences.hasFine(this)) "✓" else "✗") + " Location",
            (if (Geofences.hasBackground(this)) "✓" else "✗") + " All the time",
            (if (battery) "✓" else "✗") + " Battery saver off for this app"
        ).joinToString("   ")
    }

    private fun updatePlaceStatus() {
        placeStatus.text = Config.WORKPLACES.joinToString("\n") { w ->
            val c = Prefs.coords(this, w.name)
            "${w.name}: " + (c?.let { "set (%.5f, %.5f)".format(it.first, it.second) } ?: "not found yet")
        }
    }

    private fun startWatching() {
        workStatus.text = "Setting up…"
        Geofences.start(this) { msg -> runOnUiThread { workStatus.text = msg; updatePlaceStatus() } }
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** Get a fresh location fix and email it (with the optional note) through the family script. */
    @SuppressLint("MissingPermission")
    private fun checkIn(noteField: EditText) {
        if (Prefs.key(this).isEmpty()) { ciStatus.text = "Enter the family code at the top first."; return }
        if (!Geofences.hasFine(this)) {
            pendingCheckIn = noteField
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 5)
            return
        }
        ciStatus.text = "Getting your location…"
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc == null) { ciStatus.text = "Couldn't get a location fix. Try again outside or near a window."; return@addOnSuccessListener }
                ciStatus.text = "Sending…"
                val note = noteField.text.toString().trim().take(120)
                thread {
                    val res = Api.post(this, JSONObject().put("action", "checkin").put("who", Prefs.owner(this))
                        .put("lat", loc.latitude).put("lon", loc.longitude).put("acc", loc.accuracy.toInt()).put("note", note))
                    runOnUiThread {
                        when {
                            res == null -> ciStatus.text = "Couldn't reach the family script. Check the internet and try again."
                            res.optBoolean("ok") -> { ciStatus.text = "✓ " + res.optString("message", "Checked in"); noteField.setText("") }
                            else -> ciStatus.text = res.optString("message", res.optString("error", "Didn't send — try again."))
                        }
                    }
                }
            }
            .addOnFailureListener { ciStatus.text = "Couldn't get a location: ${it.message}" }
    }

    @SuppressLint("MissingPermission")
    private fun useCurrentSpot(place: String) {
        if (!Geofences.hasFine(this)) { workStatus.text = "Allow location first (step 1)."; return }
        workStatus.text = "Finding where you are…"
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc == null) { workStatus.text = "Couldn't get a location fix. Try again outside or near a window."; return@addOnSuccessListener }
                Prefs.setCoords(this, place, loc.latitude, loc.longitude)
                updatePlaceStatus()
                workStatus.text = "Saved this spot for $place."
                if (Prefs.workTracking(this)) startWatching()
            }
            .addOnFailureListener { workStatus.text = "Couldn't get a location: ${it.message}" }
    }

    // ---------- tiny UI helpers ----------
    private fun title(s: String, size: Float) = TextView(this).apply {
        text = s; textSize = size; setTextColor(cText); setTypeface(typeface, Typeface.BOLD)
    }
    private fun label(s: String) = TextView(this).apply {
        text = s; textSize = 11f; setTextColor(cMuted); letterSpacing = 0.12f; setTypeface(typeface, Typeface.BOLD)
    }
    private fun small(s: String) = TextView(this).apply { text = s; textSize = 13f; setTextColor(cMuted); setPadding(0, dp(4), 0, 0) }
    private fun box(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill); setStroke(dp(1), stroke); cornerRadius = dp(10).toFloat()
    }
    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(cPanel, cLine)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        parent.addView(c, lp(top = 16))
        return c
    }
    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(cAccent); gravity = Gravity.CENTER
        background = box(Color.parseColor("#1A22D3EE"), Color.parseColor("#9922D3EE"))
        setOnClickListener { onClick() }
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
}
