package tw.umaya.tracker.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.umaya.tracker.data.Prefs
import tw.umaya.tracker.location.gpxToKml
import tw.umaya.tracker.location.recordedTracks
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// ---------- data ----------

class TrkPt(val lat: Double, val lon: Double, val ele: Double?, val t: Long?)

/** One recorded trail on this phone, parsed with its statistics. */
class TrackRecord(val file: File, val title: String, val segments: List<List<TrkPt>>, val waypoints: Int, stampTime: Long?) {
    val points = segments.flatten()
    /** cumulative distance (m) per point; segments are not joined across their gaps */
    val dist = DoubleArray(points.size)
    var ascent = 0.0
        private set
    var descent = 0.0
        private set
    var movingMs = 0L
        private set
    val start: Long = points.firstNotNullOfOrNull { it.t } ?: stampTime ?: file.lastModified()
    val end: Long? = points.lastOrNull { it.t != null }?.t
    val maxEle = points.mapNotNull { it.ele }.maxOrNull()
    val minEle = points.mapNotNull { it.ele }.minOrNull()
    val distance get() = dist.lastOrNull() ?: 0.0
    /** has timestamps = an actual recording (goes in the calendar); none = a planned route */
    val timed = points.any { it.t != null }
    val isKml get() = file.extension.equals("kml", true)

    init {
        val r = FloatArray(1)
        var k = 0
        var ref: Double? = null
        for (seg in segments) {
            for ((j, p) in seg.withIndex()) {
                if (k > 0) dist[k] = dist[k - 1]
                if (j > 0) {
                    val q = seg[j - 1]
                    Location.distanceBetween(q.lat, q.lon, p.lat, p.lon, r)
                    dist[k] = dist[k] + r[0]
                    if (p.t != null && q.t != null) {
                        val dt = p.t - q.t
                        if (dt in 1..600_000 && r[0] / (dt / 1000.0) >= 0.2) movingMs += dt // walking, not resting or a gap
                    }
                }
                p.ele?.let { e ->
                    val rf = ref
                    if (rf == null) ref = e
                    else if (e - rf >= 8) { ascent += e - rf; ref = e }
                    else if (rf - e >= 8) { descent += rf - e; ref = e }
                }
                k++
            }
        }
    }

    companion object {
        private val trkpt = Regex("<trkpt\\b([^>]*)>(.*?)</trkpt>", RegexOption.DOT_MATCHES_ALL)
        private val trkseg = Regex("<trkseg>(.*?)</trkseg>", RegexOption.DOT_MATCHES_ALL)
        private val latRe = Regex("lat=\"([^\"]+)\""); private val lonRe = Regex("lon=\"([^\"]+)\"")
        private val eleRe = Regex("<ele>([^<]+)</ele>"); private val timeRe = Regex("<time>([^<]+)</time>")
        /** 行程名稱-yyyyMMdd-HHmmss(-n) */
        val stampName = Regex("^(.*)-(\\d{8})-(\\d{6})(-\\d+)?$")
        private val isoFormats = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX")

        private fun parseTime(s: String): Long? {
            for (f in isoFormats) runCatching {
                return SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.trim())!!.time
            }
            return null
        }

        fun load(file: File): TrackRecord {
            val text = file.readText()
            if (text.contains("<kml", ignoreCase = true)) {
                val parsed = parseRoute(text)
                val segs = parsed.segments.map { seg -> seg.map { TrkPt(it.latitude, it.longitude, it.altitude.takeIf { a -> a != 0.0 }, null) } }
                return TrackRecord(file, file.nameWithoutExtension, segs, parsed.labels.size, null)
            }
            val segs = trkseg.findAll(text).map { s ->
                trkpt.findAll(s.groupValues[1]).mapNotNull { m ->
                    val lat = latRe.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
                    val lon = lonRe.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
                    val body = m.groupValues[2]
                    TrkPt(lat, lon, eleRe.find(body)?.groupValues?.get(1)?.trim()?.toDoubleOrNull(), timeRe.find(body)?.groupValues?.get(1)?.let(::parseTime))
                }.toList()
            }.filter { it.isNotEmpty() }.toList()
            val name = file.nameWithoutExtension
            val m = stampName.find(name)
            val stamp = m?.let { runCatching { SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(it.groupValues[2] + it.groupValues[3])!!.time }.getOrNull() }
            return TrackRecord(file, m?.groupValues?.get(1) ?: name, segs, Regex("<wpt\\b").findAll(text).count(), stamp)
        }
    }
}

