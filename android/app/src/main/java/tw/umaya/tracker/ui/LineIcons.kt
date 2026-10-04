package tw.umaya.tracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** circle as path data (SVG has no circle in `d`) */
private fun c(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

/**
 * The map screen's line icons (24×24 SVG path data, 2 px round strokes) — one consistent set
 * instead of emoji, matching the 介面改版 mockups. `rec` is drawn filled.
 */
private val ICONS: Map<String, String> = mapOf(
    "menu" to "M4 6h16M4 12h16M4 18h16",
    "rec" to c(12f, 12f, 6f),
    "pause" to "M9 5v14M15 5v14",
    "stop" to "M6 6h12v12H6z",
    "locate" to c(12f, 12f, 3.2f) + "M12 2v4M12 18v4M2 12h4M18 12h4",
    "flag" to "M5 21V4h11l-2 4 2 4H5",
    "camera" to "M3 7h18v13H3z" + c(12f, 13.5f, 3.5f) + "M8 7l1.5-3h5L16 7",
    "refresh" to "M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7",
    "gps" to c(12f, 12f, 2f) + "M8.5 8.5a5 5 0 0 0 0 7M15.5 8.5a5 5 0 0 1 0 7M5.6 5.6a9 9 0 0 0 0 12.8M18.4 5.6a9 9 0 0 1 0 12.8",
    "gpsoff" to "M4 4l16 16" + c(12f, 12f, 2f) + "M5.6 5.6a9 9 0 0 0 0 12.8M18.4 5.6a9 9 0 0 1 0 12.8",
    "layers" to "M12 3l9 5-9 5-9-5 9-5zM3 13l9 5 9-5",
    "home" to "M3 11l9-7 9 7v9h-6v-6H9v6H3z",
    "cloud" to "M7 18h10a4 4 0 0 0 .6-7.95A6 6 0 0 0 6 9.6 4.2 4.2 0 0 0 7 18z",
    "chart" to "M3 20h18M5 16l4-6 4 3 6-8",
    "ruler" to "M3 17L17 3l4 4L7 21zM7 13l2 2M10 10l2 2M13 7l2 2",
    "mountain" to "M2 20l7-12 4 6 3-4 6 10z",
    "plus" to "M12 5v14M5 12h14",
    "minus" to "M5 12h14",
    "gear" to c(12f, 12f, 3f) + "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1",
    "tools" to "M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.4-.6-.6-2.4z",
    "x" to "M6 6l12 12M18 6L6 18",
    "map" to "M9 4L3 6v14l6-2 6 2 6-2V4l-6 2-6-2zM9 4v14M15 6v14",
    "route" to c(6f, 18f, 2f) + c(18f, 6f, 2f) + "M8 18h6a3 3 0 0 0 0-6h-4a3 3 0 0 1 0-6h6",
    "play" to "M7 5l12 7-12 7z",
    "smile" to c(12f, 12f, 9f) + "M8.5 14a4 4 0 0 0 7 0",
    "tent" to "M3 20L12 4l9 16zM9 20l3-6 3 6",
    "stats" to "M4 20V10M10 20V4M16 20v-7M22 20H2",
    "chev" to "M9 6l6 6-6 6",
    "check" to "M5 12l5 5 9-10",
    "clock" to c(12f, 12f, 9f) + "M12 7v5l3 2",
    "link" to "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
    "download" to "M12 4v11M7 10l5 5 5-5M5 20h14",
    "merge" to "M6 3v6a6 6 0 0 0 6 6h7M16 12l3 3-3 3M18 3v4",
    "film" to "M3 5h18v14H3zM3 9h18M3 15h18M8 5v4M16 5v4M8 15v4M16 15v4",
    "battery" to "M3 8h16v8H3zM21 11v2",
    "logout" to "M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10",
    "power" to "M12 3v8M6.4 6.4a8 8 0 1 0 11.2 0",
)

/** One of [ICONS], in [tint]. Unknown names draw nothing. */
@Composable
fun LineIcon(name: String, tint: Color = Color.White, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val path = remember(name) { ICONS[name]?.let { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(size)) {
        if (path == null) return@Canvas
        scale(this.size.width / 24f, this.size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            if (name == "rec") drawPath(path, tint, style = Fill)
            else drawPath(path, tint, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
