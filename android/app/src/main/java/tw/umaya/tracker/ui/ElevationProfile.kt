package tw.umaya.tracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.osmdroid.util.GeoPoint

/** Distance (m) / elevation (m) samples along one track, segments joined in order. */
class Profile(val name: String, val dist: DoubleArray, val ele: DoubleArray, val points: List<GeoPoint>) {
    val total get() = dist.lastOrNull() ?: 0.0
    val hasElevation get() = ele.any { it != 0.0 }

    companion object {
        fun of(name: String, segments: List<List<GeoPoint>>): Profile? {
            val pts = segments.flatten()
            if (pts.size < 2) return null
            val d = DoubleArray(pts.size)
            for (i in 1 until pts.size) d[i] = d[i - 1] + pts[i - 1].distanceToAsDouble(pts[i])
            return Profile(name, d, DoubleArray(pts.size) { pts[it].altitude }, pts)
        }
    }

    /** Nearest track point to [here]: (index, metres off the track). */
    fun nearest(here: GeoPoint): Pair<Int, Double> {
        var best = 0; var bestD = Double.MAX_VALUE
        for (i in points.indices) { val dd = points[i].distanceToAsDouble(here); if (dd < bestD) { bestD = dd; best = i } }
        return best to bestD
    }

    /** Climb / descent from index [from] to the end, with a small deadband against GPS noise. */
    fun climbFrom(from: Int): Pair<Double, Double> {
        var up = 0.0; var down = 0.0; var ref = ele[from]
        for (i in from + 1 until ele.size) {
            val diff = ele[i] - ref
            if (diff >= 5) { up += diff; ref = ele[i] } else if (diff <= -5) { down -= diff; ref = ele[i] }
        }
        return up to down
    }
}

/**
 * 📈 高度剖面: elevation against distance for a track shown on the map, the hiker's position marked
 * on the curve. Pinch = zoom the distance axis, drag = pan; buttons zoom distance and height.
 * Same look as the 行程統計 panel.
 */