private fun km(m: Double) = if (m < 1000) "${m.toInt()} m" else "%.2f km".format(m / 1000)
private fun dur(ms: Long): String { val min = ms / 60_000; return if (min < 60) "${min} 分" else "${min / 60} 小時 ${min % 60} 分" }
private val WEEK = arrayOf("日", "一", "二", "三", "四", "五", "六")
private fun dayLabel(t: Long): String { val c = Calendar.getInstance().apply { timeInMillis = t }; return "${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)}（${WEEK[c.get(Calendar.DAY_OF_WEEK) - 1]}）" }
private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.US).format(t)
private fun dayKey(t: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(t)
private fun monthKey(t: Long) = SimpleDateFormat("yyyyMM", Locale.US).format(t)

/** 外部記錄: GPX/KML imported into 記錄管理 (others' recordings, or planned routes), kept apart from the phone's own. */
fun importedDir(context: Context) = File(context.getExternalFilesDir(null), "imported").apply { mkdirs() }

private fun importedTracks(context: Context): List<File> =
    importedDir(context).listFiles { f -> f.isFile && (f.extension.equals("gpx", true) || f.extension.equals("kml", true)) }.orEmpty().toList()

/** Copies picked files into [importedDir]; returns how many were GPX/KML. */
private fun importFiles(context: Context, uris: List<Uri>): Int {
    var n = 0
    for (uri in uris) runCatching {
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "track.gpx"
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        if (!(text.contains("<gpx", true) || text.contains("<kml", true))) return@runCatching
        val ext = if (text.contains("<kml", true)) "kml" else "gpx"
        val base = name.substringBeforeLast('.').replace(Regex("[\\/:*?\"<>|]"), "_").ifBlank { "track" }
        var f = File(importedDir(context), "$base.$ext"); var k = 2
        while (f.exists()) f = File(importedDir(context), "$base-${k++}.$ext")
        f.writeText(text); n++
    }
    return n
}

/** 我的最愛: starred records, keyed by folder/file name (survives the list reloading; updated on rename). */
private fun favKey(f: File) = f.parentFile?.name + "/" + f.name
private fun loadFavs(context: Context): Set<String> = context.getSharedPreferences("records", Context.MODE_PRIVATE).getStringSet("fav", emptySet()).orEmpty().toSet()
private fun saveFavs(context: Context, favs: Set<String>) = context.getSharedPreferences("records", Context.MODE_PRIVATE).edit().putStringSet("fav", favs).apply()

// ---------- activity ----------

/**
 * 記錄管理: every trail recorded on this phone, as a list (by month) or a calendar. A record opens
 * to its track, statistics and an elevation profile whose points can be tapped for their data, and
 * can be shown on the map, replayed in 3D, shared, exported (GPX/KML), renamed or deleted.
 * 「顯示在地圖上」 hands the file back to the map screen as an activity result.
 */
class RecordsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PanelTheme { RecordsScreen(onClose = { finish() }, onShowOnMap = { f -> setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SHOW, f.absolutePath)); finish() }) } }
    }

    companion object {
        const val EXTRA_SHOW = "show"
        fun intent(context: Context) = Intent(context, RecordsActivity::class.java)
    }
}

