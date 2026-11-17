package com.delgado.family

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** One doorbell clip saved in Google Drive (Doorbell > Clips > day). */
data class Clip(val id: String, val name: String, val size: Long, val ts: Long, val day: String) {
    fun toIntent(i: Intent): Intent = i.putExtra("id", id).putExtra("name", name).putExtra("size", size)
        .putExtra("ts", ts).putExtra("day", day)

    companion object {
        fun from(o: JSONObject) = Clip(o.getString("id"), o.optString("name"), o.optLong("size"), o.optLong("ts"), o.optString("day"))
        fun from(i: Intent) = Clip(i.getStringExtra("id") ?: "", i.getStringExtra("name") ?: "", i.getLongExtra("size", 0),
            i.getLongExtra("ts", 0), i.getStringExtra("day") ?: "")
    }
}

/** Downloads, caches, saves and deletes clips. The phone only ever talks to the family Google script. */
object ClipStore {
    private const val CHUNK = 6 * 1024 * 1024L           // must match CLIP_CHUNK in the Google script
    private const val CACHE_LIMIT = 600L * 1024 * 1024    // recently watched clips kept on the phone

    private fun clipDir(ctx: Context) = File(ctx.cacheDir, "clips").apply { mkdirs() }
    private fun thumbDir(ctx: Context) = File(ctx.cacheDir, "clip-thumbs").apply { mkdirs() }
    fun cached(ctx: Context, c: Clip) = File(clipDir(ctx), c.id + ".mp4")
    fun thumbFile(ctx: Context, id: String) = File(thumbDir(ctx), "$id.jpg")

    fun list(ctx: Context, before: String?): Pair<List<Clip>, String?>? {
        val body = JSONObject().put("action", "doorbell").put("op", "clips").put("n", 60)
        if (before != null) body.put("before", before)
        val r = Api.post(ctx, body, 60000) ?: return null
        if (r.has("error")) return null
        val arr = r.optJSONArray("clips") ?: JSONArray()
        val out = (0 until arr.length()).map { Clip.from(arr.getJSONObject(it)) }
        return out to (if (r.isNull("more")) null else r.optString("more"))
    }

    /** Fetches missing previews in groups of 8. Calls [done] after each group that brought something new. */
    fun loadThumbs(ctx: Context, clips: List<Clip>, stop: AtomicBoolean, done: () -> Unit) {
        val missing = clips.filter { !thumbFile(ctx, it.id).exists() }.map { it.id }
        for (group in missing.chunked(8)) {
            if (stop.get()) return
            val r = Api.post(ctx, JSONObject().put("action", "doorbell").put("op", "clipthumbs").put("ids", JSONArray(group)), 60000)
                ?: continue
            val t = r.optJSONObject("thumbs") ?: continue
            var any = false
            for (id in group) {
                val b64 = t.optString(id, "")
                if (b64.isNotEmpty()) {
                    try { thumbFile(ctx, id).writeBytes(Base64.decode(b64, Base64.DEFAULT)); any = true } catch (_: Exception) {}
                }
            }
            if (any) done()
        }
    }

