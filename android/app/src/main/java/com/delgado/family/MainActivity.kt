package com.delgado.family

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.app.AlertDialog
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.URLEncoder

/** The full dashboard, full screen, plus a small gear for app settings. */
class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#080D1B")
        window.navigationBarColor = Color.parseColor("#080D1B")

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#080D1B"))
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#080D1B"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false   // lets the daily briefing play its voice
        web.settings.setGeolocationEnabled(true)                 // lets the dashboard's Check in share location
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Keep the dashboard inside the app; open anything else in the browser.
                if (url.startsWith(Config.DASHBOARD_URL)) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                shareSettingsWithPage()
            }
        }
        // Show the page's "Delete this?" confirmations and notices (WebView hides them by default).
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (Geofences.hasFine(this@MainActivity)) {
                    callback.invoke(origin, true, false)
                } else {
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 31)
                    callback.invoke(origin, false, false)
                }
            }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("Yes") { _, _ -> result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.confirm() }
                    .show()
                return true
            }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(Color.parseColor("#22D3EE"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6131D36"))
                setStroke(2, Color.parseColor("#2622D3EE"))
            }
            contentDescription = "Settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        val size = (48 * resources.displayMetrics.density).toInt()
        val margin = (16 * resources.displayMetrics.density).toInt()
        root.addView(gear, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(margin, margin, margin, margin)
        })
        // On phones the dashboard puts Settings in its bottom dock, so the floating gear hides there.
        // It stays on bigger screens (no dock) and if the page can't load.
        web.addJavascriptInterface(object {
            @JavascriptInterface fun openSettings() {
                runOnUiThread { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            }
            @JavascriptInterface fun setDock(showing: Boolean) {
                runOnUiThread { gear.visibility = if (showing) View.GONE else View.VISIBLE }
            }
        }, "DelgadoApp")
        setContentView(root)

        val shared = sharedRecipeUrl(intent)
        when {
            shared != null -> web.loadUrl(shared)
            savedInstanceState != null -> web.restoreState(savedInstanceState)
            else -> web.loadUrl(Config.DASHBOARD_URL)
        }
        if (Prefs.key(this).isEmpty()) startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** Hand the family code and "who is logging" to the web page so nobody types the code twice. */
    private fun shareSettingsWithPage() {
        val key = Prefs.key(this)
        if (key.isEmpty()) return
        val js = """
            (function(){ try {
              var changed = false;
              if (localStorage.getItem('fd-key') !== JSON.stringify(${JSONObject.quote(key)})) {
                localStorage.setItem('fd-key', JSON.stringify(${JSONObject.quote(key)})); changed = true; }
              if (!localStorage.getItem('fd-who')) localStorage.setItem('fd-who', JSON.stringify(${JSONObject.quote(Prefs.owner(this))}));
              return changed ? 'reload' : '';
            } catch(e) { return ''; } })()
        """.trimIndent()
        web.evaluateJavascript(js) { result -> if (result?.contains("reload") == true) web.reload() }
    }

    /** A recipe link shared to the app opens the dashboard's "Add from a recipe" review screen. */
    private fun sharedRecipeUrl(i: Intent?): String? {
        if (i?.action != Intent.ACTION_SEND) return null
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val link = Regex("https?://\\S+").find(text)?.value ?: return null
        return Config.DASHBOARD_URL + "?recipe=" + URLEncoder.encode(link, "UTF-8")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedRecipeUrl(intent)?.let { web.loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        shareSettingsWithPage()
    }

    override fun onPause() {
        web.onPause()
        DashboardWidget.requestRefresh(this)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