@Composable
private fun RecordsScreen(onClose: () -> Unit, onShowOnMap: (File) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<TrackRecord>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var calendarMode by remember { mutableStateOf(false) }
    var external by remember { mutableStateOf(false) }
    var favs by remember { mutableStateOf(loadFavs(context)) }
    var favOnly by remember { mutableStateOf(false) }
    fun toggleFav(f: File) { val k = favKey(f); favs = if (k in favs) favs - k else favs + k; saveFavs(context, favs) }
    var open by remember { mutableStateOf<TrackRecord?>(null) }
    val recordingPath = prefs.gpxFilePath
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) { importFiles(context, uris) }
            Toast.makeText(context, if (n > 0) "已匯入 $n 個檔案" else "沒有可匯入的 GPX／KML", Toast.LENGTH_SHORT).show()
            reload++
        }
    }

    LaunchedEffect(reload, external) {
        records = null
        records = withContext(Dispatchers.IO) {
            (if (external) importedTracks(context) else recordedTracks(context))
                .mapNotNull { runCatching { TrackRecord.load(it) }.getOrNull() }.sortedByDescending { it.start }
        }
    }
    BackHandler(enabled = open != null) { open = null }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF15181C)).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(Color(0xFF202020)).padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (open != null) Text("‹", fontSize = 24.sp, modifier = Modifier.clickable { open = null }.padding(end = 10.dp))
            Text(open?.title ?: "記錄管理", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            if (open == null) {
                Seg("列表", !calendarMode) { calendarMode = false }
                Spacer(Modifier.width(4.dp))
                Seg("日曆", calendarMode) { calendarMode = true }
            }
            Text("✕", fontSize = 18.sp, modifier = Modifier.clickable(onClick = onClose).padding(start = 12.dp))
        }
        if (open == null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Seg("內部・我的記錄", !external) { external = false }
            Spacer(Modifier.width(4.dp))
            Seg("外部・匯入", external) { external = true }
            Spacer(Modifier.weight(1f))
            Seg("★ 最愛", favOnly) { favOnly = !favOnly }
            if (external) { Spacer(Modifier.width(4.dp)); Seg("＋ 匯入", false) { picker.launch(arrayOf("*/*")) } }
        }
        val list = records?.let { all -> if (favOnly) all.filter { favKey(it.file) in favs } else all }
        val isFav = { r: TrackRecord -> favKey(r.file) in favs }
        when {
            list == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            open != null -> RecordDetail(
                open!!, recording = !external && open!!.file.absolutePath == recordingPath, onShowOnMap = onShowOnMap,
                onChanged = { gone -> if (gone) open = null; reload++ },
                onRenamed = { f -> scope.launch { open = withContext(Dispatchers.IO) { TrackRecord.load(f) }; reload++ } },
                fav = isFav(open!!), onFav = { toggleFav(open!!.file) },
                onRenameFav = { old, new -> if (favKey(old) in favs) { favs = favs - favKey(old) + favKey(new); saveFavs(context, favs) } },
            )
            list.isEmpty() -> Text(
                if (favOnly) "沒有加入最愛的記錄。打開一筆記錄，按「☆ 加入最愛」。" else if (external) "還沒有外部記錄。按「＋ 匯入」加入別人記錄的 GPX 或規劃好的路線（GPX／KML）。" else "還沒有記錄。開始記錄軌跡後，每段記錄都會出現在這裡。",
                color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp),
            )
            // the calendar holds actual recordings only — a planned route has no date
            calendarMode -> CalendarView(list.filter { it.timed }, recordingPath, isFav) { open = it }
            else -> RecordList(list, recordingPath, monthHeaders = true, isFav = isFav) { open = it }
        }
    }
}