    /**
     * Downloads a clip in pieces (3 at a time) into the phone's cache.
     * Returns the finished file, or null on failure. [progress] gets 0..100.
     */
    fun download(ctx: Context, c: Clip, cancel: AtomicBoolean, progress: (Int) -> Unit): File? {
        val out = cached(ctx, c)
        if (out.exists() && out.length() > 0 && (c.size <= 0 || out.length() == c.size)) { out.setLastModified(System.currentTimeMillis()); return out }
        val part = File(clipDir(ctx), c.id + ".part")
        part.delete()
        var total = c.size
        // The first piece also tells us the true size if the list didn't.
        val first = chunk(ctx, c.id, 0) ?: return null
        if (first.second > 0) total = first.second
        RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(total)
            raf.seek(0); raf.write(first.first)
            val got = AtomicLong(first.first.size.toLong())
            progress(pct(got.get(), total))
            val starts = generateSequence(CHUNK) { it + CHUNK }.takeWhile { it < total }.toList()
            val failed = AtomicBoolean(false)
            val pool = Executors.newFixedThreadPool(3)
            for (s in starts) pool.execute {
                if (cancel.get() || failed.get()) return@execute
                var piece: Pair<ByteArray, Long>? = null
                for (attempt in 1..3) { piece = chunk(ctx, c.id, s); if (piece != null || cancel.get()) break }
                if (piece == null) { failed.set(true); return@execute }
                synchronized(raf) { raf.seek(s); raf.write(piece.first) }
                progress(pct(got.addAndGet(piece.first.size.toLong()), total))
            }
            pool.shutdown()
            pool.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES)
            if (cancel.get() || failed.get() || got.get() < total) { part.delete(); return null }
        }
        out.delete()
        if (!part.renameTo(out)) return null
        trimCache(ctx)
        return out
    }

    private fun pct(got: Long, total: Long) = if (total <= 0) 0 else ((got * 100) / total).toInt().coerceIn(0, 100)

    private fun chunk(ctx: Context, id: String, start: Long): Pair<ByteArray, Long>? {
        val r = Api.post(ctx, JSONObject().put("action", "doorbell").put("op", "clipchunk").put("id", id).put("start", start), 90000)
            ?: return null
        if (r.has("error")) return null
        return try { Base64.decode(r.optString("data", ""), Base64.DEFAULT) to r.optLong("total", 0) } catch (e: Exception) { null }
    }

    private fun trimCache(ctx: Context) {
        val files = clipDir(ctx).listFiles()?.filter { it.name.endsWith(".mp4") }?.sortedByDescending { it.lastModified() } ?: return
        var used = 0L
        for (f in files) { used += f.length(); if (used > CACHE_LIMIT) f.delete() }
    }

    fun delete(ctx: Context, ids: List<String>): List<String>? {
        val r = Api.post(ctx, JSONObject().put("action", "doorbell").put("op", "clipdelete").put("ids", JSONArray(ids)), 60000)
            ?: return null
        val d = r.optJSONArray("deleted") ?: return null
        val gone = (0 until d.length()).map { d.getString(it) }
        for (id in gone) { File(clipDir(ctx), "$id.mp4").delete(); thumbFile(ctx, id).delete() }
        return gone
    }

    /** Copies a downloaded clip into the phone's Movies > Front Door folder (shows in Gallery / Photos). */
    fun saveToPhone(ctx: Context, src: File, c: Clip): Boolean {
        val name = "Front door " + SimpleDateFormat("yyyy-MM-dd h.mm.ss a", Locale.US).format(Date(c.ts)) + ".mp4"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Front Door")
                    put(MediaStore.Video.Media.DATE_TAKEN, c.ts)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } } ?: return false
                values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Front Door").apply { mkdirs() }
                val dest = File(dir, name)
                src.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf("video/mp4"), null)
            }
            true
        } catch (e: Exception) { false }
    }
}

/** Shared look: the dashboard's navy + cyan. */
private object Look {
    val bg = Color.parseColor("#080D1B")
    val panel = Color.parseColor("#131D36")
    val line = Color.parseColor("#26355A")
    val text = Color.parseColor("#E8EEFB")
    val muted = Color.parseColor("#8A98B8")
    val accent = Color.parseColor("#22D3EE")
    val danger = Color.parseColor("#F87171")

    fun box(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius; setStroke(2, stroke)
    }
}

private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Activity.pill(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
    text = label
    textSize = 14f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(color)
    gravity = Gravity.CENTER
    setPadding(dp(14), dp(10), dp(14), dp(10))
    val red = color == Look.danger
    background = Look.box(Color.parseColor(if (red) "#1AF87171" else "#1A22D3EE"),
        Color.parseColor(if (red) "#55F87171" else "#5522D3EE"), dp(12).toFloat())
    setOnClickListener { onClick() }
}

private fun timeLabel(ts: Long) = SimpleDateFormat("h:mm a", Locale.US).format(Date(ts))

