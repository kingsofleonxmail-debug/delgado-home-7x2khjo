package com.delgado.family

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Shared fetch + the two tap-to-log widgets (Sebastian's nap and pets). */
object Widgets {
    const val ACTION_NAP = "com.delgado.family.NAP_TOGGLE"
    const val ACTION_PET = "com.delgado.family.PET"
    const val EXTRA_TYPE = "type"

    /** Pet buttons: type sent to the script → (cell id, last-time id, label). Types match the dashboard. */
    data class Pet(val type: String, val cell: Int, val last: Int, val label: String)
    val PETS = listOf(
        Pet("dogfed", R.id.cell_dog, R.id.last_dog, "Fed dog"),
        Pet("catfed", R.id.cell_cat, R.id.last_cat, "Fed cat"),
        Pet("dogsout", R.id.cell_out, R.id.last_out, "Dogs out"),
        Pet("litter", R.id.cell_litter, R.id.last_litter, "Litterbox")
    )

    fun ymd(d: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
    fun time(ms: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))
    fun dur(ms: Long): String { val m = ms / 60000; return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }
    private fun uid() = java.lang.Long.toString(System.currentTimeMillis(), 36) + (1000..9999).random()

    private fun ids(ctx: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    private fun fetchState(ctx: Context): JSONObject? =
        Api.post(ctx, JSONObject().put("action", "state").put("date", ymd(Date())).put("days", 2))

    /** One network call, then redraw every widget on the home screen. Blocking. */
    fun refreshAll(ctx: Context) {
        val app = ctx.applicationContext
        val data = fetchState(app)
        val offline = data == null
        val json = data ?: Prefs.lastWidgetJson(app)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (data != null) Prefs.setLastWidgetJson(app, data.toString())
        DashboardWidget.draw(app, json, offline)
        drawNap(app, json, null)
        drawPets(app, json, null)
    }

    // ---------- data helpers ----------

    private data class Entry(val date: String, val o: JSONObject)

    private fun entries(data: JSONObject?): List<Entry> {
        val logs = data?.optJSONObject("logs") ?: return emptyList()
        val out = mutableListOf<Entry>()
        val keys = logs.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = logs.optJSONArray(k) ?: JSONArray()
            for (i in 0 until arr.length()) out.add(Entry(k, arr.getJSONObject(i)))
        }
        return out.sortedBy { it.o.optLong("ts") }
    }

    private fun currentNap(data: JSONObject?): Entry? =
        entries(data).lastOrNull { it.o.optString("type") == "nap" && it.o.isNull("end") &&
            System.currentTimeMillis() - it.o.optLong("ts") < 16 * 3600_000L }

    // ---------- nap widget ----------

    fun drawNap(ctx: Context, data: JSONObject?, saving: String?) {
        val ids = ids(ctx, NapWidget::class.java)
        if (ids.isEmpty()) return
        val v = RemoteViews(ctx.packageName, R.layout.widget_nap)
        v.setTextViewText(R.id.nap_title, "${Config.NAPPER.uppercase()}'S NAP")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        v.setOnClickPendingIntent(R.id.nap_button,
            PendingIntent.getBroadcast(ctx, 30, Intent(ctx, NapWidget::class.java).setAction(ACTION_NAP), flags))
        v.setOnClickPendingIntent(R.id.nap_root,
            PendingIntent.getActivity(ctx, 31, Intent(ctx, MainActivity::class.java), flags))

        val today = ymd(Date())
        val cur = currentNap(data)
        val todays = entries(data).filter { it.date == today && it.o.optString("type") == "nap" && !it.o.isNull("end") }
        when {
            Prefs.key(ctx).isEmpty() -> {
                v.setTextViewText(R.id.nap_status, "Set up the app")
                v.setTextViewText(R.id.nap_sub, "Open Delgado Family and enter the family code")
                v.setTextViewText(R.id.nap_button, "Open app")
                v.setOnClickPendingIntent(R.id.nap_button, PendingIntent.getActivity(ctx, 32, Intent(ctx, SettingsActivity::class.java), flags))
            }
            cur != null -> {
                val ts = cur.o.optLong("ts")
                v.setTextViewText(R.id.nap_status, "Napping 😴")
                v.setTextViewText(R.id.nap_sub, "Since ${time(ts)} · ${dur(System.currentTimeMillis() - ts)} so far")
                v.setTextViewText(R.id.nap_button, "Woke up")
            }
            todays.isNotEmpty() -> {
                val total = todays.sumOf { it.o.optLong("end") - it.o.optLong("ts") }
                val last = todays.last().o
                v.setTextViewText(R.id.nap_status, dur(total) + " today")
                v.setTextViewText(R.id.nap_sub, "Last nap ${time(last.optLong("ts"))}–${time(last.optLong("end"))}")
                v.setTextViewText(R.id.nap_button, "Start nap")
            }
            else -> {
                v.setTextViewText(R.id.nap_status, "No nap logged")
                v.setTextViewText(R.id.nap_sub, if (data == null) "Can't reach the dashboard" else "Tap when ${Config.NAPPER} goes down")
                v.setTextViewText(R.id.nap_button, "Start nap")
            }
        }
        if (saving != null) v.setTextViewText(R.id.nap_button, saving)
        AppWidgetManager.getInstance(ctx).updateAppWidget(ids, v)
    }

    fun toggleNap(ctx: Context) {
        val app = ctx.applicationContext
        drawNap(app, cachedState(app), "Saving…")
        val data = fetchState(app)
        if (data == null) { drawNap(app, cachedState(app), "No connection — tap again"); return }
        val cur = currentNap(data)
        val now = System.currentTimeMillis()
        val body = if (cur != null)
            JSONObject().put("op", "update").put("date", cur.date).put("id", cur.o.optString("id")).put("end", now)
        else
            JSONObject().put("op", "log").put("date", ymd(Date(now))).put("entry", JSONObject()
                .put("id", uid()).put("type", "nap").put("who", Prefs.owner(app)).put("ts", now).put("end", JSONObject.NULL))
        val ok = Api.post(app, body) != null
        refreshAll(app)
        if (!ok) drawNap(app, cachedState(app), "Didn't save — tap again")
    }

    // ---------- pets widget ----------

    fun drawPets(ctx: Context, data: JSONObject?, saving: String?) {
        val ids = ids(ctx, PetsWidget::class.java)
        if (ids.isEmpty()) return
        val v = RemoteViews(ctx.packageName, R.layout.widget_pets)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val today = ymd(Date())
        val todays = entries(data).filter { it.date == today }
        PETS.forEachIndexed { i, p ->
            val intent = Intent(ctx, PetsWidget::class.java).setAction(ACTION_PET).putExtra(EXTRA_TYPE, p.type)
            v.setOnClickPendingIntent(p.cell, PendingIntent.getBroadcast(ctx, 40 + i, intent, flags))
            val mine = todays.filter { it.o.optString("type") == p.type }
            val last = mine.lastOrNull()?.o
            val text = when {
                saving == p.type -> "Saving…"
                last != null -> "${time(last.optLong("ts"))} · ${last.optString("who")}" + if (mine.size > 1) " (×${mine.size})" else ""
                else -> "Not yet today"
            }
            v.setTextViewText(p.last, text)
        }
        v.setTextViewText(R.id.pets_title,
            if (Prefs.key(ctx).isEmpty()) "PETS · open the app to set up" else "PETS · logging as ${Prefs.owner(ctx).uppercase()}")
        AppWidgetManager.getInstance(ctx).updateAppWidget(ids, v)
    }

    fun logPet(ctx: Context, type: String) {
        val app = ctx.applicationContext
        if (PETS.none { it.type == type }) return
        drawPets(app, cachedState(app), type)
        val now = System.currentTimeMillis()
        val body = JSONObject().put("op", "log").put("date", ymd(Date(now))).put("entry", JSONObject()
            .put("id", uid()).put("type", type).put("who", Prefs.owner(app)).put("ts", now).put("end", JSONObject.NULL))
        var ok = false
        for (attempt in 1..2) { if (Api.post(app, body) != null) { ok = true; break }; Thread.sleep(2000) }
        refreshAll(app)
        if (!ok) {
            val v = RemoteViews(app.packageName, R.layout.widget_pets)
            PETS.firstOrNull { it.type == type }?.let { v.setTextViewText(it.last, "Didn't save — tap again") }
            AppWidgetManager.getInstance(app).partiallyUpdateAppWidget(ids(app, PetsWidget::class.java), v)
        }
    }

    private fun cachedState(ctx: Context): JSONObject? =
        Prefs.lastWidgetJson(ctx)?.let { runCatching { JSONObject(it) }.getOrNull() }
}

class NapWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        thread { try { Widgets.refreshAll(context) } finally { pending.finish() } }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_NAP) {
            val pending = goAsync()
            thread { try { Widgets.toggleNap(context) } finally { pending.finish() } }
            return
        }
        super.onReceive(context, intent)
    }
}

class PetsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        thread { try { Widgets.refreshAll(context) } finally { pending.finish() } }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_PET) {
            val type = intent.getStringExtra(Widgets.EXTRA_TYPE) ?: return
            val pending = goAsync()
            thread { try { Widgets.logPet(context, type) } finally { pending.finish() } }
            return
        }
        super.onReceive(context, intent)
    }
}
