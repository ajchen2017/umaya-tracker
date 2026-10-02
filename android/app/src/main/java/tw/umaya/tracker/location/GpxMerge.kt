package tw.umaya.tracker.location

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.util.TreeMap

/**
 * GPX files brought in through ☰ → GPX 合併匯出 (e.g. a watch's track of the same day). They are
 * copies — the originals on the phone are never touched — kept apart from the app's own
 * recordings, and never deleted by the app. Merging only ever writes a new file.
 */
fun importedGpxDir(context: Context) = File(context.getExternalFilesDir(null), "gpx/imported").apply { mkdirs() }

fun importedTracks(context: Context): List<File> =
    importedGpxDir(context).listFiles { f -> f.extension.equals("gpx", ignoreCase = true) }
        .orEmpty().sortedByDescending { it.lastModified() }

private val gpxRootTag = Regex("<gpx\\b[^>]*>")
private val xmlnsAttr = Regex("xmlns(:[\\w.-]+)?\\s*=\\s*\"([^\"]*)\"")
private val wptElem = Regex("<wpt\\b[^>]*?/>|<wpt\\b.*?</wpt>", RegexOption.DOT_MATCHES_ALL)
private val rteElem = Regex("<rte\\b.*?</rte>", RegexOption.DOT_MATCHES_ALL)
private val trksegElem = Regex("<trkseg\\b[^>]*>(.*?)</trkseg>", RegexOption.DOT_MATCHES_ALL)
private val trkptElem = Regex("<trkpt\\b[^>]*?/>|<trkpt\\b.*?</trkpt>", RegexOption.DOT_MATCHES_ALL)
private val timeElem = Regex("<time>\\s*([^<\\s]+)\\s*</time>")

/** 補空檔: another file's point is used only where the merged track has nothing within ±1 min. */
private const val FILL_GAP_MS = 60_000L
/** No point for 5 min → new segment, so no straight line is drawn across a hole. */
private const val SPLIT_GAP_MS = 300_000L

private fun parseGpxTime(s: String): Long? =
    runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()

private fun xmlText(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private class Pt(val time: Long?, val xml: String)

/**
 * Merges several GPX files into one new GPX. Every point is copied verbatim (time, elevation,
 * heart rate and other extensions included), as are waypoints and routes; the namespaces those
 * extensions need are carried over.
 *
 * [fillGaps] true — 補空檔合併: the file with the most timed points leads; the others only add
 * points where it has nothing within ±1 min (e.g. a watch track filling the holes in the phone's),
 * all in time order. false — 各自一段: each file's segments kept as they are, files in time order.
 */
fun mergeGpxFiles(files: List<File>, name: String, fillGaps: Boolean): String {
    val texts = files.map { it.readText() }

    val namespaces = linkedMapOf<String, String>() // prefix ("" = default) → URI
    texts.forEach { t ->
        gpxRootTag.find(t)?.value?.let { root ->
            xmlnsAttr.findAll(root).forEach { m -> namespaces.putIfAbsent(m.groupValues[1].removePrefix(":"), m.groupValues[2]) }
        }
    }
    namespaces[""] = "http://www.topografix.com/GPX/1/1"

    val points = texts.map { t ->
        trkptElem.findAll(t).map { m -> Pt(timeElem.find(m.value)?.groupValues?.get(1)?.let(::parseGpxTime), m.value) }.toList()
    }
    val segments = mutableListOf<List<String>>()
    if (fillGaps) {
        val timed = points.map { pts -> pts.filter { it.time != null } }
        val merged = TreeMap<Long, String>()
        for (i in timed.indices.sortedByDescending { timed[it].size }) {
            val before = TreeMap(merged) // a file never thins out its own points, only others'
            for (p in timed[i]) {
                val t = p.time!!
                val near = listOfNotNull(before.floorKey(t), before.ceilingKey(t)).any { kotlin.math.abs(it - t) <= FILL_GAP_MS }
                if (!near) merged.putIfAbsent(t, p.xml)
            }
        }
        var current = mutableListOf<String>()
        var last: Long? = null
        for ((t, xml) in merged) {
            if (last != null && t - last > SPLIT_GAP_MS && current.isNotEmpty()) { segments += current; current = mutableListOf() }
            current += xml
            last = t
        }
        if (current.isNotEmpty()) segments += current
        // Points without a time can't be placed on the timeline — kept, as their own segments.
        texts.indices.filter { i -> points[i].isNotEmpty() && timed[i].isEmpty() }.forEach { i ->
            trksegElem.findAll(texts[i]).map { seg -> trkptElem.findAll(seg.groupValues[1]).map { it.value }.toList() }
                .filter { it.isNotEmpty() }.forEach { segments += it }
        }
    } else {
        val order = texts.indices.sortedBy { i -> points[i].firstNotNullOfOrNull { it.time } ?: Long.MAX_VALUE }
        for (i in order) {
            trksegElem.findAll(texts[i]).map { seg -> trkptElem.findAll(seg.groupValues[1]).map { it.value }.toList() }
                .filter { it.isNotEmpty() }.forEach { segments += it }
        }
    }

    val waypoints = texts.flatMap { t -> wptElem.findAll(t).map { it.value }.toList() }.distinct()
    val routes = texts.flatMap { t -> rteElem.findAll(t).map { it.value }.toList() }
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"umaya-tracker\"")
        namespaces.forEach { (prefix, uri) -> append(if (prefix.isEmpty()) " xmlns=\"$uri\"" else " xmlns:$prefix=\"$uri\"") }
        append(">\n<metadata><name>").append(xmlText(name)).append("</name></metadata>\n")
        waypoints.forEach { append(it).append('\n') }
        routes.forEach { append(it).append('\n') }
        append("<trk><name>").append(xmlText(name)).append("</name>\n")
        segments.forEach { seg -> append("<trkseg>\n"); seg.forEach { append(it).append('\n') }; append("</trkseg>\n") }
        append("</trk></gpx>\n")
    }
}
