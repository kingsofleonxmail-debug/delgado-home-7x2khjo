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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Home-screen widget: school status, Mom's shift, Sebastian's nap, bins and shopping. */
class DashboardWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        thread {
            try { refreshNow(context) } finally { pending.finish() }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val pending = goAsync()
            thread { try { refreshNow(context) } finally { pending.finish() } }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_REFRESH = "com.delgado.family.REFRESH"

        fun requestRefresh(ctx: Context) {
            ctx.sendBroadcast(Intent(ctx, DashboardWidget::class.java).setAction(ACTION_REFRESH))
        }

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, DashboardWidget::class.java))

        /** Fetch fresh data and redraw every widget (all three kinds). Blocking — call from a background thread. */
        fun refreshNow(ctx: Context) = Widgets.refreshAll(ctx)

        fun draw(ctx: Context, data: JSONObject?, offline: Boolean) {
            if (ids(ctx).isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_dashboard)
            val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            v.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), immutable))
            v.setOnClickPendingIntent(R.id.w_refresh,
                PendingIntent.getBroadcast(ctx, 2, Intent(ctx, DashboardWidget::class.java).setAction(ACTION_REFRESH), immutable))

            if (Prefs.key(ctx).isEmpty()) {
                v.setTextViewText(R.id.w_school, "Open the app and enter the family code")
                v.setTextViewText(R.id.w_mom, ""); v.setTextViewText(R.id.w_nap, ""); v.setTextViewText(R.id.w_more, "")
            } else if (data == null) {
                v.setTextViewText(R.id.w_school, "Can't reach the family dashboard")
                v.setTextViewText(R.id.w_mom, "Tap ↻ to try again"); v.setTextViewText(R.id.w_nap, ""); v.setTextViewText(R.id.w_more, "")
            } else {
                v.setTextViewText(R.id.w_school, schoolLine(data))
                v.setTextViewText(R.id.w_mom, momLine(data))
                v.setTextViewText(R.id.w_nap, napLine(data))
                v.setTextViewText(R.id.w_more, moreLine(data))
            }
            v.setTextViewText(R.id.w_updated, (if (offline) "offline · " else "") + time(System.currentTimeMillis()))
            AppWidgetManager.getInstance(ctx).updateAppWidget(ids(ctx), v)
        }

        // ---------- the four lines ----------

        private fun schoolLine(d: JSONObject): String {
            val checks = d.optJSONObject("checks") ?: JSONObject()
            return "🏫 " + Config.KIDS.mapIndexed { i, name ->
                val row = checks.optJSONArray(i.toString())
                val left = stamp(row, 0); val arrived = stamp(row, 1)
                when {
                    arrived != null -> "$name at school" + if (arrived > 0) " ${time(arrived)}" else ""
                    left != null -> "$name on the way"
                    else -> "$name home"
                }
            }.joinToString(" · ")
        }

        private fun momLine(d: JSONObject): String {
            val today = ymd(Date())
            val shifts = d.optJSONArray("shifts") ?: JSONArray()
            val todays = (0 until shifts.length()).map { shifts.getJSONObject(it) }.filter { it.optString("date") == today }
            val work = d.optJSONArray("work") ?: JSONArray()
            val last = if (work.length() > 0) work.getJSONObject(work.length() - 1) else null
            return "🏥 " + when {
                last != null && last.optString("event") == "arrive" ->
                    "${Config.MOM} at work · ${last.optString("loc")} since ${time(last.optLong("ts"))}"
                last != null -> "${Config.MOM} left ${last.optString("loc")} at ${time(last.optLong("ts"))}"
                todays.isNotEmpty() -> todays[0].let { "${Config.MOM} works ${hm(it.optString("start"))}–${hm(it.optString("end"))} · ${it.optString("loc")}" }
                else -> {
                    val next = (0 until shifts.length()).map { shifts.getJSONObject(it) }.firstOrNull { it.optString("date") > today }
                    "${Config.MOM} off today" + (next?.let { " · next ${dayName(it.optString("date"))} ${hm(it.optString("start"))} ${it.optString("loc")}" } ?: "")
                }
            }
        }

        private fun napLine(d: JSONObject): String {
            val logs = d.optJSONObject("logs")?.optJSONArray(ymd(Date())) ?: JSONArray()
            val naps = (0 until logs.length()).map { logs.getJSONObject(it) }.filter { it.optString("type") == "nap" }
            val current = naps.lastOrNull { it.isNull("end") }
            if (current != null) return "😴 ${Config.NAPPER} napping since ${time(current.optLong("ts"))}"
            val done = naps.filter { !it.isNull("end") }
            if (done.isEmpty()) return "😴 No nap logged for ${Config.NAPPER}"
            val mins = done.sumOf { (it.optLong("end") - it.optLong("ts")) / 60000 }
            return "😴 ${Config.NAPPER} napped ${if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"} today"
        }

        private fun moreLine(d: JSONObject): String {
            val cal = Calendar.getInstance()
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val tomorrow = if (dow == Calendar.SATURDAY) Calendar.SUNDAY else dow + 1
            val bins = Config.BINS.firstOrNull { it.first == dow && hour < 12 }?.let { "🗑 Bins today: ${it.second}" }
                ?: Config.BINS.firstOrNull { it.first == tomorrow }?.let { "🗑 Tonight: put out ${it.second}" }
            val shop = d.optJSONArray("shop") ?: JSONArray()
            val toBuy = (0 until shop.length()).count { !shop.getJSONObject(it).optBoolean("done") }
            val shopText = if (toBuy > 0) "🛒 $toBuy to buy" else null
            val today = ymd(Date())
            val cds = d.optJSONArray("countdowns") ?: JSONArray()
            val next = (0 until cds.length()).map { cds.getJSONObject(it) }
                .filter { it.optString("date") >= today }.minByOrNull { it.optString("date") }
            val cdText = next?.let {
                val days = runCatching {
                    val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    ((f.parse(it.optString("date"))!!.time - f.parse(today)!!.time) / 86_400_000L + 0.5).toLong()
                }.getOrDefault(-1L)
                when { days == 0L -> "⭐ ${it.optString("name")} is today!"; days > 0 -> "⭐ $days days to ${it.optString("name")}"; else -> null }
            }
            return listOfNotNull(bins, shopText, cdText).joinToString(" · ").ifEmpty { "🛒 Shopping list is empty" }
        }

        // ---------- helpers ----------

        /** A tick is stored as a time (ms) or true; returns 0 for an untimed tick, null when not ticked. */
        private fun stamp(row: JSONArray?, i: Int): Long? {
            if (row == null || i >= row.length() || row.isNull(i)) return null
            return when (val x = row.get(i)) {
                is Number -> x.toLong()
                is Boolean -> if (x) 0L else null
                else -> null
            }
        }

        private fun ymd(d: Date) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
        private fun time(ms: Long) = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))
        private fun dayName(date: String): String = runCatching {
            SimpleDateFormat("EEE", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!)
        }.getOrDefault(date)
        private fun hm(s: String): String {
            val p = s.split(":"); if (p.size != 2) return s
            val h = p[0].toIntOrNull() ?: return s
            return "${if (h % 12 == 0) 12 else h % 12}:${p[1]}${if (h < 12) "a" else "p"}"
        }
    }
}
