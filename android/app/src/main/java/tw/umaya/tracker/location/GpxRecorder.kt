package tw.umaya.tracker.location

import android.content.Context
import android.location.Location
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Writes the hiker's own personal GPS trail to a file under app-external storage — independent
 * of the server-facing hike reporting. Always recorded on disk as GPX while active (re-writing
 * the closing tags after every point, so the file is valid GPX after every single point, not
 * just after a clean stop() — the phone dying or the app getting killed mid-recording is the
 * normal case to design for on a multi-hour hike, not an edge case). KML is produced only at
 * [finalize] time, by converting the finished GPX, since the format choice isn't made until then.
 */
class GpxRecorder(private val context: Context) {

    private var file: File? = null
    private var pointCount = 0
    private var lastLogged: Location? = null

    val isActive: Boolean get() = file != null
    val currentPath: String? get() = file?.absolutePath

    private fun isoNow(time: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(time)
    }

    private fun gpxDir() = File(context.getExternalFilesDir(null), "gpx").apply { mkdirs() }

    /**
     * Starts a new trail file, or — if [resumePath] names a file that still exists — resumes
     * appending to it in a new segment (接續舊行程, or after the process died mid-recording).
     * Returns the path actually in use.
     */
    fun start(resumePath: String? = null): String {
        if (resumePath != null && File(resumePath).exists()) {
            file = File(resumePath)
            pointCount = trackPointCount(File(resumePath))
            newSegment()
            return resumePath
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(System.currentTimeMillis())
        val f = File(gpxDir(), "$stamp.gpx")
        f.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="umaya-tracker" xmlns="http://www.topografix.com/GPX/1/1">
<trk><name>$stamp</name><trkseg>
</trkseg></trk></gpx>
"""
        )
        file = f
        pointCount = 0
        lastLogged = null
        return f.absolutePath
    }

    /**
     * Appends one fix, but only once BOTH the minimum time and the minimum distance since the
     * last logged point are reached — so standing still doesn't pile up points at one spot.
     * [minIntervalSec] 0 = no time limit (distance alone decides). No-op if [start] wasn't called.
     */
    fun appendPoint(location: Location, minIntervalSec: Int, minDistanceM: Int) {
        val f = file ?: return
        val prev = lastLogged
        if (prev != null) {
            val elapsedSec = (location.time - prev.time) / 1000.0
            val distanceM = prev.distanceTo(location)
            if (elapsedSec < minIntervalSec || distanceM < minDistanceM) return
        }
        val ele = if (location.hasAltitude()) " <ele>%.1f</ele>".format(location.altitude) else ""
        val trkpt = "<trkpt lat=\"${location.latitude}\" lon=\"${location.longitude}\">$ele" +
            "<time>${isoNow(location.time)}</time></trkpt>\n"

        // Re-open, drop the trailing "</trkseg></trk></gpx>\n" footer, append the new point,
        // write the footer back — keeps the file valid GPX after every single point.
        val text = f.readText()
        val footerIdx = text.lastIndexOf("</trkseg>")
        if (footerIdx < 0) return // file got corrupted/tampered with externally — give up quietly
        val body = text.substring(0, footerIdx)
        f.writeText(body + trkpt + "</trkseg></trk></gpx>\n")
        pointCount++
        lastLogged = location
    }

    /** Adds a named waypoint (航點) — before <trk>, as GPX 1.1 orders wpt first. No-op if not recording. */
    fun addWaypoint(name: String, location: Location, photo: String? = null) {
        val f = file ?: return
        val text = f.readText()
        val trkIdx = text.indexOf("<trk>")
        if (trkIdx < 0) return
        val ele = if (location.hasAltitude()) "<ele>%.1f</ele>".format(location.altitude) else ""
        val wpt = "<wpt lat=\"${location.latitude}\" lon=\"${location.longitude}\">$ele" +
            "<time>${isoNow(location.time)}</time><name>${xmlEscape(name)}</name>" +
            (photo?.let { "<link href=\"${xmlEscape(it)}\"><text>照片</text><type>image/jpeg</type></link>" } ?: "") +
            "</wpt>\n"
        f.writeText(text.substring(0, trkIdx) + wpt + text.substring(trkIdx))
    }

    /**
     * Closes the current <trkseg> and opens a new one, so the gap across a pause (or an app
     * restart) is not drawn as a straight line. No-op while the current segment is still empty.
     */
    fun newSegment() {
        val f = file ?: return
        val text = f.readText()
        val footerIdx = text.lastIndexOf("</trkseg>")
        if (footerIdx < 0) return
        if (text.indexOf("<trkpt", text.lastIndexOf("<trkseg>")) < 0) return
        f.writeText(text.substring(0, footerIdx) + "</trkseg>\n<trkseg>\n</trkseg></trk></gpx>\n")
        lastLogged = null
    }

    /**
     * Renames the finished trail to the hiker's chosen name, converting to KML too if asked.
     * Returns the final saved path and point count, or null if nothing was recording.
     */
    fun finalize(name: String, format: String): Pair<String, Int>? {
        val f = file ?: return null
        file = null
        val count = pointCount
        pointCount = 0
        lastLogged = null

        val safeName = name.ifBlank { f.nameWithoutExtension }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val gpxTarget = File(f.parentFile, "$safeName.gpx")
        if (f != gpxTarget) f.renameTo(gpxTarget)

        if (format == "kml") {
            val kmlFile = File(f.parentFile, "$safeName.kml")
            kmlFile.writeText(gpxToKml(gpxTarget.readText(), safeName))
            return kmlFile.absolutePath to count
        }
        return gpxTarget.absolutePath to count
    }

}

/** GPX trails recorded on this phone (finished and in-progress), newest first. */
fun recordedTracks(context: Context): List<File> =
    File(context.getExternalFilesDir(null), "gpx").listFiles { f -> f.extension == "gpx" }
        .orEmpty().sortedByDescending { it.lastModified() }

private fun xmlEscape(s: String) =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private val wptPattern = Regex("<wpt\\b.*?</wpt>", RegexOption.DOT_MATCHES_ALL)

private val trksegPattern = Regex("<trkseg>(.*?)</trkseg>", RegexOption.DOT_MATCHES_ALL)

/**
 * Merges several recorded trails into one GPX, oldest first, each kept as its own <trkseg> so no
 * line is drawn across the gap between one recording and the next.
 */
fun mergeGpx(files: List<File>, name: String): String {
    val segments = files.sortedBy { it.name }.flatMap { f ->
        trksegPattern.findAll(f.readText()).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    }
    val waypoints = files.sortedBy { it.name }.flatMap { f -> wptPattern.findAll(f.readText()).map { it.value }.toList() }
    return """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="umaya-tracker" xmlns="http://www.topografix.com/GPX/1/1">
""" + waypoints.joinToString("") { "$it\n" } + """<trk><name>$name</name>
""" + segments.joinToString("") { "<trkseg>\n$it\n</trkseg>\n" } + "</trk></gpx>\n"
}

/** Converts a recorded (or merged) GPX trail to KML, one line per segment, keeping altitude. */
fun gpxToKml(gpxXml: String, name: String): String {
    // The recorder writes `...lon="x"> <ele>…` — whitespace before <ele> must be allowed, or every
    // altitude silently comes out as 0.
    val point = Regex("<trkpt lat=\"([^\"]+)\" lon=\"([^\"]+)\">\\s*(?:<ele>([^<]+)</ele>)?")
    val lines = trksegPattern.findAll(gpxXml).map { seg ->
        point.findAll(seg.groupValues[1]).joinToString(" ") { m ->
            "${m.groupValues[2]},${m.groupValues[1]},${m.groupValues[3].ifBlank { "0" }}"
        }
    }.filter { it.isNotEmpty() }.joinToString("") {
        "<LineString><tessellate>1</tessellate><altitudeMode>absolute</altitudeMode><coordinates>\n$it\n</coordinates></LineString>\n"
    }
    val wptPoint = Regex("<wpt lat=\"([^\"]+)\" lon=\"([^\"]+)\">\\s*(?:<ele>([^<]+)</ele>)?.*?<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
    val placemarks = wptPattern.findAll(gpxXml).mapNotNull { w -> wptPoint.find(w.value) }.joinToString("") { m ->
        "<Placemark><name>${m.groupValues[4]}</name><Point><coordinates>${m.groupValues[2]},${m.groupValues[1]},${m.groupValues[3].ifBlank { "0" }}</coordinates></Point></Placemark>\n"
    }
    return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>$name</name>
<Placemark><name>$name</name><MultiGeometry>
$lines</MultiGeometry></Placemark>
$placemarks</Document></kml>
"""
}

/** Point count of a recorded trail, for listing it. */
fun trackPointCount(f: File): Int {
    val text = f.readText()
    var count = 0
    var idx = text.indexOf("<trkpt")
    while (idx >= 0) { count++; idx = text.indexOf("<trkpt", idx + 1) }
    return count
}

data class TrackStats(val distanceM: Double, val ascentM: Double, val descentM: Double)

/**
 * Distance (per segment — not across pause gaps) and cumulative ascent/descent of a recorded
 * trail. GPS altitude jitters by several meters even standing still, so a climb only counts once
 * it moves [ALTITUDE_DEADBAND_M] away from the last counted altitude.
 */
fun trackStats(gpxXml: String): TrackStats {
    val point = Regex("<trkpt lat=\"([^\"]+)\" lon=\"([^\"]+)\">\\s*(?:<ele>([^<]+)</ele>)?")
    val result = FloatArray(1)
    var distance = 0.0
    var ascent = 0.0
    var descent = 0.0
    var refAlt: Double? = null
    for (seg in Regex("<trkseg>(.*?)</trkseg>", RegexOption.DOT_MATCHES_ALL).findAll(gpxXml)) {
        var prevLat = Double.NaN
        var prevLon = Double.NaN
        for (m in point.findAll(seg.groupValues[1])) {
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            if (!prevLat.isNaN()) {
                Location.distanceBetween(prevLat, prevLon, lat, lon, result)
                distance += result[0]
            }
            prevLat = lat; prevLon = lon
            val alt = m.groupValues[3].toDoubleOrNull() ?: continue
            val ref = refAlt
            if (ref == null) refAlt = alt
            else if (alt - ref >= ALTITUDE_DEADBAND_M) { ascent += alt - ref; refAlt = alt }
            else if (ref - alt >= ALTITUDE_DEADBAND_M) { descent += ref - alt; refAlt = alt }
        }
    }
    return TrackStats(distance, ascent, descent)
}

private const val ALTITUDE_DEADBAND_M = 8.0
