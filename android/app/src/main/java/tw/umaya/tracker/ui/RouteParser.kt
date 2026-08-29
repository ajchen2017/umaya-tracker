package tw.umaya.tracker.ui

import org.osmdroid.util.GeoPoint

/**
 * Extracts track segments from a GPX or KML file's raw text — just enough to draw reference
 * lines on the map (功能選單「載入GPX/KML」), not a full parser. Regex over a real XML parser is a
 * deliberate simplification here: both formats' coordinate syntax is simple and line-oriented,
 * and a full DOM parse buys nothing this feature actually uses.
 *
 * Returns one list of points PER SEGMENT rather than a single flat list — a GPX/KML file's
 * <trkseg>s (or KML LineStrings) are independently recorded stretches, often with a real gap
 * between them (e.g. a paused-and-resumed hike, or two separate trips in one file). Concatenating
 * them into one polyline draws a straight line across that gap, which is exactly what looked like
 * "軌跡亂畫" — a garbled zig-zag — when the file had more than one segment, or mixed in <wpt>
 * (individual waypoints) that aren't part of the track path at all.
 */
fun parseRoutePoints(text: String): List<List<GeoPoint>> {
    val segments = if (text.contains("<kml", ignoreCase = true)) parseKml(text) else parseGpx(text)
    return segments.filter { it.size >= 2 }
}

private val latPattern = Regex("""\blat\s*=\s*["']([-0-9.]+)["']""")
private val lonPattern = Regex("""\blon\s*=\s*["']([-0-9.]+)["']""")
private val trkptPattern = Regex("""<trkpt\b([^>]*)>""")

private fun parseGpx(text: String): List<List<GeoPoint>> {
    // Only <trkpt> inside <trkseg> — <wpt> (standalone waypoints) and <rtept> (a *planned* route,
    // a different concept from a *recorded* track) are deliberately excluded from the drawn line.
    val segPattern = Regex("<trkseg\\b[^>]*>(.*?)</trkseg>", RegexOption.DOT_MATCHES_ALL)
    return segPattern.findAll(text).map { seg ->
        trkptPattern.findAll(seg.groupValues[1]).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val lat = latPattern.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
            val lon = lonPattern.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
            if (lat != null && lon != null) GeoPoint(lat, lon) else null
        }.toList()
    }.toList()
}

private fun parseKml(text: String): List<List<GeoPoint>> {
    // <coordinates>lon,lat[,ele] lon,lat[,ele] ...</coordinates> — whitespace-separated tuples.
    // Each <coordinates> block (LineString, Track, etc.) becomes its own segment; a lone-point
    // block (a Placemark <Point>, a POI) is dropped by the size>=2 filter in parseRoutePoints.
    val coordsBlocks = Regex("<coordinates>(.*?)</coordinates>", RegexOption.DOT_MATCHES_ALL)
        .findAll(text).map { it.groupValues[1] }
    return coordsBlocks.map { block ->
        block.trim().split(Regex("\\s+")).mapNotNull { tuple ->
            val parts = tuple.split(",")
            if (parts.size < 2) return@mapNotNull null
            val lon = parts[0].toDoubleOrNull()
            val lat = parts[1].toDoubleOrNull()
            if (lon != null && lat != null) GeoPoint(lat, lon) else null
        }
    }.toList()
}
