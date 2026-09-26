package tw.umaya.tracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.osmdroid.util.GeoPoint
import org.xmlpull.v1.XmlPullParser

const val LOADED_ROUTE_COLOR = 0xFF00008B.toInt() // dark blue, shared by both map engines

/** A route label drawn as a dot with its text to the right; the dot sits at ([dotX], [dotY]). */
class RouteLabelBitmap(val bitmap: Bitmap, val dotX: Float, val dotY: Float)

/** Route labels show their text only from this zoom up — below it they crowd into an unreadable pile. */
const val ROUTE_LABEL_TEXT_MIN_ZOOM = 13

/** The label's dot alone, for zoom levels below [ROUTE_LABEL_TEXT_MIN_ZOOM]. */
fun routeDotBitmap(context: Context): RouteLabelBitmap {
    val density = context.resources.displayMetrics.density
    val radius = 5 * density
    val size = (radius * 2 + 2).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = size / 2f
    canvas.drawCircle(c, c, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    canvas.drawCircle(c, c, radius - 1.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LOADED_ROUTE_COLOR })
    return RouteLabelBitmap(bitmap, c, c)
}

fun routeLabelBitmap(context: Context, text: String): RouteLabelBitmap {
    val density = context.resources.displayMetrics.density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LOADED_ROUTE_COLOR
        textSize = 13 * density
        isFakeBoldText = true
    }
    val haloPaint = Paint(textPaint).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    val lines = text.lines().take(4).map { if (it.length > 40) it.take(40) + "…" else it }
    val radius = 5 * density
    val gap = 4 * density
    val lineHeight = textPaint.fontSpacing
    val textWidth = lines.maxOf { textPaint.measureText(it) }
    val width = (radius * 2 + gap + textWidth + haloPaint.strokeWidth * 2).toInt().coerceAtLeast(1)
    val height = (lines.size * lineHeight + haloPaint.strokeWidth * 2).coerceAtLeast(radius * 2 + 4).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val dotX = radius + 1
    val dotY = height / 2f
    canvas.drawCircle(dotX, dotY, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    canvas.drawCircle(dotX, dotY, radius - 1.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LOADED_ROUTE_COLOR })
    val textX = radius * 2 + gap
    var baseline = (height - lines.size * lineHeight) / 2 - textPaint.fontMetrics.ascent
    for (line in lines) {
        canvas.drawText(line, textX, baseline, haloPaint)
        canvas.drawText(line, textX, baseline, textPaint)
        baseline += lineHeight
    }
    return RouteLabelBitmap(bitmap, dotX, dotY)
}

/** A text annotation from a route file, drawn as a labeled dot: a waypoint (<wpt>, KML Point
 *  Placemark) or a track's own name at its first point. [text] may span several lines. */
data class RouteLabel(val point: GeoPoint, val text: String)

data class ParsedRoute(val segments: List<List<GeoPoint>>, val labels: List<RouteLabel>)

/**
 * Extracts what the map draws from a GPX or KML file — track segments plus every named
 * point/track. Streams through Android's XmlPullParser: an earlier regex-based version took
 * 10+ seconds on a pair of ~1.4MB files on-device, during which the map showed no routes.
 *
 * Returns one list of points PER SEGMENT rather than a single flat list — a GPX/KML file's
 * <trkseg>s (or KML LineStrings) are independently recorded stretches, often with a real gap
 * between them. Concatenating them into one polyline draws a straight line across that gap,
 * which is exactly what looked like "軌跡亂畫".
 */
fun parseRoute(text: String): ParsedRoute {
    val parser = android.util.Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(java.io.StringReader(text.removePrefix("﻿").trimStart()))
    return if (text.contains("<kml", ignoreCase = true)) parseKml(parser) else parseGpx(parser)
}

private fun XmlPullParser.localName(): String = name.substringAfter(':')

/** Text content of the current element (CDATA included); markup inside it — KML descriptions are
 *  often HTML — is dropped. Leaves the parser on the element's END_TAG. */
private fun XmlPullParser.readText(): String {
    val sb = StringBuilder()
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(text)
            XmlPullParser.START_TAG -> { depth++; if (localName() == "br") sb.append('\n') }
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> break
        }
    }
    return sb.toString().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "").trim()
}

private fun labelText(name: String?, vararg extra: String?): String? {
    val lines = listOfNotNull(name?.takeIf { it.isNotBlank() }) +
        extra.filterNotNull().filter { it.isNotBlank() && it != name }
    return lines.joinToString("\n").takeIf { it.isNotBlank() }
}

