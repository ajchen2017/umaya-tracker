package tw.umaya.tracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private class Sat(val system: String, val svid: Int, val cn0: Float, val used: Boolean)

private fun systemName(type: Int) = when (type) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_BEIDOU -> "北斗"
    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
    else -> "其他"
}

/**
 * GPS 衛星狀態: satellites in view / used in the fix, per system, and each one's signal strength
 * (C/N0, dB-Hz) — live while open. Keeps the GPS receiver running meanwhile so the numbers move
 * even when nothing else is asking for a location.
 */
@SuppressLint("MissingPermission")
@Composable
fun GnssStatusDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var sats by remember { mutableStateOf<List<Sat>>(emptyList()) }
    var fix by remember { mutableStateOf<Location?>(null) }
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose {}
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                sats = (0 until status.satelliteCount).map { i ->
                    Sat(systemName(status.getConstellationType(i)), status.getSvid(i), status.getCn0DbHz(i), status.usedInFix(i))
                }.sortedWith(compareByDescending<Sat> { it.used }.thenByDescending { it.cn0 })
            }
        }
        val listener = LocationListener { fix = it }
        val handler = Handler(Looper.getMainLooper())
        runCatching { lm.registerGnssStatusCallback(cb, handler) }
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()) }
        onDispose { lm.unregisterGnssStatusCallback(cb); lm.removeUpdates(listener) }
    }

    PanelDialog(
        onDismissRequest = onDismiss,
        title = { Text("GPS 衛星狀態") },
        text = {
            Column {
                if (!granted) { Text("沒有定位權限"); return@Column }
                val used = sats.count { it.used }
                val avg = sats.filter { it.used }.map { it.cn0 }.average().takeIf { !it.isNaN() }
                Text("定位使用 $used 顆・可見 ${sats.size} 顆", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    (avg?.let { "平均訊號 ${"%.0f".format(it)} dB-Hz（" + quality(it) + "）・" } ?: "") +
                        (fix?.let { "精度 ±${it.accuracy.toInt()} m" } ?: "等待定位…"),
                    style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f),
                )
                Text(
                    sats.groupBy { it.system }.entries.sortedByDescending { it.value.size }
                        .joinToString("・") { (sys, l) -> "$sys ${l.count { it.used }}/${l.size}" },
                    style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 6.dp),
                )
                if (sats.isEmpty()) Text("搜尋衛星中…請到戶外空曠處，可能需要 30 秒～2 分鐘。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(sats.size) { i ->
                        val s = sats[i]
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("${s.system} ${s.svid}", fontSize = 12.sp, color = if (s.used) Color.White else Color.White.copy(alpha = 0.5f), modifier = Modifier.width(92.dp))
                            Box(modifier = Modifier.weight(1f).height(10.dp).background(Color(0x22FFFFFF), RoundedCornerShape(5.dp))) {
                                Box(
                                    modifier = Modifier.fillMaxWidth((s.cn0 / 50f).coerceIn(0.02f, 1f)).fillMaxHeight()
                                        .background(if (s.used) barColor(s.cn0) else Color(0x66FFFFFF), RoundedCornerShape(5.dp)),
                                )
                            }
                            Text("${s.cn0.toInt()}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.width(30.dp).padding(start = 6.dp))
                        }
                    }
                }
                Text("長條＝訊號強度（C/N0）；亮色＝正用於定位。約 35 dB-Hz 以上為良好。",
                    style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}

private fun quality(cn0: Double) = when { cn0 >= 35 -> "良好"; cn0 >= 25 -> "普通"; else -> "微弱" }
private fun barColor(cn0: Float) = when { cn0 >= 35 -> Color(0xFF81C784); cn0 >= 25 -> Color(0xFFFFD54F); else -> Color(0xFFFF8A65) }