private fun dayLabel(day: String): String {
    return try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day) ?: return day
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = Calendar.getInstance()
        val yest = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        when (day) {
            fmt.format(today.time) -> "Today"
            fmt.format(yest.time) -> "Yesterday"
            else -> SimpleDateFormat("EEEE, MMM d", Locale.US).format(d)
        }
    } catch (e: Exception) { day }
}

private fun sizeLabel(b: Long) = if (b <= 0) "" else if (b < 1024 * 1024) "${b / 1024} KB" else String.format(Locale.US, "%.1f MB", b / 1048576.0)

/** The list of doorbell clips, newest first. Tap to watch; long-press to pick several and delete them. */
class DoorbellActivity : Activity() {
    private val rows = mutableListOf<Any>()          // String = day header, Clip = a clip
    private val clips = mutableListOf<Clip>()
    private val picked = linkedSetOf<String>()
    private var more: String? = null
    private var loading = false
    private val stopThumbs = AtomicBoolean(false)
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var pickBar: LinearLayout
    private lateinit var pickCount: TextView
    private lateinit var footer: TextView
    private lateinit var adapter: BaseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Look.bg
        window.navigationBarColor = Look.bg

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Look.bg) }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(12), dp(8))
        }
        head.addView(TextView(this).apply {
            text = "🔔  Front door"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Look.text)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(pill("↻", Look.accent) { load(fresh = true) })
        root.addView(head)

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Look.muted)
            setPadding(dp(18), 0, dp(18), dp(8))
        }
        root.addView(status)

        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setPadding(dp(12), 0, dp(12), dp(12))
            clipToPadding = false
            setBackgroundColor(Look.bg)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        footer = TextView(this).apply {
            text = "Load older clips"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Look.accent)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
            setOnClickListener { if (!loading) load(fresh = false) }
            visibility = View.GONE
        }
        list.addFooterView(footer, null, false)
        adapter = ClipAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val c = rows.getOrNull(pos) as? Clip ?: return@setOnItemClickListener
            if (picked.isNotEmpty()) togglePick(c) else open(c)
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val c = rows.getOrNull(pos) as? Clip ?: return@setOnItemLongClickListener false
            togglePick(c); true
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        // Shown while picking clips to delete
        pickBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            setBackgroundColor(Look.panel)
            visibility = View.GONE
        }
        pickCount = TextView(this).apply { textSize = 15f; setTextColor(Look.text) }
        pickBar.addView(pickCount, LinearLayout.LayoutParams(0, -2, 1f))
        pickBar.addView(pill("Cancel", Look.muted) { picked.clear(); refreshPick() })
        pickBar.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        pickBar.addView(pill("Delete", Look.danger) { confirmDelete(picked.toList()) })
        root.addView(pickBar)

        setContentView(root)

        if (Prefs.key(this).isEmpty()) {
            status.text = "Add the family code in Settings first."
            startActivity(Intent(this, SettingsActivity::class.java))
        } else load(fresh = true)
    }

    override fun onResume() {
        super.onResume()
        // Came back after deleting a clip from the player: drop it from the list.
        val gone = getSharedPreferences("delgado", MODE_PRIVATE).getString("clip-just-deleted", null)
        if (gone != null) {
            getSharedPreferences("delgado", MODE_PRIVATE).edit().remove("clip-just-deleted").apply()
            removeClips(listOf(gone))
        } else if (clips.isEmpty() && !loading && Prefs.key(this).isNotEmpty()) load(fresh = true)
    }

    override fun onDestroy() {
        stopThumbs.set(true)
        super.onDestroy()
    }

    private fun load(fresh: Boolean) {
        if (loading) return
        loading = true
        status.text = if (fresh) "Loading clips…" else "Loading older clips…"
        footer.text = "Loading…"
        val before = if (fresh) null else more
        thread {
            val r = ClipStore.list(this, before)
            runOnUiThread {
                loading = false
                footer.text = "Load older clips"
                if (r == null) {
                    status.text = "Couldn't load clips. Check your connection and the family code, then tap ↻."
                    return@runOnUiThread
                }
                if (fresh) { clips.clear(); picked.clear(); refreshPick() }
                val have = clips.map { it.id }.toHashSet()
                clips.addAll(r.first.filter { it.id !in have })
                more = r.second
                rebuild()
                startThumbs(r.first)
            }
        }
    }

    private fun startThumbs(batch: List<Clip>) {
        thread {
            ClipStore.loadThumbs(this, batch, stopThumbs) { runOnUiThread { adapter.notifyDataSetChanged() } }
        }
    }

    private fun rebuild() {
        rows.clear()
        var day = ""
        for (c in clips) {
            if (c.day != day) { day = c.day; rows.add(day) }
            rows.add(c)
        }
        status.text = when {
            clips.isEmpty() -> "No clips yet. New ones show up here after the TV saves them to Google Drive."
            else -> "${clips.size} clips · tap to watch · press and hold to pick several"
        }
        footer.visibility = if (more != null) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun open(c: Clip) = startActivity(c.toIntent(Intent(this, ClipActivity::class.java)))

    private fun togglePick(c: Clip) {
        if (!picked.remove(c.id)) picked.add(c.id)
        refreshPick()
    }

    private fun refreshPick() {
        pickBar.visibility = if (picked.isEmpty()) View.GONE else View.VISIBLE
        pickCount.text = if (picked.size == 1) "1 clip picked" else "${picked.size} clips picked"
        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(ids: List<String>) {
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(if (ids.size == 1) "Delete this clip?" else "Delete ${ids.size} clips?")
            .setMessage("They're removed for everyone. If it was a mistake, they stay in Google Drive's trash for 30 days.")
            .setPositiveButton("Delete") { _, _ ->
                status.text = "Deleting…"
                thread {
                    val gone = ClipStore.delete(this, ids)
                    runOnUiThread {
                        if (gone == null) { status.text = "Couldn't delete. Check your connection and try again."; return@runOnUiThread }
                        picked.clear(); refreshPick()
                        removeClips(gone)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeClips(ids: List<String>) {
        val set = ids.toHashSet()
        clips.removeAll { it.id in set }
        rebuild()
    }

    private fun thumb(id: String): Bitmap? {
        thumbs.get(id)?.let { return it }
        val f = ClipStore.thumbFile(this, id)
        if (!f.exists()) return null
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        thumbs.put(id, b)
        return b
    }

    private inner class ClipAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is String) 0 else 1
        override fun isEnabled(p: Int) = rows[p] is Clip

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val item = rows[p]
            if (item is String) {
                val tv = (convert as? TextView) ?: TextView(this@DoorbellActivity).apply {
                    textSize = 12f
                    letterSpacing = 0.08f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Look.accent)
                    setPadding(dp(6), dp(16), dp(6), dp(6))
                }
                tv.text = dayLabel(item).uppercase(Locale.US)
                return tv
            }
            val c = item as Clip
            val row = (convert as? LinearLayout) ?: makeRow()
            val img = row.getChildAt(0) as ImageView
            val texts = row.getChildAt(1) as LinearLayout
            val bm = thumb(c.id)
            if (bm != null) img.setImageBitmap(bm) else img.setImageDrawable(null)
            (texts.getChildAt(0) as TextView).text = timeLabel(c.ts)
            val saved = ClipStore.cached(this@DoorbellActivity, c).exists()
            (texts.getChildAt(1) as TextView).text = listOf(sizeLabel(c.size), if (saved) "ready offline" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            val on = c.id in picked
            row.background = Look.box(if (on) Color.parseColor("#2622D3EE") else Look.panel, if (on) Look.accent else Look.line, dp(14).toFloat())
            (row.getChildAt(2) as TextView).text = if (picked.isEmpty()) "▶" else if (on) "☑" else "☐"
            return row
        }

        private fun makeRow(): LinearLayout = LinearLayout(this@DoorbellActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(14), dp(8))
            layoutParams = android.widget.AbsListView.LayoutParams(-1, -2)
            addView(ImageView(this@DoorbellActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Look.box(Color.parseColor("#0B1226"), Look.line, dp(10).toFloat())
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(112), dp(64)))
            addView(LinearLayout(this@DoorbellActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(TextView(this@DoorbellActivity).apply { textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(Look.text) })
                addView(TextView(this@DoorbellActivity).apply { textSize = 12f; setTextColor(Look.muted) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@DoorbellActivity).apply { textSize = 18f; setTextColor(Look.accent) })
        }
    }
}

/** Plays one clip, with Save to phone and Delete. */
class ClipActivity : Activity() {
    private lateinit var clip: Clip
    private lateinit var video: VideoView
    private lateinit var progress: ProgressBar
    private lateinit var note: TextView
    private val cancel = AtomicBoolean(false)
    private var file: File? = null
    private var pendingSave = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Look.bg
        clip = Clip.from(intent)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Look.bg) }

        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        stage.addView(video, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Look.accent)
        }
        stage.addView(progress, FrameLayout.LayoutParams(dp(220), -2, Gravity.CENTER))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        info.addView(TextView(this).apply {
            text = dayLabel(clip.day) + " · " + timeLabel(clip.ts)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Look.text)
        })
        note = TextView(this).apply { textSize = 13f; setTextColor(Look.muted); setPadding(0, dp(4), 0, dp(12)) }
        info.addView(note)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pill("⬇  Save to phone", Look.accent) { save() }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        buttons.addView(pill("🗑  Delete", Look.danger) { confirmDelete() }, LinearLayout.LayoutParams(0, -2, 1f))
        info.addView(buttons)
        root.addView(info)
        setContentView(root)

        fetch()
    }

    private fun fetch() {
        note.text = "Loading video… " + sizeLabel(clip.size)
        progress.visibility = View.VISIBLE
        thread {
            val f = ClipStore.download(this, clip, cancel) { p -> runOnUiThread { progress.progress = p; note.text = "Loading video… $p%" } }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                progress.visibility = View.GONE
                if (f == null) {
                    note.text = if (cancel.get()) "" else "Couldn't load this clip. Check your connection and open it again."
                    return@runOnUiThread
                }
                file = f
                note.text = sizeLabel(f.length()) + " · tap the video for controls"
                play(f)
                if (pendingSave) { pendingSave = false; save() }
            }
        }
    }

    private fun play(f: File) {
        val mc = MediaController(this)
        mc.setAnchorView(video)
        video.setMediaController(mc)
        video.setVideoPath(f.path)
        video.setOnPreparedListener { it.isLooping = false; video.start() }
        video.setOnErrorListener { _, _, _ -> note.text = "This phone can't play this clip's format. Save it and open it in Gallery."; true }
    }

    private fun save() {
        val f = file
        if (f == null) { pendingSave = true; note.text = "It'll save as soon as it finishes loading…"; return }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 41)
            return
        }
        thread {
            val ok = ClipStore.saveToPhone(this, f, clip)
            runOnUiThread { note.text = if (ok) "Saved to your phone: Gallery › Movies › Front Door" else "Couldn't save to the phone." }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == 41 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) save()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete this clip?")
            .setMessage("It's removed for everyone. If it was a mistake, it stays in Google Drive's trash for 30 days.")
            .setPositiveButton("Delete") { _, _ ->
                note.text = "Deleting…"
                thread {
                    val gone = ClipStore.delete(this, listOf(clip.id))
                    runOnUiThread {
                        if (gone.isNullOrEmpty()) { note.text = "Couldn't delete. Check your connection and try again."; return@runOnUiThread }
                        getSharedPreferences("delgado", MODE_PRIVATE).edit().putString("clip-just-deleted", clip.id).apply()
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (video.isPlaying) video.pause()
    }

    override fun onDestroy() {
        cancel.set(true)
        super.onDestroy()
    }
}