private fun XmlPullParser.latLon(): GeoPoint? {
    val lat = getAttributeValue(null, "lat")?.toDoubleOrNull()
    val lon = getAttributeValue(null, "lon")?.toDoubleOrNull()
    return if (lat != null && lon != null) GeoPoint(lat, lon) else null
}

private fun parseGpx(p: XmlPullParser): ParsedRoute {
    // Only <trkpt> inside <trkseg> form lines — <wpt> are standalone labeled points, and <rtept>
    // (a *planned* route, a different concept from a *recorded* track) is not drawn.
    val segments = mutableListOf<List<GeoPoint>>()
    val labels = mutableListOf<RouteLabel>()
    var trkName: String? = null
    var trkFirst: GeoPoint? = null
    var seg: MutableList<GeoPoint>? = null
    var wpt: GeoPoint? = null
    var wptName: String? = null; var wptDesc: String? = null; var wptCmt: String? = null
    var inTrkpt = false
    while (true) {
        when (p.next()) {
            XmlPullParser.END_DOCUMENT -> break
            XmlPullParser.START_TAG -> when (p.localName()) {
                "trk" -> { trkName = null; trkFirst = null }
                "trkseg" -> seg = mutableListOf()
                "trkpt" -> { inTrkpt = true; p.latLon()?.let { pt -> seg?.add(pt); if (trkFirst == null) trkFirst = pt } }
                "wpt" -> { wpt = p.latLon(); wptName = null; wptDesc = null; wptCmt = null }
                "name" -> when {
                    wpt != null -> wptName = p.readText()
                    seg == null && !inTrkpt -> trkName = p.readText()
                }
                "desc" -> if (wpt != null) wptDesc = p.readText()
                "cmt" -> if (wpt != null) wptCmt = p.readText()
            }
            XmlPullParser.END_TAG -> when (p.localName()) {
                "trkpt" -> inTrkpt = false
                "trkseg" -> { seg?.takeIf { it.size >= 2 }?.let(segments::add); seg = null }
                "trk" -> { val first = trkFirst; val name = trkName; if (first != null && !name.isNullOrBlank()) labels += RouteLabel(first, name) }
                "wpt" -> { val pt = wpt; labelText(wptName, wptDesc, wptCmt)?.let { if (pt != null) labels += RouteLabel(pt, it) }; wpt = null }
            }
        }
    }
    return ParsedRoute(segments, labels)
}

private fun parseCoordinates(block: String): List<GeoPoint> =
    block.trim().split(Regex("\\s+")).mapNotNull { tuple ->
        val parts = tuple.split(",")
        if (parts.size < 2) return@mapNotNull null
        val lon = parts[0].toDoubleOrNull()
        val lat = parts[1].toDoubleOrNull()
        if (lon != null && lat != null) GeoPoint(lat, lon) else null
    }

private fun parseKml(p: XmlPullParser): ParsedRoute {
    val segments = mutableListOf<List<GeoPoint>>()
    val labels = mutableListOf<RouteLabel>()
    var inPlacemark = false
    var name: String? = null; var desc: String? = null
    val blocks = mutableListOf<List<GeoPoint>>()
    while (true) {
        when (p.next()) {
            XmlPullParser.END_DOCUMENT -> break
            XmlPullParser.START_TAG -> when (p.localName()) {
                "Placemark" -> { inPlacemark = true; name = null; desc = null; blocks.clear() }
                "name" -> if (inPlacemark) name = p.readText()
                "description" -> if (inPlacemark) desc = p.readText()
                "coordinates" -> if (inPlacemark) blocks += parseCoordinates(p.readText())
            }
            XmlPullParser.END_TAG -> if (p.localName() == "Placemark") {
                inPlacemark = false
                val lines = blocks.filter { it.size >= 2 }
                segments += lines
                // A lone-coordinate block is a Point placemark (a POI); a line gets its name at its start.
                val anchor = blocks.firstOrNull { it.size == 1 }?.first() ?: lines.firstOrNull()?.first()
                val label = if (lines.isEmpty()) labelText(name, desc) else name?.takeIf { it.isNotBlank() }
                if (anchor != null && label != null) labels += RouteLabel(anchor, label)
            }
        }
    }
    return ParsedRoute(segments, labels)
}