@Composable
fun ElevationProfilePanel(profiles: List<Profile>, here: GeoPoint?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var pick by remember { mutableIntStateOf(0) }
    val profile = profiles.getOrNull(pick) ?: profiles.firstOrNull()
    // View window as fractions of the full range: x over distance, y over the elevation span.
    var x0 by remember(profile) { mutableFloatStateOf(0f) }
    var xSpan by remember(profile) { mutableFloatStateOf(1f) }
    var yZoom by remember(profile) { mutableFloatStateOf(1f) }
    var yShift by remember(profile) { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .background(Color(0xF2202020), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📈 高度剖面", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                profiles.forEachIndexed { i, p ->
                    val on = p === profile
                    Text(
                        p.name, fontSize = 11.sp, maxLines = 1,
                        color = if (on) Color.Black else Color.White,
                        modifier = Modifier
                            .background(if (on) Color(0xFF64B5F6) else Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                            .clickable { pick = i }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text("✕", color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onClose).padding(start = 8.dp, end = 2.dp))
        }
        if (profile == null) {
            Text("地圖上沒有顯示中的軌跡。請到 ☰ → 軌跡檔管理 匯入，或開始記錄軌跡。", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
            return@Column
        }
        if (!profile.hasElevation) {
            Text("這條軌跡沒有高度資料。", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
            return@Column
        }

        val (idx, off) = remember(profile, here) { here?.let { profile.nearest(it) } ?: (-1 to Double.NaN) }
        // Readout for where the hiker is on the track.
        val info = if (idx >= 0) {
            val (up, down) = profile.climbFrom(idx)
            "📍 距起點 ${formatKm(profile.dist[idx])}・海拔 ${profile.ele[idx].toInt()} m・剩 ${formatKm(profile.total - profile.dist[idx])} ↑${up.toInt()} ↓${down.toInt()} m" +
                if (off > 150) "（離軌跡 ${off.toInt()} m）" else ""
        } else "全長 ${formatKm(profile.total)}・尚無 GPS 位置"
        Text(info, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))

        val eMin = remember(profile) { profile.ele.filter { it != 0.0 }.minOrNull() ?: 0.0 }
        val eMax = remember(profile) { profile.ele.maxOrNull() ?: 1.0 }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .pointerInput(profile) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        // pinch: zoom distance around the fingers; drag: pan both axes
                        val anchor = x0 + xSpan * (centroid.x / w)
                        val newSpan = (xSpan / zoom).coerceIn(0.01f, 1f)
                        x0 = (anchor - newSpan * (centroid.x / w) - pan.x / w * newSpan).coerceIn(0f, 1f - newSpan)
                        xSpan = newSpan
                        yShift += pan.y / h / yZoom
                    }
                },
        ) {
            val padL = 40.dp.toPx(); val padB = 16.dp.toPx(); val w = size.width - padL; val h = size.height - padB
            val total = profile.total
            val dA = total * x0; val dB = total * (x0 + xSpan)
            val span = (eMax - eMin).coerceAtLeast(20.0) * 1.15 / yZoom
            val mid = (eMax + eMin) / 2 + yShift * span
            val yA = mid - span / 2; val yB = mid + span / 2
            fun px(d: Double) = padL + ((d - dA) / (dB - dA) * w).toFloat()
            fun py(e: Double) = (h - (e - yA) / (yB - yA) * h).toFloat()
            val paint = android.graphics.Paint().apply { color = 0xB3FFFFFF.toInt(); textSize = 9.sp.toPx(); isAntiAlias = true }

            // grid + labels
            val yStep = niceStep((yB - yA) / 4); var gy = Math.ceil(yA / yStep) * yStep
            while (gy <= yB) {
                drawLine(Color(0x22FFFFFF), Offset(padL, py(gy)), Offset(size.width, py(gy)))
                drawContext.canvas.nativeCanvas.drawText("${gy.toInt()}", 2f, py(gy) + 3.dp.toPx(), paint); gy += yStep
            }
            val xStep = niceStep((dB - dA) / 4); var gx = Math.ceil(dA / xStep) * xStep
            while (gx <= dB) {
                drawLine(Color(0x22FFFFFF), Offset(px(gx), 0f), Offset(px(gx), h))
                drawContext.canvas.nativeCanvas.drawText(formatKm(gx), px(gx) - 8.dp.toPx(), size.height - 2.dp.toPx(), paint); gx += xStep
            }

            // curve (only what's in view, plus one point either side)
            val line = Path(); val area = Path(); var started = false; var firstX = 0f; var lastX = 0f
            for (i in profile.dist.indices) {
                val d = profile.dist[i]
                if (d < dA && i + 1 < profile.dist.size && profile.dist[i + 1] < dA) continue
                if (d > dB && i > 0 && profile.dist[i - 1] > dB) break
                val e = profile.ele[i]; if (e == 0.0) continue
                val x = px(d); val y = py(e)
                if (!started) { line.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y); firstX = x; started = true } else { line.lineTo(x, y); area.lineTo(x, y) }
                lastX = x
            }
            if (started) {
                area.lineTo(lastX, h); area.lineTo(firstX, h); area.close()
                drawPath(area, Brush.verticalGradient(listOf(Color(0x8064B5F6), Color(0x1064B5F6)), 0f, h))
                drawPath(line, Color(0xFF90CAF9), style = Stroke(width = 2.dp.toPx()))
            }

            // the hiker
            if (idx >= 0) {
                val x = px(profile.dist[idx]); val y = py(profile.ele[idx])
                if (x in padL..size.width) {
                    drawLine(Color(0xAAFF5252), Offset(x, 0f), Offset(x, h), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                    drawCircle(Color.White, 6.dp.toPx(), Offset(x, y)); drawCircle(Color(0xFFFF5252), 4.5.dp.toPx(), Offset(x, y))
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            ProfileButton("距離＋") { val c = x0 + xSpan / 2; xSpan = (xSpan / 1.6f).coerceAtLeast(0.01f); x0 = (c - xSpan / 2).coerceIn(0f, 1f - xSpan) }
            ProfileButton("距離－") { val c = x0 + xSpan / 2; xSpan = (xSpan * 1.6f).coerceAtMost(1f); x0 = (c - xSpan / 2).coerceIn(0f, 1f - xSpan) }
            ProfileButton("高度＋") { yZoom = (yZoom * 1.5f).coerceAtMost(20f) }
            ProfileButton("高度－") { yZoom = (yZoom / 1.5f).coerceAtLeast(0.5f) }
            if (idx >= 0) ProfileButton("📍") { // center the view on the hiker
                val f = (profile.dist[idx] / profile.total).toFloat(); x0 = (f - xSpan / 2).coerceIn(0f, 1f - xSpan)
            }
            ProfileButton("⟲") { x0 = 0f; xSpan = 1f; yZoom = 1f; yShift = 0f }
        }
    }
}

@Composable
private fun ProfileButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.White, fontSize = 12.sp,
        modifier = Modifier
            .background(Color(0x33FFFFFF), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun formatKm(m: Double) = if (m < 1000) "${m.toInt()} m" else "%.2f km".format(m / 1000)

/** 1/2/5 × 10ⁿ step at or above [raw]. */
private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val p = Math.pow(10.0, Math.floor(Math.log10(raw)))
    return listOf(1.0, 2.0, 5.0, 10.0).first { it * p >= raw } * p
}
