package tw.umaya.tracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tw.umaya.tracker.data.MapsforgeDownloader
import tw.umaya.tracker.data.WorldMaps

/**
 * 🌍 下載世界各地離線地圖: 洲 → 國家 (→ 區域) from the Mapsforge server, or type a name (中文／English)
 * to search every continent. Picking a map shows its real size first; downloading shows progress
 * and can be cancelled. [onInstalled] runs after a map lands so the pack lists refresh.
 */
@Composable
fun WorldMapsDialog(downloader: MapsforgeDownloader, onInstalled: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf("") }                 // path stack, "" = continents
    val listings = remember { mutableStateMapOf<String, List<WorldMaps.Entry>>() }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<WorldMaps.Entry?>(null) }
    var confirmBytes by remember { mutableStateOf<Long?>(null) }
    var downloading by remember { mutableStateOf<WorldMaps.Entry?>(null) }
    var done by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(1L) }
    var installedTick by remember { mutableStateOf(0) }
    val path = stack.last()

    suspend fun load(p: String) {
        if (p in listings) return
        runCatching { WorldMaps.list(p) }.onSuccess { listings[p] = it; error = null }.onFailure { error = "讀取清單失敗：${it.message}（需要網路）" }
    }
    LaunchedEffect(path) { load(path) }
    // Searching covers every continent's first level (countries and big-country folders).
    LaunchedEffect(query.isNotBlank()) {
        if (query.isBlank()) return@LaunchedEffect
        load("")
        listings[""]?.filter { it.isDir }?.forEach { load(it.path) }
    }

    fun mb(b: Long) = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)

    PanelDialog(
        onDismissRequest = { if (downloading == null) onDismiss() },
        title = { Text("下載世界各地離線地圖") },
        text = {
            Column {
                val dl = downloading; val cf = confirm
                when {
                    dl != null -> {
                        Text("下載中：${dl.label}", fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                        Text("${mb(done)} / ${mb(total)}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { downloader.cancel() }) { Text("取消下載") }
                    }
                    cf != null -> {
                        Text("下載「${cf.label}」？", fontWeight = FontWeight.Bold)
                        Text(confirmBytes?.let { "大小約 ${mb(it)}，存在手機裡、沒網路也能用；之後在底部「地圖」分頁切換。" } ?: "正在確認檔案大小…",
                            modifier = Modifier.padding(vertical = 8.dp))
                        Text("地圖資料：Mapsforge / OpenStreetMap，以魯地圖樣式顯示（沒有魯地圖專屬的步道標註與陰影）。",
                            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                        Row {
                            TextButton(onClick = { confirm = null }) { Text("取消") }
                            TextButton(enabled = confirmBytes != null, onClick = {
                                downloading = cf; confirm = null; done = 0; total = (confirmBytes ?: 1L).coerceAtLeast(1L)
                                scope.launch {
                                    runCatching { downloader.downloadWorldMap(cf) { d, t -> done = d; if (t > 0) total = t } }
                                        .onSuccess { onInstalled(); installedTick++ }
                                        .onFailure { error = "下載未完成：${it.message}" }
                                    downloading = null
                                }
                            }) { Text("下載") }
                        }
                    }
                    else -> {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            label = { Text("搜尋國家（中文或英文）") }, modifier = Modifier.fillMaxWidth(),
                        )
                        if (query.isBlank() && stack.size > 1) Text(
                            "↑ 上一層　" + stack.drop(1).joinToString(" › ") { p -> WorldMaps.label(p.trimEnd('/').substringAfterLast('/')) },
                            color = Color(0xFF64B5F6), fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().clickable { stack.removeAt(stack.lastIndex) }.padding(vertical = 8.dp),
                        )
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        @Suppress("UNUSED_EXPRESSION") installedTick
                        val rows: List<WorldMaps.Entry> = if (query.isBlank()) listings[path].orEmpty() else {
                            val q = query.trim().lowercase()
                            listings.filterKeys { it.isNotEmpty() }.values.flatten().filter { q in it.label.lowercase() }.distinctBy { it.path }
                        }
                        if (rows.isEmpty()) Text(if (query.isBlank()) "讀取中…" else "找不到符合的地圖", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 10.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                            items(rows.size) { i ->
                                val e = rows[i]; val have = !e.isDir && downloader.hasWorldMap(e.name)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        if (e.isDir) { query = ""; stack.add(e.path) }
                                        else if (!have) { confirm = e; confirmBytes = null; scope.launch { confirmBytes = downloader.worldMapBytes(e) } }
                                    }.padding(vertical = 10.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(e.label, fontSize = 15.sp)
                                        if (query.isNotBlank()) Text(WorldMaps.label(e.path.substringBefore('/')), fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        when { e.isDir -> "分區 ›"; have -> "已下載・" + mb(e.bytes); else -> mb(e.bytes) },
                                        color = if (have) Color(0xFF81C784) else Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = downloading == null, onClick = onDismiss) { Text("關閉") } },
    )
}