@Composable
private fun Seg(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, color = if (on) Color.Black else Color.White,
        modifier = Modifier.background(if (on) Color(0xFF64B5F6) else Color(0x22FFFFFF), RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun RecordList(list: List<TrackRecord>, recordingPath: String?, monthHeaders: Boolean, isFav: (TrackRecord) -> Boolean, onOpen: (TrackRecord) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        var lastMonth = ""
        list.forEach { r ->
            val mk = monthKey(r.start)
            if (monthHeaders && mk != lastMonth) {
                lastMonth = mk
                item(key = "m$mk") { Text("${mk.take(4)} 年 ${mk.drop(4).toInt()} 月", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
            }
            item(key = r.file.absolutePath) { RecordRow(r, r.file.absolutePath == recordingPath, isFav(r)) { onOpen(r) } }
        }
    }
}

@Composable
private fun RecordRow(r: TrackRecord, recording: Boolean, fav: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Color(0xFF23272C), RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(10.dp),
    ) {
        TrackThumb(r, Modifier.size(54.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (fav) Text(" ★", color = Color(0xFFFFCA28), fontSize = 14.sp)
                if (recording) Text(" ● 記錄中", color = Color(0xFFFF5252), fontSize = 11.sp)
            }
            Text(if (r.timed) dayLabel(r.start) + " " + hm(r.start) + (r.end?.let { "–" + (if (dayKey(it) != dayKey(r.start)) dayLabel(it) + " " else "") + hm(it) } ?: "") else "路線（無時間資料）・" + r.file.extension.uppercase(),
                color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
            Text(
                km(r.distance) + "・↑${r.ascent.toInt()} ↓${r.descent.toInt()} m" + (r.end?.let { "・" + dur(it - r.start) } ?: ""),
                color = Color(0xFF90CAF9), fontSize = 12.sp,
            )
        }
    }
}

/** The trail's shape (no map), north up. */
@Composable
private fun TrackThumb(r: TrackRecord, modifier: Modifier, selected: Int = -1) {
    Canvas(modifier.background(Color(0xFF2B3036), RoundedCornerShape(8.dp))) {
        val pts = r.points
        if (pts.size < 2) return@Canvas
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        val kx = Math.cos(Math.toRadians((minLat + maxLat) / 2))
        val w = ((maxLon - minLon) * kx).coerceAtLeast(1e-6); val h = (maxLat - minLat).coerceAtLeast(1e-6)
        val pad = size.minDimension * 0.08f
        val s = minOf((size.width - 2 * pad) / w, (size.height - 2 * pad) / h)
        val ox = (size.width - w * s) / 2; val oy = (size.height - h * s) / 2
        fun o(p: TrkPt) = Offset((ox + (p.lon - minLon) * kx * s).toFloat(), (oy + (maxLat - p.lat) * s).toFloat())
        val stroke = (size.minDimension / 60f).coerceIn(1.5f, 4f)
        for (seg in r.segments) {
            if (seg.size < 2) continue
            val path = Path().apply { moveTo(o(seg[0]).x, o(seg[0]).y); for (p in seg.drop(1)) lineTo(o(p).x, o(p).y) }
            drawPath(path, Color(0xFFFF7043), style = Stroke(width = stroke))
        }
        drawCircle(Color(0xFF66BB6A), stroke * 1.6f, o(pts.first()))
        drawCircle(Color(0xFFEF5350), stroke * 1.6f, o(pts.last()))
        if (selected in pts.indices) { drawCircle(Color.White, stroke * 3f, o(pts[selected])); drawCircle(Color(0xFFFFEB3B), stroke * 2.2f, o(pts[selected])) }
    }
}

@Composable
private fun CalendarView(list: List<TrackRecord>, recordingPath: String?, isFav: (TrackRecord) -> Boolean, onOpen: (TrackRecord) -> Unit) {
    val cal = remember { Calendar.getInstance() }
    var ym by remember { mutableIntStateOf(cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)) }
    var day by remember { mutableStateOf<String?>(null) }
    val byDay = remember(list) { list.groupBy { dayKey(it.start) } }
    val year = ym / 12; val month = ym % 12
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("‹", fontSize = 24.sp, modifier = Modifier.clickable { ym--; day = null }.padding(horizontal = 16.dp))
            Text("$year 年 ${month + 1} 月", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text("›", fontSize = 24.sp, modifier = Modifier.clickable { ym++; day = null }.padding(horizontal = 16.dp))
        }
        Row { WEEK.forEach { Text(it, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) } }
        val first = Calendar.getInstance().apply { clear(); set(year, month, 1) }
        val lead = first.get(Calendar.DAY_OF_WEEK) - 1
        val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = dayKey(System.currentTimeMillis())
        for (week in 0 until (lead + days + 6) / 7) {
            Row {
                for (col in 0 until 7) {
                    val d = week * 7 + col - lead + 1
                    Box(modifier = Modifier.weight(1f).aspectRatio(1.1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (d in 1..days) {
                            val key = "%04d%02d%02d".format(year, month + 1, d)
                            val n = byDay[key]?.size ?: 0
                            val on = key == day
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                                    .background(if (on) Color(0xFF64B5F6) else if (n > 0) Color(0x3364B5F6) else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable(enabled = n > 0) { day = if (on) null else key },
                            ) {
                                Text("$d", fontSize = 14.sp, color = if (on) Color.Black else Color.White, fontWeight = if (key == today) FontWeight.Bold else FontWeight.Normal)
                                if (n > 0) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    repeat(minOf(n, 3)) { Box(Modifier.size(5.dp).background(if (on) Color.Black else Color(0xFFFF7043), CircleShape)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        val mk = "%04d%02d".format(year, month + 1)
        val shown = day?.let { byDay[it].orEmpty() } ?: list.filter { monthKey(it.start) == mk }
        Text(
            if (day != null) "${day!!.substring(4, 6).toInt()}/${day!!.substring(6).toInt()} 的記錄" else "本月 ${shown.size} 筆・" + km(shown.sumOf { it.distance }) + "・↑${shown.sumOf { it.ascent }.toInt()} m",
            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        RecordList(shown, recordingPath, monthHeaders = false, isFav = isFav, onOpen = onOpen)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordDetail(
    r: TrackRecord, recording: Boolean, onShowOnMap: (File) -> Unit, onChanged: (gone: Boolean) -> Unit, onRenamed: (File) -> Unit,
    fav: Boolean, onFav: () -> Unit, onRenameFav: (old: File, new: File) -> Unit,
) {
    val context = LocalContext.current
    var sel by remember(r) { mutableIntStateOf(-1) }
    var askDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    // GPX 編輯器 → reload this record, or open the 「（編輯）」 copy it saved
    val editor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringExtra(GpxEditorActivity.EXTRA_SAVED)?.let { onRenamed(File(it)) }
    }
    val exportGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri -> uri?.let { writeTo(context, it, r.file.readText()) } }
    val exportKml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { uri ->
        uri?.let { writeTo(context, it, if (r.isKml) r.file.readText() else gpxToKml(r.file.readText(), r.title)) }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        TrackThumb(r, Modifier.fillMaxWidth().height(230.dp), selected = sel)
        Spacer(Modifier.height(8.dp))
        if (!r.timed) Text("路線（無時間資料）", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        else Text(dayLabel(r.start) + " " + hm(r.start) + (r.end?.let { "–" + (if (dayKey(it) != dayKey(r.start)) dayLabel(it) + " " else "") + hm(it) } ?: "") + if (recording) "・● 記錄中" else "",
            color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        val total = r.end?.let { it - r.start }
        val stats = listOf(
            "距離" to km(r.distance),
            "總時間" to (total?.let(::dur) ?: "—"),
            "移動時間" to if (r.movingMs > 0) dur(r.movingMs) else "—",
            "平均速度" to if (r.movingMs > 0) "%.1f km/h".format(r.distance / 1000 / (r.movingMs / 3_600_000.0)) else "—",
            "爬升" to "${r.ascent.toInt()} m",
            "下降" to "${r.descent.toInt()} m",
            "最高" to (r.maxEle?.let { "${it.toInt()} m" } ?: "—"),
            "最低" to (r.minEle?.let { "${it.toInt()} m" } ?: "—"),
            "記錄點" to "${r.points.size}",
            "航點" to "${r.waypoints}",
        )
        stats.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { (k, v) ->
                    Row(modifier = Modifier.weight(1f).padding(vertical = 3.dp)) {
                        Text(k, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.width(64.dp))
                        Text(v, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ProfileChart(r, sel) { sel = it }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Action("star", if (fav) "★ 已加入最愛" else "加入最愛", if (fav) Color(0xFFFFCA28) else Color.White, onFav)
            if (!recording && !r.isKml) Action("edit", "編輯") { editor.launch(GpxEditorActivity.intent(context, r.file, r.title)) }
            Action("map", "顯示在地圖上") { onShowOnMap(r.file) }
            Action("film", "3D 回放") { ReliveActivity.start(context, listOf(r.file), r.title) }
            Action("globe", "3D 地形") { ReliveActivity.start(context, listOf(r.file), r.title, explore = true) }
            Action("share", "分享") { share(context, r) }
            if (!r.isKml) Action("download", "匯出 GPX") { exportGpx.launch(r.file.name) }
            Action("download", "匯出 KML") { exportKml.launch(r.file.nameWithoutExtension + ".kml") }
            if (!recording) {
                Action("flag", "重新命名") { renaming = r.title }
                Action("x", "刪除", Color(0xFFFF8A80)) { askDelete = true }
            }
        }
        if (recording) Text("記錄中的軌跡要先結束記錄，才能重新命名或刪除。", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }

    if (askDelete) PanelDialog(
        onDismissRequest = { askDelete = false },
        title = { Text("刪除記錄") },
        text = { Text("確定刪除「${r.title}」" + (if (r.timed) "（${dayLabel(r.start)}）" else "") + "？手機上的這個檔案（與同名 KML）會永久刪除，無法復原。照片保留。") },
        confirmButton = {
            TextButton(onClick = {
                askDelete = false
                r.file.delete(); File(r.file.parentFile, r.file.nameWithoutExtension + ".kml").delete()
                Toast.makeText(context, "已刪除", Toast.LENGTH_SHORT).show()
                onChanged(true)
            }) { Text("刪除", color = Color(0xFFFF8A80)) }
        },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text("取消") } },
    )
    renaming?.let { name ->
        PanelDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重新命名") },
            text = { OutlinedTextField(value = name, onValueChange = { renaming = it }, singleLine = true, label = { Text("行程名稱") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = null
                    val f = renameRecord(r.file, name)
                    if (f == null) Toast.makeText(context, "無法重新命名（檔名重複？）", Toast.LENGTH_LONG).show() else { onRenameFav(r.file, f); onRenamed(f) }
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Action(icon: String, label: String, color: Color = Color.White, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.background(Color(0xFF2B3036), RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        LineIcon(icon, tint = color, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 13.sp)
    }
}

/** Elevation over distance; tap or drag to read a point (also marked on the track above). */
@Composable
private fun ProfileChart(r: TrackRecord, sel: Int, onSelect: (Int) -> Unit) {
    val eles = r.points.map { it.ele }
    if (eles.none { it != null } || r.distance <= 0) {
        Text("這段記錄沒有高度資料。", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        return
    }
    val eMin = eles.filterNotNull().min(); val eMax = eles.filterNotNull().max()
    val info = r.points.getOrNull(sel)?.let { p ->
        val prev = (sel - 6).coerceAtLeast(0)
        val dd = r.dist[sel] - r.dist[prev]
        val speed = if (p.t != null && r.points[prev].t != null && p.t > r.points[prev].t!!) dd / ((p.t - r.points[prev].t!!) / 3_600_000.0) / 1000 else null
        val grade = if (dd > 5 && p.ele != null && r.points[prev].ele != null) (p.ele - r.points[prev].ele!!) / dd * 100 else null
        "距起點 ${km(r.dist[sel])}・海拔 ${p.ele?.toInt() ?: "—"} m" + (p.t?.let { "・" + SimpleDateFormat("HH:mm:ss", Locale.US).format(it) } ?: "") +
            (speed?.let { "・%.1f km/h".format(it) } ?: "") + (grade?.let { "・坡度 %+.0f%%".format(it) } ?: "") +
            "\n%.5f, %.5f".format(p.lat, p.lon) + (p.t?.let { "・已走 " + dur(it - r.start) } ?: "")
    } ?: "點或拖曳高度圖，查看該點的距離、海拔、時間、速度與坡度"
    Text("高度剖面", fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Text(info, color = if (sel >= 0) Color.White else Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
    fun nearest(x: Float, w: Float, padL: Float): Int {
        val d = ((x - padL) / (w - padL)).coerceIn(0f, 1f) * r.distance
        var lo = 0; var hi = r.dist.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (r.dist[mid] < d) lo = mid + 1 else hi = mid }
        return lo
    }
    Canvas(
        modifier = Modifier.fillMaxWidth().height(170.dp)
            .pointerInput(r) { detectTapGestures { onSelect(nearest(it.x, size.width.toFloat(), 40.dp.toPx())) } }
            .pointerInput(r) { detectDragGestures { ch, _ -> onSelect(nearest(ch.position.x, size.width.toFloat(), 40.dp.toPx())) } },
    ) {
        val padL = 40.dp.toPx(); val padB = 16.dp.toPx(); val w = size.width - padL; val h = size.height - padB
        val span = (eMax - eMin).coerceAtLeast(20.0) * 1.1; val yA = (eMax + eMin) / 2 - span / 2
        fun px(d: Double) = padL + (d / r.distance * w).toFloat()
        fun py(e: Double) = (h - (e - yA) / span * h).toFloat()
        val paint = android.graphics.Paint().apply { color = 0xB3FFFFFF.toInt(); textSize = 9.sp.toPx(); isAntiAlias = true }
        for (k in 0..4) {
            val e = yA + span * k / 4; drawLine(Color(0x22FFFFFF), Offset(padL, py(e)), Offset(size.width, py(e)))
            drawContext.canvas.nativeCanvas.drawText("${e.toInt()}", 2f, py(e) + 3.dp.toPx(), paint)
        }
        for (k in 0..4) {
            val d = r.distance * k / 4
            drawContext.canvas.nativeCanvas.drawText(km(d), (px(d) - if (k == 4) 34.dp.toPx() else 6.dp.toPx()).coerceAtLeast(padL), size.height - 2.dp.toPx(), paint)
        }
        val line = Path(); val area = Path(); var started = false; var fx = 0f; var lx = 0f
        r.points.forEachIndexed { i, p ->
            val e = p.ele ?: return@forEachIndexed
            val x = px(r.dist[i]); val y = py(e)
            if (!started) { line.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y); fx = x; started = true } else { line.lineTo(x, y); area.lineTo(x, y) }
            lx = x
        }
        area.lineTo(lx, h); area.lineTo(fx, h); area.close()
        drawPath(area, Brush.verticalGradient(listOf(Color(0x8064B5F6), Color(0x1064B5F6)), 0f, h))
        drawPath(line, Color(0xFF90CAF9), style = Stroke(width = 2.dp.toPx()))
        r.points.getOrNull(sel)?.let { p ->
            val x = px(r.dist[sel])
            drawLine(Color(0xCCFFEB3B), Offset(x, 0f), Offset(x, h), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            p.ele?.let { drawCircle(Color.White, 6.dp.toPx(), Offset(x, py(it))); drawCircle(Color(0xFFFFEB3B), 4.5.dp.toPx(), Offset(x, py(it))) }
        }
    }
}

private fun writeTo(context: Context, uri: Uri, text: String) {
    runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
        .onSuccess { Toast.makeText(context, "已匯出", Toast.LENGTH_SHORT).show() }
        .onFailure { Toast.makeText(context, "匯出失敗：${it.message}", Toast.LENGTH_LONG).show() }
}

private fun share(context: Context, r: TrackRecord) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", r.file)
    val send = Intent(Intent.ACTION_SEND).setType(if (r.isKml) "application/vnd.google-earth.kml+xml" else "application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, r.title)
        .putExtra(Intent.EXTRA_TEXT, "${r.title}・" + (if (r.timed) "${dayLabel(r.start)}・" else "") + "${km(r.distance)}・${km(r.distance)}・↑${r.ascent.toInt()} ↓${r.descent.toInt()} m")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "分享軌跡"))
}

/** New 行程名稱 keeping the -yyyyMMdd-HHmmss stamp; renames the GPX and a same-named KML. Null on failure. */
private fun renameRecord(f: File, title: String): File? {
    val safe = title.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val m = TrackRecord.stampName.find(f.nameWithoutExtension)
    val base = if (m != null) "$safe-${m.groupValues[2]}-${m.groupValues[3]}" else safe
    val target = File(f.parentFile, "$base.${f.extension}")
    if (target.exists() && target != f) return null
    if (!f.renameTo(target)) return null
    if (f.extension == "gpx") File(f.parentFile, f.nameWithoutExtension + ".kml").takeIf { it.exists() }?.renameTo(File(f.parentFile, "$base.kml"))
    return target
}
