package com.delgado.family

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject

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
        setContentView(root)

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(Config.DASHBOARD_URL)
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
