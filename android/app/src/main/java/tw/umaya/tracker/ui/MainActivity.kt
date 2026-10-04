package tw.umaya.tracker.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.osmdroid.util.GeoPoint
import tw.umaya.tracker.data.ApiClient
import tw.umaya.tracker.data.CreateHikeRequest
import tw.umaya.tracker.data.ForgotPasswordRequest
import tw.umaya.tracker.data.IntervalRequest
import tw.umaya.tracker.data.HikeListItemDto
import tw.umaya.tracker.data.INTERVAL_PRESETS
import tw.umaya.tracker.data.GPX_DISTANCE_OPTIONS
import tw.umaya.tracker.data.GPX_INTERVAL_OPTIONS
import tw.umaya.tracker.data.MAP_TEXT_SIZE_OPTIONS
import tw.umaya.tracker.data.OFF_ROUTE_DISTANCE_OPTIONS
import tw.umaya.tracker.data.LoginRequest
import tw.umaya.tracker.data.MapsforgeDownloader
import tw.umaya.tracker.data.Prefs
import tw.umaya.tracker.data.RegisterRequest
import tw.umaya.tracker.data.TAIWAN_PACK_ID
import tw.umaya.tracker.data.intervalLabel
import tw.umaya.tracker.location.LocationForegroundService
import tw.umaya.tracker.location.gpxToKml
import tw.umaya.tracker.location.importedGpxDir
import tw.umaya.tracker.location.importedTracks
import tw.umaya.tracker.location.mergeGpxFiles
import tw.umaya.tracker.location.migrateTrackFileNames
import tw.umaya.tracker.location.trackFileBaseName
import tw.umaya.tracker.location.recordedTracks
import tw.umaya.tracker.location.trackPointCount
import tw.umaya.tracker.location.trackStats
import tw.umaya.tracker.location.TrackStats
import tw.umaya.tracker.sync.HikeActionWorker
import tw.umaya.tracker.widget.TrackerWidgetProvider
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.io.File
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/** One loaded GPX/KML reference route (功能選單「載入GPX/KML」). [file] is the app-private copy
 *  (also its identity); [serverId] is set while it's shared with the guardian page for the
 *  current hike. */
private data class LoadedRoute(val file: String, val name: String, val route: ParsedRoute, val serverId: Long?) {
    val pointCount get() = route.segments.sumOf { it.size }
    val firstPoint: GeoPoint get() = route.segments.firstOrNull()?.firstOrNull() ?: route.labels.first().point
    val summary get() = buildList {
        add("$pointCount 個點")
        if (route.segments.size > 1) add("${route.segments.size} 段")
        if (route.labels.isNotEmpty()) add("${route.labels.size} 個標註")
    }.joinToString("，")
}

/** Reopens the SAF file picker at the folder the hiker last picked a GPX/KML from, instead of
 *  the device's default root every time — reads [Prefs.lastGpxFolderUri] fresh on each launch. */
private class OpenDocumentsAtLastFolder(private val prefs: Prefs) : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: android.content.Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        prefs.lastGpxFolderUri?.let { intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it)) }
        return intent
    }
}

/** ⛰️ for the Taiwan RudyMap pack, 🏔️ for every other offline pack (Annapurna, imported maps). */
private fun mapSourceIcon(source: MapSource, packId: String?): String = when (source) {
    MapSource.OPENSTREETMAP -> "🗺️"
    MapSource.OFFLINE -> if (packId == TAIWAN_PACK_ID) "⛰️" else "🏔️"
}

/** Default GPX track name shown in the 結束追蹤 dialog — yyyy-mm-dd-hh-mm-ss per spec. */

/**
 * Runs [content] as its own composable lambda, i.e. its own JVM/dex method. HikeScreen had grown
 * into one ~22k-instruction method, and 4.6 crashed at launch on a vivo (Android 15) with a
 * `remember`ed state read as null deep inside it — not reproducible on an x86 emulator, so most
 * likely the phone's runtime mishandling that huge method. Each dialog lives in one of these now.
 */
@Composable
private fun Isolated(content: @Composable () -> Unit) = content()

/** e.g. "4.9" — shown at the bottom of ☰ and on the home screen. */
fun appVersionName(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

/** Raw exception messages ("timeout", "Unable to resolve host…") aren't useful to a hiker. */
private fun friendlyErrorMessage(e: Exception): String = when (e) {
    is SocketTimeoutException -> "連線逾時，山區訊號較弱時常見，請稍後再試一次"
    is UnknownHostException -> "連不上伺服器，請確認網路連線"
    is IOException -> "網路連線失敗，請稍後再試一次"
    else -> e.message ?: "發生未知錯誤"
}

/**
 * Prompts the system dialog to exempt this app from battery optimization, so Android is
 * less likely to kill the background GPS service mid-hike. No-op if already exempted or
 * the device doesn't offer a matching activity (some OEM ROMs strip this).
 */
private fun requestBackgroundExecutionExemption(context: android.content.Context) {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        )
    } catch (_: ActivityNotFoundException) {
        // OEM without this dialog — nothing more we can do from here.
    }
}

/** A row of selectable choices — used by the 軌跡記錄設定 dialog. */
@Composable
private fun OptionChips(options: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { v ->
            OutlinedButton(
                onClick = { onSelect(v) },
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.weight(1f),
                colors = if (v == selected) {
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else ButtonDefaults.outlinedButtonColors(),
            ) { Text(label(v), fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
    }
}

private fun gpxIntervalLabel(seconds: Int) = if (seconds == 0) "持續" else "${seconds}秒"

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Small compass: the card rotates so its red N points to real north. */
@Composable
private fun MiniCompass(azimuthDeg: Float, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(Color(0xFF424242), radius = r)
        drawCircle(Color.White, radius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        rotate(-azimuthDeg) {
            val c = center
            val north = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x, c.y - r * 0.8f); lineTo(c.x - r * 0.22f, c.y); lineTo(c.x + r * 0.22f, c.y); close()
            }
            val south = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x, c.y + r * 0.8f); lineTo(c.x - r * 0.22f, c.y); lineTo(c.x + r * 0.22f, c.y); close()
            }
            drawPath(north, Color(0xFFE53935))
            drawPath(south, Color(0xFFEEEEEE))
        }
    }
}

private fun compassDirection(deg: Float): String =
    listOf("北", "東北", "東", "東南", "南", "西南", "西", "西北")[(((deg + 22.5f) % 360f) / 45f).toInt()]

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/** Shortest distance (m) from a point to any of the given polylines — local equirectangular
 *  projection, accurate to well under a meter at route scale. Null when there are no lines. */
private fun distanceToLinesM(lat: Double, lon: Double, lines: List<List<GeoPoint>>): Double? {
    val mPerDegLat = 111_320.0
    val mPerDegLon = 111_320.0 * Math.cos(Math.toRadians(lat))
    var best = Double.MAX_VALUE
    for (line in lines) {
        for (i in 0 until line.size - 1) {
            val ax = (line[i].longitude - lon) * mPerDegLon; val ay = (line[i].latitude - lat) * mPerDegLat
            val bx = (line[i + 1].longitude - lon) * mPerDegLon; val by = (line[i + 1].latitude - lat) * mPerDegLat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            best = minOf(best, px * px + py * py)
        }
    }
    return if (best == Double.MAX_VALUE) null else Math.sqrt(best)
}

/** 量距 line shows once the map centre is this far from the GPS fix (GPS jitter / follow lag stay below). */
private const val MEASURE_MIN_M = 20f

private fun formatDistance(m: Double): String = if (m < 1000) "${m.toInt()} m" else "%.2f km".format(m / 1000)

/** Snaps to the fixed [INTERVAL_PRESETS] list rather than any continuous value. */
@Composable
private fun IntervalSlider(seconds: Int, onSecondsChange: (Int) -> Unit, onChangeFinished: () -> Unit = {}) {
    val index = INTERVAL_PRESETS.indexOfFirst { it.first == seconds }.let { if (it < 0) 1 else it }
    Text("定位頻率：每 ${intervalLabel(seconds)} 一筆", style = MaterialTheme.typography.bodyMedium)
    // Dragging a thumb to land exactly on one of 8 closely-spaced stops is fiddly on a
    // narrow dialog — a fat-finger drag can overshoot past a step and never quite settle
    // there. Step buttons guarantee every preset is reachable regardless of touch precision;
    // the slider stays for quick coarse jumps.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(
            onClick = {
                if (index > 0) {
                    onSecondsChange(INTERVAL_PRESETS[index - 1].first)
                    onChangeFinished()
                }
            },
            enabled = index > 0,
        ) { Text("－") }
        Slider(
            modifier = Modifier.weight(1f),
            value = index.toFloat(),
            onValueChange = { onSecondsChange(INTERVAL_PRESETS[it.toInt()].first) },
            onValueChangeFinished = onChangeFinished,
            valueRange = 0f..(INTERVAL_PRESETS.size - 1).toFloat(),
            steps = INTERVAL_PRESETS.size - 2,
        )
        IconButton(
            onClick = {
                if (index < INTERVAL_PRESETS.size - 1) {
                    onSecondsChange(INTERVAL_PRESETS[index + 1].first)
                    onChangeFinished()
                }
            },
            enabled = index < INTERVAL_PRESETS.size - 1,
        ) { Text("＋") }
    }
}

/**
 * Big red circle, not a tap — SOS is consequential enough that it shouldn't fire from a
 * stray touch. Holding fills the ring over [HOLD_MS]; releasing early cancels with no effect.
 */
@Composable
private fun SosHoldButton(
    outerSize: Dp = 200.dp,
    showCaption: Boolean = true,
    onTriggered: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(0f) }
    val holdMs = 3000L
    val innerSize = outerSize * 0.84f
    val textSizeSp = (outerSize.value * 0.2f).sp

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(outerSize)
                .pointerInput(Unit) {
                    // Not detectTapGestures: this sits inside a verticalScroll Column, and a
                    // plain onPress there gets its gesture stolen by the ancestor scroll on any
                    // natural finger tremor during a deliberate 3-second hold — tryAwaitRelease()
                    // returns early, the timer job gets cancelled, and the hold silently never
                    // fires. Manually draining and consuming every pointer event for the whole
                    // gesture keeps the scroll container from ever claiming it.
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        val job = scope.launch {
                            val start = System.currentTimeMillis()
                            while (isActive) {
                                val elapsed = System.currentTimeMillis() - start
                                progress = (elapsed.toFloat() / holdMs).coerceIn(0f, 1f)
                                if (elapsed >= holdMs) {
                                    onTriggered()
                                    break
                                }
                                delay(16)
                            }
                        }
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        job.cancel()
                        progress = 0f
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Custom-drawn arc, not CircularProgressIndicator: the stock indicator's default
            // track/indicator colors were low-contrast against the red circle and gave no
            // perceptible feedback during a hold — a hiker with a finger held down had no way
            // to tell the press had registered at all.
            Canvas(modifier = Modifier.matchParentSize()) {
                val strokePx = 10.dp.toPx()
                drawArc(
                    color = Color(0x40FFFFFF),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    topLeft = Offset(strokePx / 2f, strokePx / 2f),
                    size = Size(size.width - strokePx, size.height - strokePx),
                )
                if (progress > 0f) {
                    drawArc(
                        color = Color.Yellow,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                        topLeft = Offset(strokePx / 2f, strokePx / 2f),
                        size = Size(size.width - strokePx, size.height - strokePx),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(innerSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "SOS",
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = textSizeSp,
                )
            }
        }
        if (showCaption) {
            Spacer(Modifier.height(4.dp))
            Text(
                "長按 3 秒發送 SOS 求救",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op: user can retry the start button if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ensurePermissions()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var loggedIn by remember { mutableStateOf(prefs.isLoggedIn) }
                    if (loggedIn) {
                        HikeScreen(prefs, onLoggedOut = { loggedIn = false })
                    } else {
                        LoginScreen(prefs) { loggedIn = true }
                    }
                }
            }
        }
    }

    private fun ensurePermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }
}

@Composable
fun LoginScreen(prefs: Prefs, onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var isRegisterMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(prefs.lastEmail ?: "") }
    var password by remember { mutableStateOf(prefs.lastPassword ?: "") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var forgotPasswordEmail by remember { mutableStateOf(prefs.lastEmail ?: "") }

    if (showForgotPasswordDialog) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showForgotPasswordDialog = false },
            title = { Text("忘記密碼") },
            text = {
                Column {
                    Text("輸入帳號 Email，我們會寄送重設密碼連結過去。", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = forgotPasswordEmail, onValueChange = { forgotPasswordEmail = it },
                        label = { Text("Email") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showForgotPasswordDialog = false
                    val targetEmail = forgotPasswordEmail
                    scope.launch {
                        try {
                            ApiClient.service.forgotPassword(ForgotPasswordRequest(targetEmail))
                            Toast.makeText(context, "如果這個帳號存在，重設密碼信件已寄出，請至信箱查看", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, friendlyErrorMessage(e), Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("寄送") }
            },
            dismissButton = { TextButton(onClick = { showForgotPasswordDialog = false }) { Text("取消") } },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(if (isRegisterMode) "註冊帳號" else "登入", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        if (isRegisterMode) {
            OutlinedTextField(
                value = displayName, onValueChange = { displayName = it },
                label = { Text("暱稱") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("密碼") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !loading,
            onClick = {
                error = null
                loading = true
                scope.launch {
                    try {
                        if (isRegisterMode) {
                            val res = ApiClient.service.register(RegisterRequest(email, password, displayName))
                            if (!res.isSuccessful) throw Exception(res.errorBody()?.string() ?: "註冊失敗")
                            isRegisterMode = false
                            error = "註冊成功，請登入"
                        } else {
                            val res = ApiClient.service.login(LoginRequest(email, password))
                            if (!res.isSuccessful) throw Exception("帳號或密碼錯誤")
                            val body = res.body()!!
                            prefs.authToken = body.token
                            prefs.shareToken = body.user.shareToken
                            prefs.lastEmail = email
                            prefs.lastPassword = password
                            onLoggedIn()
                        }
                    } catch (e: Exception) {
                        error = friendlyErrorMessage(e)
                    } finally {
                        loading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (isRegisterMode) "註冊" else "登入") }

        TextButton(onClick = { isRegisterMode = !isRegisterMode; error = null }) {
            Text(if (isRegisterMode) "已經有帳號？改為登入" else "還沒有帳號？註冊一個")
        }

        if (!isRegisterMode) {
            TextButton(onClick = {
                forgotPasswordEmail = email.ifBlank { prefs.lastEmail ?: "" }
                showForgotPasswordDialog = true
            }) { Text("忘記密碼？") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HikeScreen(prefs: Prefs, onLoggedOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasActiveHike by remember { mutableStateOf(prefs.hasActiveHike) }
    val shareToken = prefs.shareToken // persistent per account, set at login — same link across every hike
    // Restored from prefs: it used to live only in memory, so after the app restarted the status
    // bar said 尚未開始行程 while the trip was still running.
    var hikeName by remember { mutableStateOf(prefs.activeHikeName ?: "") }
    var nickname by remember { mutableStateOf(prefs.lastNickname) }
    // null = choosing 開始新行程/接續舊行程; "new"/"continue" = filling in the form for that choice.
    var startMode by remember { mutableStateOf<String?>(null) }
    var continuingHikeId by remember { mutableStateOf<Long?>(null) }
    var continuingNeedsReactivation by remember { mutableStateOf(false) }
    var intervalSeconds by remember { mutableStateOf(prefs.intervalSeconds) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showBackgroundExecDialog by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var showStartHikeDialog by remember { mutableStateOf(false) } // wraps the 開始新行程/接續舊行程 flow
    var showEndTripConfirm by remember { mutableStateOf(false) }
    var showStartGpxDialog by remember { mutableStateOf(false) }
    var showStopGpxConfirm by remember { mutableStateOf(false) }
    var showFunctionMenu by remember { mutableStateOf(false) }
    var navTab by remember { mutableStateOf("map") }   // 底部導覽列: map / trip / track / settings
    var toolsOpen by remember { mutableStateOf(false) } // 工具 button expanded
    var mapController by remember { mutableStateOf<HikeMapController?>(null) }
    var gpsFollowing by remember { mutableStateOf(true) }
    var gpsHasFix by remember { mutableStateOf(false) } // false while "connecting" — drives the pulse animation
    var zoomLevelDisplay by remember { mutableStateOf("—") } // shown between the ＋/－ buttons
    var gpxRecording by remember { mutableStateOf(prefs.isGpxRecording) }
    var gpxPaused by remember { mutableStateOf(prefs.isGpxPaused) }
    var showTrackSettingsDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showLoadRouteDialog by remember { mutableStateOf(false) }
    var showMapSettingsDialog by remember { mutableStateOf(false) }
    var scaleBarEnabled by remember { mutableStateOf(prefs.showScaleBar) }
    var hillshadingEnabled by remember { mutableStateOf(prefs.showHillshading) }
    var enabledLayerIds by remember {
        mutableStateOf(prefs.enabledMapLayerIds ?: HIKING_THEME_LAYERS.filter { it.defaultEnabled }.map { it.id }.toSet())
    }
    var showLayerSettingsDialog by remember { mutableStateOf(false) }
    var showLoraDevicePoints by remember { mutableStateOf(prefs.showLoraDevicePoints) }
    val loraDevicePoints = remember { mutableStateListOf<LoraDevicePoint>() }
    var mapOrientationMode by remember {
        mutableStateOf(
            when (prefs.mapOrientationMode) {
                "track" -> MapOrientationMode.TRACK_UP
                "compass" -> MapOrientationMode.COMPASS_UP
                else -> MapOrientationMode.NORTH_UP
            }
        )
    }
    val mapsforgeDownloader = remember { MapsforgeDownloader(context) }
    var packRevision by remember { mutableStateOf(0) } // bumped after a pack is downloaded/imported/deleted
    val offlinePacks = remember(packRevision) { mapsforgeDownloader.allPacks() }
    val installedPackIds = remember(packRevision) {
        offlinePacks.filter { mapsforgeDownloader.isInstalled(it) }.map { it.id }.toSet()
    }
    // 每次開啟都從魯地圖開始：台灣離線包已安裝就用它，否則只能先用 OpenStreetMap。
    var currentOfflinePackId by remember { mutableStateOf(TAIWAN_PACK_ID) }
    var currentMapSource by remember {
        mutableStateOf(if (TAIWAN_PACK_ID in installedPackIds) MapSource.OFFLINE else MapSource.OPENSTREETMAP)
    }
    val currentOfflinePack = offlinePacks.firstOrNull { it.id == currentOfflinePackId }
    var offlineMapController by remember { mutableStateOf<OfflineMapController?>(null) }
    // (downloadedBytes, totalBytes, currentFile), null = not downloading; one pack at a time.
    var mapsforgeDownloadState by remember { mutableStateOf<Triple<Long, Long, String>?>(null) }
    var downloadingPackId by remember { mutableStateOf<String?>(null) }
    var packStatus by remember { mutableStateOf<Pair<String, List<MapsforgeDownloader.PackageStatus>>?>(null) }
    var checkingPackId by remember { mutableStateOf<String?>(null) }
    var packPendingDelete by remember { mutableStateOf<MapsforgeDownloader.OfflinePack?>(null) }
    val mapsforgeActive = currentMapSource == MapSource.OFFLINE && currentOfflinePackId in installedPackIds
    var showMapPicker by remember { mutableStateOf(false) }
    var mapTextSizePx by remember { mutableStateOf(prefs.mapTextSizePx) }
    var keepGpsCentered by remember { mutableStateOf(prefs.keepGpsCentered) }
    var showShareLinkDialog by remember { mutableStateOf(false) }
    // True while the current map was picked automatically (the startup default, or a switch because
    // the phone left the previous map's coverage). A manual pick sticks — e.g. browsing 安娜普納
    // from Taiwan — unless the phone was inside that map and then walks/flies out of it.
    var autoSwitchedMap by remember { mutableStateOf(true) }
    var lastCoverageCheck by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // (map, phone inside it)
    fun selectMapSource(source: MapSource, packId: String? = null, auto: Boolean = false) {
        currentMapSource = source
        if (packId != null) currentOfflinePackId = packId
        autoSwitchedMap = auto
        mapController = null
        offlineMapController = null
    }

    // Follows the phone across map coverage: outside the current offline pack → another installed
    // pack that covers it (Taiwan first), else OSM; back inside a pack after an automatic switch →
    // that pack. Low-frequency fixes are plenty for this; the map's own GPS dot uses its own feed.
    val packCoverage = remember(packRevision) {
        offlinePacks.filter { it.id in installedPackIds }.associate { it.id to mapsforgeDownloader.coverage(it) }
    }
    var phoneLocation by remember { mutableStateOf<android.location.Location?>(null) }
    // Precise and frequent while 偏離航道 is being watched (set further down); otherwise coarse and cheap.
    var watchOffRoute by remember { mutableStateOf(false) }
    DisposableEffect(watchOffRoute) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { phoneLocation = result.lastLocation ?: return }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedClient.lastLocation.addOnSuccessListener { if (it != null && phoneLocation == null) phoneLocation = it }
            fusedClient.requestLocationUpdates(
                if (watchOffRoute) LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).build()
                else LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000L).build(),
                callback, Looper.getMainLooper(),
            )
        }
        onDispose { fusedClient.removeLocationUpdates(callback) }
    }
    LaunchedEffect(phoneLocation, packCoverage) {
        val loc = phoneLocation ?: return@LaunchedEffect
        fun covers(packId: String) = packCoverage[packId]?.contains(loc.latitude, loc.longitude) == true
        val coveringPack = offlinePacks
            .filter { it.id in installedPackIds && covers(it.id) }
            .minByOrNull { if (it.id == TAIWAN_PACK_ID) 0 else 1 }
        val currentPackName = offlinePacks.firstOrNull { it.id == currentOfflinePackId }?.name
        val onOfflinePack = currentMapSource == MapSource.OFFLINE && packCoverage.containsKey(currentOfflinePackId)
        val mapKey = if (currentMapSource == MapSource.OFFLINE) currentOfflinePackId else "osm"
        val insideCurrent = !onOfflinePack || covers(currentOfflinePackId)
        val justLeft = lastCoverageCheck?.let { (key, wasInside) -> key == mapKey && wasInside && !insideCurrent } == true
        lastCoverageCheck = mapKey to insideCurrent
        when {
            onOfflinePack && !insideCurrent && (autoSwitchedMap || justLeft) -> {
                if (coveringPack != null) selectMapSource(MapSource.OFFLINE, coveringPack.id, auto = true)
                else selectMapSource(MapSource.OPENSTREETMAP, auto = true)
                Toast.makeText(
                    context,
                    "目前位置不在「$currentPackName」範圍，已自動切換到「${coveringPack?.name ?: "OpenStreetMap"}」",
                    Toast.LENGTH_LONG,
                ).show()
            }
            currentMapSource == MapSource.OPENSTREETMAP && autoSwitchedMap && coveringPack != null -> {
                selectMapSource(MapSource.OFFLINE, coveringPack.id, auto = true)
                Toast.makeText(context, "已回到「${coveringPack.name}」範圍，自動切換回離線地圖", Toast.LENGTH_LONG).show()
            }
        }
    }
    var gpxMinIntervalSec by remember { mutableStateOf(prefs.gpxMinIntervalSec) }
    var gpxMinDistanceM by remember { mutableStateOf(prefs.gpxMinDistanceM) }
    var gpxStopName by remember { mutableStateOf("") }
    var backgroundExecutionEnabled by remember { mutableStateOf(prefs.backgroundExecutionEnabled) }
    var isPaused by remember { mutableStateOf(prefs.isPaused) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    // 載入 GPX/KML — reference routes drawn on whichever map is showing. Each picked file is copied
    // into app storage, so reloading after an app restart never depends on the original file's URI
    // permission still being valid. While a hike is active, a route is also shared with the
    // guardian page (serverId) and unshared when removed; the server drops them all at hike end.
    val routesDir = remember { File(context.filesDir, "routes").apply { mkdirs() } }
    val loadedRoutes = remember { mutableStateListOf<LoadedRoute>() }
    var importingRoutes by remember { mutableStateOf(false) }
    // Routes ticked in the 開始新行程 dialog to share once the hike is created.
    val routesToShare = remember { mutableStateListOf<String>() }

    fun persistRoutes() {
        prefs.loadedRouteFiles = loadedRoutes.map { it.file }
        prefs.routeServerIds = loadedRoutes.mapNotNull { r -> r.serverId?.let { r.file to it } }.toMap()
    }

    suspend fun shareRoute(file: String) {
        val route = loadedRoutes.firstOrNull { it.file == file } ?: return
        if (!prefs.hasActiveHike || route.serverId != null) return
        try {
            val text = withContext(Dispatchers.IO) { File(route.file).readText() }
            val res = ApiClient.service.uploadRoute(
                "Bearer ${prefs.authToken}", prefs.activeHikeId, route.name,
                text.toRequestBody("application/xml".toMediaTypeOrNull()),
            )
            val serverId = res.body()?.id ?: throw Exception("伺服器回應 ${res.code()}")
            val index = loadedRoutes.indexOfFirst { it.file == file }
            if (index >= 0) loadedRoutes[index] = loadedRoutes[index].copy(serverId = serverId)
            persistRoutes()
        } catch (e: Exception) {
            Toast.makeText(context, "「${route.name}」同步給留守人失敗：${friendlyErrorMessage(e)}", Toast.LENGTH_LONG).show()
        }
    }

    fun removeRoute(route: LoadedRoute) {
        loadedRoutes.removeAll { it.file == route.file }
        routesToShare.remove(route.file)
        persistRoutes()
        File(route.file).delete()
        val serverId = route.serverId ?: return
        val hikeId = prefs.activeHikeId
        scope.launch {
            runCatching { ApiClient.service.deleteRoute("Bearer ${prefs.authToken}", hikeId, serverId) }
        }
    }

    /** Copies and parses the picked files; returns the app-private paths of the ones that loaded. */
    suspend fun importRoutes(uris: List<Uri>): List<String> {
        importingRoutes = true
        val added = mutableListOf<String>()
        for (uri in uris) {
            try {
                val route = withContext(Dispatchers.IO) {
                    val displayName = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: uri.lastPathSegment ?: "路線.gpx"
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw Exception("無法讀取檔案")
                    if (!(text.contains("<gpx", true) || text.contains("<kml", true))) throw Exception("不是 GPX 或 KML 檔")
                    val parsed = parseRoute(text)
                    if (parsed.segments.isEmpty() && parsed.labels.isEmpty()) throw Exception("檔案裡沒有找到任何座標點")
                    val file = File(routesDir, "${System.currentTimeMillis()}_${displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")}")
                    file.writeText(text)
                    LoadedRoute(file.absolutePath, displayName, parsed, null)
                }
                loadedRoutes.add(route)
                added += route.file
            } catch (e: Exception) {
                Log.e("LoadRoute", "載入 GPX/KML 失敗：$uri", e)
                Toast.makeText(context, "載入失敗：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        persistRoutes()
        importingRoutes = false
        return added
    }

    LaunchedEffect(Unit) {
        if (loadedRoutes.isNotEmpty()) return@LaunchedEffect
        val serverIds = prefs.routeServerIds
        val restored = withContext(Dispatchers.IO) {
            prefs.loadedRouteFiles.mapNotNull { path ->
                val f = File(path)
                if (!f.exists()) return@mapNotNull null
                runCatching { LoadedRoute(path, f.name.substringAfter('_'), parseRoute(f.readText()), serverIds[path]) }.getOrNull()
            }
        }
        loadedRoutes.addAll(restored)
        persistRoutes()
    }

    // Redraws every loaded route whenever the map instance or the route list changes — switching
    // maps (or offline packs) creates a brand-new map view that has no overlays of its own.
    val hiddenRoutes = remember { mutableStateListOf<String>().apply { addAll(prefs.hiddenRouteFiles) } }
    val visibleRoutes = loadedRoutes.filter { it.file !in hiddenRoutes }
    val loadedRouteKeys = visibleRoutes.joinToString("\n") { it.file }
    LaunchedEffect(mapController, offlineMapController, loadedRouteKeys) {
        mapController?.let { c -> c.clearAllLoadedRoutes(); visibleRoutes.forEach { c.addLoadedRoute(it.file, it.route) } }
        offlineMapController?.let { c -> c.clearAllLoadedRoutes(); visibleRoutes.forEach { c.addLoadedRoute(it.file, it.route) } }
    }

    // ---- 偏離航道：distance from the phone to the nearest visible route line ----
    var offRouteEnabled by remember { mutableStateOf(prefs.offRouteAlertEnabled) }
    var offRouteThresholdM by remember { mutableStateOf(prefs.offRouteDistanceM) }
    var distanceToRouteM by remember { mutableStateOf<Double?>(null) }
    var wasOffRoute by remember { mutableStateOf(false) }
    val onTrip = hasActiveHike || gpxRecording
    SideEffect { watchOffRoute = onTrip && offRouteEnabled && visibleRoutes.isNotEmpty() }
    LaunchedEffect(phoneLocation, loadedRouteKeys, offRouteEnabled, onTrip) {
        val loc = phoneLocation
        if (!offRouteEnabled || !onTrip || loc == null || visibleRoutes.isEmpty()) { distanceToRouteM = null; wasOffRoute = false; return@LaunchedEffect }
        val segments = visibleRoutes.flatMap { it.route.segments }
        val d = withContext(Dispatchers.Default) { distanceToLinesM(loc.latitude, loc.longitude, segments) }
        distanceToRouteM = d
        val off = d != null && d > offRouteThresholdM
        if (off && !wasOffRoute) {
            // A vibration failure must never take the map screen down with it.
            runCatching {
                context.getSystemService(android.os.Vibrator::class.java)
                    ?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
            }
        }
        wasOffRoute = off
    }

    var importingMap by remember { mutableStateOf(false) }
    val mapImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importingMap = true
        scope.launch {
            try {
                val displayName = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: uri.lastPathSegment ?: ""
                val input = context.contentResolver.openInputStream(uri) ?: throw Exception("無法讀取檔案")
                val added = mapsforgeDownloader.importMap(input, displayName)
                packRevision++
                Toast.makeText(context, "已匯入：${added.joinToString()}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "匯入失敗：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                importingMap = false
            }
        }
    }

    // ---- 行程 = 回報給留守人 + GPX 記錄，一起開始／暫停／結束 ----
    fun gpxServiceAction(action: String) {
        context.startService(Intent(context, LocationForegroundService::class.java).setAction(action))
    }

    /** Stops the recording after the 結束行程 save dialog. [output] null (dialog cancelled) still
     *  stops it — the trail is kept in the app and can be exported later. */
    fun finishRecording(output: Uri?) {
        gpxRecording = false; gpxPaused = false
        context.startService(
            Intent(context, LocationForegroundService::class.java)
                .setAction(LocationForegroundService.ACTION_GPX_STOP)
                .putExtra(LocationForegroundService.EXTRA_GPX_NAME, gpxStopName)
                .putExtra(LocationForegroundService.EXTRA_GPX_FORMAT, "gpx")
                .apply { if (output != null) putExtra(LocationForegroundService.EXTRA_GPX_OUTPUT_URI, output.toString()) }
        )
    }
    val saveGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { finishRecording(it) }

    fun startRecording(resumeExisting: Boolean) {
        gpxRecording = true; gpxPaused = false
        context.startForegroundService(
            Intent(context, LocationForegroundService::class.java)
                .setAction(LocationForegroundService.ACTION_GPX_START)
                .putExtra(LocationForegroundService.EXTRA_GPX_RESUME_EXISTING, resumeExisting)
                .putExtra(LocationForegroundService.EXTRA_GPX_TITLE, if (hasActiveHike) hikeName else "")
        )
    }

    // 行程 (reporting to the guardian, started/ended from ☰) and GPX 記錄 (the ⏺ buttons on the map)
    // are independent; "a trip is on" for the stats panel, waypoints etc. means either of them.
    val tripActive = hasActiveHike || gpxRecording

    /** The stats panel's 移動時間 stops counting while everything that's running is paused. */
    fun updatePauseClock(hikePausedNow: Boolean, gpxPausedNow: Boolean) {
        val allPaused = (!hasActiveHike || hikePausedNow) && (!gpxRecording || gpxPausedNow)
        val now = System.currentTimeMillis()
        if (allPaused) {
            if (prefs.tripPausedSince == 0L) prefs.tripPausedSince = now
        } else if (prefs.tripPausedSince != 0L) {
            prefs.tripPausedTotalMs += now - prefs.tripPausedSince
            prefs.tripPausedSince = 0L
        }
    }

    fun setHikePaused(pause: Boolean) {
        if (!hasActiveHike) return
        isPaused = pause; prefs.isPaused = pause
        gpxServiceAction(if (pause) LocationForegroundService.ACTION_PAUSE else LocationForegroundService.ACTION_RESUME)
        updatePauseClock(pause, gpxPaused)
        Toast.makeText(context, if (pause) "⏸ 行程回報已暫停" else "▶ 行程回報繼續", Toast.LENGTH_SHORT).show()
    }

    fun setGpxPaused(pause: Boolean) {
        if (!gpxRecording) return
        gpxPaused = pause; prefs.isGpxPaused = pause
        gpxServiceAction(if (pause) LocationForegroundService.ACTION_GPX_PAUSE else LocationForegroundService.ACTION_GPX_RESUME)
        updatePauseClock(isPaused, pause)
        Toast.makeText(context, if (pause) "⏸ 軌跡記錄已暫停" else "⏺ 軌跡記錄繼續", Toast.LENGTH_SHORT).show()
    }

    // Trip clock + step baseline start when a trip begins (any path) and reset when it ends.
    val stepPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(tripActive) {
        if (tripActive) {
            if (prefs.tripStartedAt == 0L) {
                prefs.tripStartedAt = System.currentTimeMillis()
                prefs.tripPausedTotalMs = 0L
                prefs.tripPausedSince = 0L
                prefs.stepBaseline = -1f
                prefs.stepCarry = 0f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
            ) stepPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            prefs.tripStartedAt = 0L
        }
    }

    fun endTrip() {
        if (hasActiveHike) {
            // Local stop always proceeds immediately; the server-side end is handed to
            // HikeActionWorker, which retries until delivered (or permanently rejected).
            HikeActionWorker.enqueue(context, prefs.activeHikeId, HikeActionWorker.ACTION_END)
            gpxServiceAction(LocationForegroundService.ACTION_STOP)
            prefs.clearActiveHike()
            // The server deletes this hike's shared routes when it ends.
            for (i in loadedRoutes.indices) loadedRoutes[i] = loadedRoutes[i].copy(serverId = null)
            persistRoutes()
            hasActiveHike = false
            isPaused = false
            TrackerWidgetProvider.updateAllWidgets(context)
            Toast.makeText(context, "🏁 行程已結束" + if (gpxRecording) "（軌跡記錄仍在進行）" else "", Toast.LENGTH_LONG).show()
        }
    }

    /** ⏹ 停止記錄 (after its confirm): pause so no more points land, then the save dialog finishes it. */
    fun stopGpxWithSave() {
        if (!gpxRecording) return
        gpxServiceAction(LocationForegroundService.ACTION_GPX_PAUSE)
        gpxPaused = true
        // Same 行程名稱-yyyyMMdd-HHmmss the file got when the recording started.
        gpxStopName = prefs.gpxFilePath?.let { File(it).nameWithoutExtension }
            ?: trackFileBaseName(hikeName, System.currentTimeMillis())
        saveGpxLauncher.launch("$gpxStopName.gpx")
    }

    // The recorder lives in the service; after the process was killed or the app updated, nothing
    // restarts it on its own even though prefs still say 記錄中 — reopening the app does.
    LaunchedEffect(Unit) {
        val token = prefs.authToken
        if (prefs.hasActiveHike && prefs.activeHikeName == null && token != null) {
            val name = runCatching {
                ApiClient.service.listHikes("Bearer $token").body()?.firstOrNull { it.id == prefs.activeHikeId }?.name
            }.getOrNull()
            if (name != null) { prefs.activeHikeName = name; hikeName = name }
        }
        if (!prefs.trackNamesMigrated) {
            val renamed = withContext(Dispatchers.IO) {
                migrateTrackFileNames(context, skipPath = if (prefs.isGpxRecording) prefs.gpxFilePath else null)
            }
            prefs.gpxFilePath?.let { p -> renamed[p]?.let { prefs.gpxFilePath = it } }
            prefs.lastFinishedGpxPath?.let { p -> renamed[p]?.let { prefs.lastFinishedGpxPath = it } }
            prefs.trackNamesMigrated = true
        }
        if (prefs.isGpxRecording) {
            context.startForegroundService(
                Intent(context, LocationForegroundService::class.java).setAction(LocationForegroundService.ACTION_START)
            )
        }
    }

    // Multi-select. Picking from the 開始新行程 dialog ticks the new routes for sharing; picking
    // during an active hike shares them right away; otherwise they're local-only for now.
    val routePickerLauncher = rememberLauncherForActivityResult(remember { OpenDocumentsAtLastFolder(prefs) }) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        prefs.lastGpxFolderUri = uris.first().toString() // reopens the picker here next time
        val fromStartDialog = showStartHikeDialog
        if (!fromStartDialog) showLoadRouteDialog = true
        scope.launch {
            val added = importRoutes(uris)
            if (fromStartDialog) routesToShare.addAll(added)
            else if (prefs.hasActiveHike) added.forEach { shareRoute(it) }
        }
    }

    if (showLoadRouteDialog) Isolated {
        PanelDialog(
            onDismissRequest = { showLoadRouteDialog = false },
            title = { Text("軌跡檔管理") },
            text = {
                Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        if (hasActiveHike) "行程進行中：載入的路線會同步顯示在留守人的地圖上，移除時也會一併移除；行程結束後自動從伺服器刪除。"
                        else "目前沒有進行中的行程，路線只顯示在這支手機；開始新行程時可以勾選要同步給留守人的路線。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            offRouteEnabled = !offRouteEnabled; prefs.offRouteAlertEnabled = offRouteEnabled
                        },
                    ) {
                        Checkbox(checked = offRouteEnabled, onCheckedChange = null)
                        Text("偏離航道提醒（行程中，離顯示中的路線超過下列距離就提醒並震動）", style = MaterialTheme.typography.bodySmall)
                    }
                    if (offRouteEnabled) {
                        OptionChips(OFF_ROUTE_DISTANCE_OPTIONS, offRouteThresholdM, { "${it}米" }) {
                            offRouteThresholdM = it; prefs.offRouteDistanceM = it
                        }
                    }
                    if (importingRoutes) {
                        Spacer(Modifier.height(8.dp))
                        Text("載入中…", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    loadedRoutes.forEach { route ->
                        Spacer(Modifier.height(8.dp))
                        val shown = route.file !in hiddenRoutes
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (shown) hiddenRoutes.add(route.file) else hiddenRoutes.remove(route.file)
                                prefs.hiddenRouteFiles = hiddenRoutes.toSet()
                            },
                        ) {
                            Checkbox(checked = shown, onCheckedChange = null)
                            Text(
                                "${route.name}（${route.summary}）" + if (route.serverId != null) "・已同步給留守人" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                if (mapsforgeActive) {
                                    offlineMapController?.animateTo(route.firstPoint.latitude, route.firstPoint.longitude)
                                } else {
                                    mapController?.animateTo(route.firstPoint, 16.0)
                                }
                                showLoadRouteDialog = false
                            }) { Text("跳到起點") }
                            OutlinedButton(onClick = { removeRoute(route) }) { Text("移除") }
                        }
                    }
                    if (loadedRoutes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            if (mapsforgeActive) offlineMapController?.recenterOnGps() else mapController?.recenterOnGps()
                            showLoadRouteDialog = false
                        }) { Text("跳到目前位置") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !importingRoutes, onClick = { routePickerLauncher.launch(arrayOf("*/*")) }) { Text("新增檔案") }
            },
            dismissButton = {
                Row {
                    if (loadedRoutes.isNotEmpty()) {
                        TextButton(onClick = { loadedRoutes.toList().forEach { removeRoute(it) } }) { Text("全部移除") }
                    }
                    TextButton(onClick = { showLoadRouteDialog = false }) { Text("關閉") }
                }
            },
        )
    }

    // Applies once the map exists and whenever these settings change from elsewhere (e.g. a
    // fresh HikeMap instance after process recreation) — keeps the map in sync with prefs.
    LaunchedEffect(mapController, mapOrientationMode) {
        mapController?.setOrientationMode(mapOrientationMode)
    }

    val visibleLoraDevicePoints = if (showLoraDevicePoints) loraDevicePoints.toList() else emptyList()
    LaunchedEffect(mapController, offlineMapController, visibleLoraDevicePoints) {
        mapController?.setLoraDevicePoints(visibleLoraDevicePoints)
        offlineMapController?.setLoraDevicePoints(visibleLoraDevicePoints)
    }

    packPendingDelete?.let { pack ->
        PanelDialog(
            onDismissRequest = { packPendingDelete = null },
            title = { Text("刪除離線地圖包") },
            text = { Text("確定刪除「${pack.name}」？刪除後要重新下載或匯入才能使用。") },
            confirmButton = {
                TextButton(onClick = {
                    if (currentMapSource == MapSource.OFFLINE && currentOfflinePackId == pack.id) {
                        selectMapSource(MapSource.OPENSTREETMAP)
                    }
                    mapsforgeDownloader.delete(pack)
                    packRevision++
                    packPendingDelete = null
                }) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { packPendingDelete = null }) { Text("取消") } },
        )
    }

    if (showMapSettingsDialog) Isolated {
        PanelDialog(
            onDismissRequest = { showMapSettingsDialog = false },
            title = { Text("地圖設定") },
            text = {
                Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("GPS 位置永遠置中", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "地圖跟著你的位置移動，讓你一直在畫面正中間；手動拖動地圖後 10 秒會自動回到置中。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = keepGpsCentered, onCheckedChange = { keepGpsCentered = it; prefs.keepGpsCentered = it })
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("地圖文字大小", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    OptionChips(MAP_TEXT_SIZE_OPTIONS, mapTextSizePx, { "${it}px" }) {
                        mapTextSizePx = it; prefs.mapTextSizePx = it
                    }
                    Text(
                        "套用在離線地圖的地名、步道等文字；線上地圖（OpenStreetMap）的文字大小無法調整。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("離線地圖包", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "每個地圖包各自獨立，沒有網路也能用；下載或匯入後，到地圖頁右上角的地圖選單選用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val packBytes = remember(packRevision) {
                        offlinePacks.associate { it.id to mapsforgeDownloader.installedBytes(it) }
                    }
                    offlinePacks.forEach { pack ->
                        val installed = pack.id in installedPackIds
                        Spacer(Modifier.height(10.dp))
                        Text(pack.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                installed -> "已安裝（${(packBytes[pack.id] ?: 0L) / 1_000_000}MB）"
                                pack.isImported -> "缺少地圖樣式檔，請先下載任一魯地圖包"
                                else -> "尚未下載" + (pack.sizeHint?.let { "，$it" } ?: "")
                            } + if (pack.isImported) "・自行匯入" else "・來源：rudymap.tw 公開載點",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        packStatus?.takeIf { it.first == pack.id }?.let { (_, statuses) ->
                            Text(
                                "載點確認：${statuses.size} 個檔案可讀，總計 ${statuses.sumOf { it.bytes } / 1_000_000}MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        val mfProgress = mapsforgeDownloadState
                        if (downloadingPackId == pack.id && mfProgress != null) {
                            val (done, total, currentFile) = mfProgress
                            Text(
                                if (total > 0) "下載中（$currentFile）：${done / 1_000_000}MB / ${total / 1_000_000}MB"
                                else "準備中…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            LinearProgressIndicator(
                                progress = { if (total > 0) done.toFloat() / total else 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(onClick = {
                                mapsforgeDownloader.cancel()
                                mapsforgeDownloadState = null
                                downloadingPackId = null
                            }) { Text("取消下載") }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                if (!pack.isImported) {
                                    OutlinedButton(
                                        enabled = checkingPackId == null,
                                        contentPadding = PaddingValues(horizontal = 10.dp),
                                        onClick = {
                                            checkingPackId = pack.id
                                            scope.launch {
                                                try {
                                                    packStatus = pack.id to mapsforgeDownloader.checkPackages(pack)
                                                } catch (e: Exception) {
                                                    packStatus = null
                                                    Toast.makeText(context, e.message ?: "載點確認失敗", Toast.LENGTH_LONG).show()
                                                } finally {
                                                    checkingPackId = null
                                                }
                                            }
                                        },
                                    ) { Text(if (checkingPackId == pack.id) "確認中…" else "確認載點") }
                                    Button(
                                        enabled = downloadingPackId == null,
                                        contentPadding = PaddingValues(horizontal = 10.dp),
                                        onClick = {
                                            downloadingPackId = pack.id
                                            mapsforgeDownloadState = Triple(0L, 0L, "")
                                            scope.launch {
                                                try {
                                                    mapsforgeDownloader.download(pack) { downloadedBytes, totalBytes, currentFile ->
                                                        mapsforgeDownloadState = Triple(downloadedBytes, totalBytes, currentFile)
                                                    }
                                                    packRevision++
                                                    Toast.makeText(context, "${pack.name} 下載完成", Toast.LENGTH_LONG).show()
                                                } catch (e: CancellationException) {
                                                    // cancelled from the 取消下載 button
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, e.message ?: "下載失敗", Toast.LENGTH_LONG).show()
                                                } finally {
                                                    mapsforgeDownloadState = null
                                                    downloadingPackId = null
                                                }
                                            }
                                        },
                                    ) { Text(if (installed) "一鍵更新" else "下載安裝") }
                                }
                                if (installed || pack.isImported) {
                                    OutlinedButton(
                                        enabled = downloadingPackId == null,
                                        contentPadding = PaddingValues(horizontal = 10.dp),
                                        onClick = { packPendingDelete = pack },
                                    ) { Text("刪除") }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        enabled = !importingMap && downloadingPackId == null,
                        onClick = { mapImportLauncher.launch(arrayOf("*/*")) },
                    ) { Text(if (importingMap) "匯入中…" else "從手機匯入地圖檔（.map／.zip）") }
                    Text(
                        "可匯入其他 Mapsforge 格式的地圖檔；會套用魯地圖的樣式顯示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(16.dp))
                    Text("LoRa / Meshtastic 節點", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("顯示裝置點位")
                            Text(
                                if (loraDevicePoints.isEmpty()) "地圖 overlay 已接好；目前尚未接入 Meshtastic/LoRa 節點資料來源。"
                                else "目前有 ${loraDevicePoints.size} 個節點可顯示。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = showLoraDevicePoints,
                            onCheckedChange = {
                                showLoraDevicePoints = it
                                prefs.showLoraDevicePoints = it
                            },
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("顯示設定", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("比例尺", modifier = Modifier.weight(1f))
                        Switch(
                            checked = scaleBarEnabled,
                            onCheckedChange = { scaleBarEnabled = it; prefs.showScaleBar = it },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("向量魯地圖山坡陰影")
                            Text(
                                "每塊圖磚都要多算一層地形陰影，關掉可以省效能。變更後要切換一次地圖圖層（或重新開啟App）才會生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = hillshadingEnabled,
                            onCheckedChange = { hillshadingEnabled = it; prefs.showHillshading = it },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showLayerSettingsDialog = true }) {
                        Text("向量魯地圖圖層顯示（${enabledLayerIds.size}/${HIKING_THEME_LAYERS.size}）")
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("地圖方向", style = MaterialTheme.typography.bodyMedium)
                    if (mapsforgeActive) {
                        Text(
                            "向量魯地圖引擎不支援旋轉地圖，固定「地圖北邊朝螢幕上方」；切換回線上地圖時可用其他兩種。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val orientationOptions = listOf(
                        MapOrientationMode.NORTH_UP to "地圖北邊朝螢幕上方",
                        MapOrientationMode.TRACK_UP to "行進方向朝螢幕上方",
                        MapOrientationMode.COMPASS_UP to "指北針朝螢幕上方",
                    )
                    orientationOptions.forEach { (mode, label) ->
                        val optionEnabled = !mapsforgeActive || mode == MapOrientationMode.NORTH_UP
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = optionEnabled) {
                                mapOrientationMode = mode
                                prefs.mapOrientationMode = when (mode) {
                                    MapOrientationMode.TRACK_UP -> "track"
                                    MapOrientationMode.COMPASS_UP -> "compass"
                                    MapOrientationMode.NORTH_UP -> "north"
                                }
                            },
                        ) {
                            RadioButton(selected = mapOrientationMode == mode, onClick = null, enabled = optionEnabled)
                            Text(label)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "目前縮放層級：$zoomLevelDisplay",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showMapSettingsDialog = false }) { Text("關閉") }
            },
        )
    }

    if (showLayerSettingsDialog) Isolated {
        // 開關先存在待確認清單裡，不會立刻套用；按「確認」才寫回 enabledLayerIds/prefs 並讓地圖
        // 用新圖層重新載入一次（key(enabledLayerIds) 那邊會處理 remount，不必再手動切換地圖/重開App）。
        var pendingLayerIds by remember(showLayerSettingsDialog) { mutableStateOf(enabledLayerIds) }
        PanelDialog(
            onDismissRequest = { showLayerSettingsDialog = false },
            title = { Text("向量魯地圖圖層顯示") },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "關掉不需要的圖層可以減少畫面雜訊、也能省一些渲染效能。選好後按「確認」套用。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    HIKING_THEME_LAYERS.forEach { layer ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                pendingLayerIds = if (layer.id in pendingLayerIds) {
                                    pendingLayerIds - layer.id
                                } else {
                                    pendingLayerIds + layer.id
                                }
                            },
                        ) {
                            Checkbox(checked = layer.id in pendingLayerIds, onCheckedChange = null)
                            Text(layer.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    enabledLayerIds = pendingLayerIds
                    prefs.enabledMapLayerIds = pendingLayerIds
                    showLayerSettingsDialog = false
                }) { Text("確認") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingLayerIds = HIKING_THEME_LAYERS.filter { it.defaultEnabled }.map { it.id }.toSet()
                    }) { Text("重設為預設值") }
                    TextButton(onClick = { showLayerSettingsDialog = false }) { Text("取消") }
                }
            },
        )
    }


    if (showExitConfirmDialog) Isolated {
        PanelDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("結束程式") },
            text = { Text("確定要完整結束 App 嗎？如果行程還在進行中，定位追蹤也會跟著停止。") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirmDialog = false
                    android.os.Process.killProcess(android.os.Process.myPid())
                }) { Text("結束") }
            },
            dismissButton = { TextButton(onClick = { showExitConfirmDialog = false }) { Text("取消") } },
        )
    }

    if (showIntervalDialog) Isolated {
        PanelDialog(
            onDismissRequest = { showIntervalDialog = false },
            title = { Text("定位頻率") },
            text = {
                Column { IntervalSlider(intervalSeconds, onSecondsChange = { intervalSeconds = it }) }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.intervalSeconds = intervalSeconds
                    context.startService(
                        Intent(context, LocationForegroundService::class.java)
                            .setAction(LocationForegroundService.ACTION_UPDATE_INTERVAL)
                    )
                    if (prefs.activeHikeId != -1L) {
                        val hikeId = prefs.activeHikeId
                        val token = prefs.authToken
                        if (token != null) {
                            scope.launch {
                                try {
                                    ApiClient.service.updateInterval(
                                        "Bearer $token", hikeId, IntervalRequest(intervalSeconds),
                                    )
                                } catch (_: Exception) {
                                    // Best-effort, same contract as pause-state: the family page just
                                    // keeps showing whatever interval it last successfully heard.
                                }
                            }
                        }
                    }
                    showIntervalDialog = false
                }) { Text("套用") }
            },
            dismissButton = { TextButton(onClick = { showIntervalDialog = false }) { Text("取消") } },
        )
    }

    if (showBackgroundExecDialog) Isolated {
        PanelDialog(
            onDismissRequest = { showBackgroundExecDialog = false },
            title = { Text("背景執行") },
            text = {
                Column {
                    Text(
                        "開始行程時請求系統排除電池優化限制，降低 Android 在背景把定位服務關掉的機率。" +
                            "部分機型仍會另外跳出系統設定畫面，需要手動確認。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("開始行程時自動請求", modifier = Modifier.weight(1f))
                        Switch(
                            checked = backgroundExecutionEnabled,
                            onCheckedChange = {
                                backgroundExecutionEnabled = it
                                prefs.backgroundExecutionEnabled = it
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackgroundExecDialog = false
                    requestBackgroundExecutionExemption(context)
                }) { Text("立即請求") }
            },
            dismissButton = { TextButton(onClick = { showBackgroundExecDialog = false }) { Text("關閉") } },
        )
    }

    val shareUrl = "https://tracker.umaya.tw/t/$shareToken"

    if (showShareLinkDialog) Isolated {
        val shareText = "我的登山行程即時位置（留守人追蹤頁）：\n$shareUrl"
        PanelDialog(
            onDismissRequest = { showShareLinkDialog = false },
            title = { Text("留守人連結") },
            text = {
                Column {
                    if (shareToken == null) {
                        Text("這個帳號還沒有分享連結，請登出後重新登入一次。", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(
                            "把這個連結傳給留守人，就能在網頁上看到你的即時位置與軌跡。每個帳號固定一個連結，每次行程都一樣，隨時可從「☰ → 留守人連結」再叫出來。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer { Text(shareUrl, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(contentPadding = PaddingValues(horizontal = 10.dp), onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("留守人連結", shareUrl))
                                Toast.makeText(context, "已複製連結", Toast.LENGTH_SHORT).show()
                            }) { Text("複製") }
                            OutlinedButton(contentPadding = PaddingValues(horizontal = 10.dp), onClick = {
                                try {
                                    context.startActivity(Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, shareText)
                                        },
                                        "分享留守人連結",
                                    ))
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, "找不到可用的分享 App", Toast.LENGTH_LONG).show()
                                }
                            }) { Text("分享") }
                            OutlinedButton(contentPadding = PaddingValues(horizontal = 10.dp), onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("mailto:")
                                        putExtra(Intent.EXTRA_SUBJECT, "登山行程留守人連結" + if (hikeName.isNotBlank()) "：$hikeName" else "")
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                    })
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, "找不到可用的郵件 App", Toast.LENGTH_LONG).show()
                                }
                            }) { Text("Email") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showShareLinkDialog = false }) { Text("關閉") } },
        )
    }

    LaunchedEffect(hasActiveHike) {
        if (!hasActiveHike) return@LaunchedEffect
        // Re-assert on every entry to this screen (app reopen, process restart) — if the
        // service died (e.g. killed on package update) nothing else would restart it, and
        // tracking would silently stay stopped forever with this screen still claiming
        // "行程進行中". Safe to call when already running: pause state is preserved server-side.
        context.startForegroundService(
            Intent(context, LocationForegroundService::class.java)
                .setAction(LocationForegroundService.ACTION_START)
        )
    }

    var serverOnline by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            serverOnline = try {
                ApiClient.service.listHikes("Bearer ${prefs.authToken}").isSuccessful
            } catch (_: Exception) {
                false
            }
            delay(15_000)
        }
    }

    if (showEndTripConfirm) Isolated {
        PanelDialog(
            onDismissRequest = { showEndTripConfirm = false },
            title = { Text("結束行程？") },
            text = { Text("結束後留守人頁面會停止更新你的位置。" + if (gpxRecording) "\nGPX 軌跡記錄不受影響，會繼續記錄。" else "") },
            confirmButton = { TextButton(onClick = { showEndTripConfirm = false; endTrip() }) { Text("結束行程", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showEndTripConfirm = false }) { Text("取消") } },
        )
    }
    if (showStopGpxConfirm) Isolated {
        PanelDialog(
            onDismissRequest = { showStopGpxConfirm = false },
            title = { Text("停止記錄軌跡？") },
            text = { Text("接著會讓你選擇儲存位置與檔名。" + if (hasActiveHike) "\n行程（回報給留守人）不受影響。" else "") },
            confirmButton = { TextButton(onClick = { showStopGpxConfirm = false; stopGpxWithSave() }) { Text("停止並儲存", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showStopGpxConfirm = false }) { Text("取消") } },
        )
    }
    if (showStartGpxDialog) Isolated {
        PanelDialog(
            onDismissRequest = { showStartGpxDialog = false },
            title = { Text("開始記錄軌跡") },
            text = { Text("記錄間隔：" + (if (gpxMinIntervalSec == 0) "持續" else "$gpxMinIntervalSec 秒") + "・最短 $gpxMinDistanceM 米\n要開新的軌跡檔，還是接著上次的檔案（另起一段）？") },
            confirmButton = { TextButton(onClick = { showStartGpxDialog = false; startRecording(resumeExisting = false) }) { Text("新的軌跡檔") } },
            dismissButton = { TextButton(onClick = { showStartGpxDialog = false; startRecording(resumeExisting = true) }) { Text("接續上次") } },
        )
    }
    if (showStartHikeDialog) Isolated {
        PanelDialog(
            onDismissRequest = { showStartHikeDialog = false; startMode = null },
            title = { Text(if (startMode == null) "開始被追蹤" else if (startMode == "continue") "接續舊行程" else "開始新行程") },
            text = {
                if (startMode == null) {
                    Column {
                        Button(
                            onClick = {
                                error = null
                                continuingHikeId = null
                                nickname = prefs.lastNickname
                                hikeName = ""
                                routesToShare.clear()
                                routesToShare.addAll(loadedRoutes.map { it.file })
                                startMode = "new"
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("開始新行程") }

                        Spacer(Modifier.height(12.dp))

                        OutlinedButton(
                            enabled = !loading,
                            onClick = {
                                error = null
                                loading = true
                                scope.launch {
                                    try {
                                        val token = prefs.authToken!!
                                        val res = ApiClient.service.listHikes("Bearer $token")
                                        if (!res.isSuccessful) throw Exception("讀取行程列表失敗")
                                        // Most recent hike regardless of status — 接續舊行程 should be
                                        // able to pick back up an already-ended one too (e.g. the app
                                        // was reinstalled, or 結束行程 was pressed by mistake), not just
                                        // one still stuck active server-side.
                                        val hike = res.body()?.firstOrNull()
                                        if (hike == null) {
                                            error = "沒有可接續的舊行程"
                                        } else {
                                            continuingHikeId = hike.id
                                            continuingNeedsReactivation = hike.status != "active"
                                            nickname = hike.nickname ?: ""
                                            hikeName = hike.name
                                            startMode = "continue"
                                        }
                                    } catch (e: Exception) {
                                        error = friendlyErrorMessage(e)
                                    } finally {
                                        loading = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // 帶入最後一個舊行程的名稱，還沒查過的話先顯示通用文字
                            Text(if (hikeName.isNotBlank()) "接續舊行程（$hikeName）" else "接續舊行程")
                        }
                        error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    val isContinue = startMode == "continue"
                    Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        if (isContinue && continuingNeedsReactivation) {
                            Text(
                                "這個行程先前已標記為結束，按確定後會重新標記為進行中並繼續記錄。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        OutlinedTextField(
                            value = nickname, onValueChange = { nickname = it },
                            label = { Text("暱稱（顯示給留守人看）") },
                            enabled = !isContinue,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = hikeName, onValueChange = { hikeName = it },
                            label = { Text("行程名稱（例如：北大武）") },
                            enabled = !isContinue,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))

                        Text(
                            "行程開始後會一直回報位置給留守人，直到你在 ☰ 按「結束行程」。GPX 軌跡記錄另外用畫面最上方的 ⏺ 開始。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        IntervalSlider(intervalSeconds, onSecondsChange = { intervalSeconds = it })
                        if (!isContinue) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("同步給留守人的 GPX/KML", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(enabled = !importingRoutes, onClick = { routePickerLauncher.launch(arrayOf("*/*")) }) { Text("＋ 新增") }
                            }
                            if (importingRoutes) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            if (loadedRoutes.isNotEmpty()) {
                                val allChecked = loadedRoutes.all { it.file in routesToShare }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        routesToShare.clear()
                                        if (!allChecked) routesToShare.addAll(loadedRoutes.map { it.file })
                                    },
                                ) {
                                    Checkbox(checked = allChecked, onCheckedChange = null)
                                    Text("全選", style = MaterialTheme.typography.bodySmall)
                                }
                                loadedRoutes.forEach { route ->
                                    val checked = route.file in routesToShare
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            if (checked) routesToShare.remove(route.file) else routesToShare.add(route.file)
                                        },
                                    ) {
                                        Checkbox(checked = checked, onCheckedChange = null)
                                        Text(route.name, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                if (startMode != null) {
                    val isContinue = startMode == "continue"
                    TextButton(
                        enabled = !loading && (isContinue || hikeName.isNotBlank()),
                        onClick = {
                            error = null
                            prefs.intervalSeconds = intervalSeconds
                            if (!isContinue) prefs.lastNickname = nickname
                            loading = true
                            scope.launch {
                                try {
                                    if (isContinue) {
                                        if (continuingNeedsReactivation) {
                                            val token = prefs.authToken!!
                                            val res = ApiClient.service.reactivateHike("Bearer $token", continuingHikeId!!)
                                            if (!res.isSuccessful) throw Exception("重新啟用行程失敗")
                                        }
                                        prefs.activeHikeId = continuingHikeId!!
                                        prefs.activeHikeName = hikeName
                                        prefs.isPaused = false
                                    } else {
                                        val token = prefs.authToken!!
                                        val res = ApiClient.service.createHike(
                                            "Bearer $token",
                                            CreateHikeRequest(hikeName, nickname.ifBlank { null }, intervalSeconds),
                                        )
                                        if (!res.isSuccessful) throw Exception("建立行程失敗")
                                        prefs.activeHikeId = res.body()!!.id
                                        prefs.activeHikeName = hikeName
                                    }
                                    context.startForegroundService(
                                        Intent(context, LocationForegroundService::class.java)
                                            .setAction(LocationForegroundService.ACTION_START)
                                    )
                                    hasActiveHike = true
                                    if (startMode == "new") {
                                        // A new hike has nothing on the server yet — ids from an earlier hike are stale.
                                        for (i in loadedRoutes.indices) loadedRoutes[i] = loadedRoutes[i].copy(serverId = null)
                                        persistRoutes()
                                        routesToShare.toList().forEach { shareRoute(it) }
                                        showShareLinkDialog = true
                                    }
                                    startMode = null
                                    showStartHikeDialog = false
                                    TrackerWidgetProvider.updateAllWidgets(context)
                                    if (prefs.backgroundExecutionEnabled) requestBackgroundExecutionExemption(context)
                                } catch (e: Exception) {
                                    error = friendlyErrorMessage(e)
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    ) { Text("確定") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (startMode == null) {
                        showStartHikeDialog = false
                    } else {
                        startMode = null; error = null
                    }
                }) { Text(if (startMode == null) "取消" else "返回") }
            },
        )
    }

    // ---- 軌跡記錄設定：記錄間隔／最短紀錄長度／匯出單一軌跡（合併在 ☰ → GPX 合併匯出）----
    val exportSelection = remember { mutableStateListOf<String>() }
    var exportFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var mergeFillGaps by remember { mutableStateOf(true) }
    fun exportName(files: List<File>): String = when (files.size) {
        1 -> files.first().nameWithoutExtension
        else -> files.minBy { it.lastModified() }.nameWithoutExtension + "-合併"
    }
    fun writeExport(uri: Uri?, asKml: Boolean) {
        val files = exportFiles
        if (uri == null || files.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val name = exportName(files)
                    // Sources are only read; the merge is a new file.
                    val gpx = if (files.size == 1) files.first().readText() else mergeGpxFiles(files, name, mergeFillGaps)
                    val content = if (asKml) gpxToKml(gpx, name) else gpx
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(content.toByteArray()) }
                }
                Toast.makeText(context, "已匯出（${files.size} 筆記錄）", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "匯出失敗：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val exportGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { writeExport(it, false) }
    val exportKmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { writeExport(it, true) }

    if (showTrackSettingsDialog) Isolated {
        val tracks = remember(showTrackSettingsDialog) { recordedTracks(context).map { it to trackPointCount(it) } }
        fun applyToService() {
            // Only a live recording needs its location rate re-requested for the new interval.
            if (prefs.isGpxRecording) {
                context.startService(
                    Intent(context, LocationForegroundService::class.java).setAction(LocationForegroundService.ACTION_UPDATE_INTERVAL)
                )
            }
        }
        PanelDialog(
            onDismissRequest = { showTrackSettingsDialog = false },
            title = { Text("軌跡記錄設定") },
            text = {
                Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    Text("記錄間隔", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    OptionChips(GPX_INTERVAL_OPTIONS, gpxMinIntervalSec, ::gpxIntervalLabel) {
                        gpxMinIntervalSec = it; prefs.gpxMinIntervalSec = it; applyToService()
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("最短紀錄長度", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    OptionChips(GPX_DISTANCE_OPTIONS, gpxMinDistanceM, { "${it}米" }) {
                        gpxMinDistanceM = it; prefs.gpxMinDistanceM = it
                    }
                    Text(
                        "時間和距離都達到才記一點（持續 = 只看距離）。只影響 GPX 記錄；回報給留守人的定位頻率不變。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("匯出 GPX／KML", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    if (tracks.isEmpty()) {
                        Text("還沒有記錄過軌跡。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "選一筆匯出。要把多筆（或手錶的 GPX）合併，請用 ☰ →「GPX 合併匯出」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        tracks.forEach { (file, count) ->
                            val checked = file.absolutePath in exportSelection
                            val recordingNow = file.absolutePath == prefs.gpxFilePath && prefs.isGpxRecording
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    exportSelection.clear()
                                    if (!checked) exportSelection.add(file.absolutePath)
                                },
                            ) {
                                RadioButton(selected = checked, onClick = null)
                                Text(
                                    "${file.nameWithoutExtension}（$count 個點${if (recordingNow) "・記錄中" else ""}）",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        val chosen = tracks.map { it.first }.filter { it.absolutePath in exportSelection }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = chosen.isNotEmpty(), onClick = {
                                exportFiles = chosen; exportGpxLauncher.launch("${exportName(chosen)}.gpx")
                            }) { Text("匯出 GPX") }
                            OutlinedButton(enabled = chosen.isNotEmpty(), onClick = {
                                exportFiles = chosen; exportKmlLauncher.launch("${exportName(chosen)}.kml")
                            }) { Text("匯出 KML") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTrackSettingsDialog = false }) { Text("關閉") } },
        )
    }

    // ---- GPX 合併匯出：App 的記錄＋匯入的 GPX（例如手錶）合併成新檔，原始檔一律不動 ----
    val mergeSelection = remember { mutableStateListOf<String>() }
    var mergeListVersion by remember { mutableStateOf(0) } // bumped after an import to re-list
    val importGpxLauncher = rememberLauncherForActivityResult(remember { OpenDocumentsAtLastFolder(prefs) }) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        prefs.lastGpxFolderUri = uris.first().toString()
        scope.launch {
            var ok = 0
            val failed = mutableListOf<String>()
            for (uri in uris) {
                val displayName = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                } ?: uri.lastPathSegment ?: "匯入.gpx"
                try {
                    val copy = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("無法讀取")
                        if (!String(bytes, Charsets.UTF_8).contains("<trkpt", ignoreCase = true)) throw Exception("沒有軌跡點")
                        // A copy inside the app — the file on the phone stays exactly as it was.
                        val dir = importedGpxDir(context)
                        val base = displayName.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        var target = File(dir, "$base.gpx"); var n = 2
                        while (target.exists()) target = File(dir, "${base}_${n++}.gpx")
                        target.writeBytes(bytes)
                        target
                    }
                    mergeSelection.add(copy.absolutePath)
                    ok++
                } catch (e: Exception) {
                    failed += "$displayName（${e.message}）"
                }
            }
            mergeListVersion++
            Toast.makeText(
                context,
                "已匯入 $ok 個 GPX" + if (failed.isNotEmpty()) "；無法匯入：" + failed.joinToString("、") else "",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    if (showMergeDialog) Isolated {
        val sources = remember(showMergeDialog, mergeListVersion) {
            (recordedTracks(context).map { Triple(it, trackPointCount(it), false) } +
                importedTracks(context).map { Triple(it, trackPointCount(it), true) })
        }
        PanelDialog(
            onDismissRequest = { showMergeDialog = false },
            title = { Text("GPX 合併匯出") },
            text = {
                Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "勾選兩個以上的檔案合併成一個新檔。原始的 GPX（App 的記錄、你匯入的檔案、手機裡的原檔）都會保留，不會被修改或刪除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { importGpxLauncher.launch(arrayOf("*/*")) }) { Text("＋ 匯入 GPX（例如手錶的軌跡）") }
                    Spacer(Modifier.height(8.dp))
                    if (sources.isEmpty()) Text("還沒有任何 GPX。", style = MaterialTheme.typography.bodySmall)
                    sources.forEach { (file, count, imported) ->
                        val checked = file.absolutePath in mergeSelection
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (checked) mergeSelection.remove(file.absolutePath) else mergeSelection.add(file.absolutePath)
                            },
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(
                                (if (imported) "📥 " else "⏺ ") + "${file.nameWithoutExtension}（$count 個點）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("合併方式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { mergeFillGaps = true }) {
                        RadioButton(selected = mergeFillGaps, onClick = null)
                        Text("補空檔：點最多的檔為主，其他檔只補它沒記到的時段（手機＋手錶互補）", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { mergeFillGaps = false }) {
                        RadioButton(selected = !mergeFillGaps, onClick = null)
                        Text("各自一段：每個檔原樣保留成獨立段落，依時間排列", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    val chosen = sources.map { it.first }.filter { it.absolutePath in mergeSelection }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = chosen.size >= 2, onClick = {
                            exportFiles = chosen; exportGpxLauncher.launch("${exportName(chosen)}.gpx")
                        }) { Text("合併匯出 GPX") }
                        OutlinedButton(enabled = chosen.size >= 2, onClick = {
                            exportFiles = chosen; exportKmlLauncher.launch("${exportName(chosen)}.kml")
                        }) { Text("合併匯出 KML") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMergeDialog = false }) { Text("關閉") } },
        )
    }

    // The trail being recorded right now, re-read from its file every few seconds and drawn live.
    var recordingTrack by remember { mutableStateOf<List<List<GeoPoint>>>(emptyList()) }
    var recordingWaypoints by remember { mutableStateOf<List<RouteLabel>>(emptyList()) }
    var tripStats by remember { mutableStateOf<TrackStats?>(null) }
    var trackReloadKey by remember { mutableStateOf(0) } // bumped by 🔄 to re-read the file right away
    LaunchedEffect(gpxRecording, trackReloadKey) {
        if (!gpxRecording) { recordingTrack = emptyList(); recordingWaypoints = emptyList(); tripStats = null; return@LaunchedEffect }
        while (true) {
            val path = prefs.gpxFilePath
            val (parsed, stats) = withContext(Dispatchers.IO) {
                path?.let { p ->
                    runCatching { File(p).readText().let { text -> parseRoute(text) to trackStats(text) } }.getOrNull()
                } ?: (ParsedRoute(emptyList(), emptyList()) to null)
            }
            recordingTrack = parsed.segments
            recordingWaypoints = parsed.labels.filter { it.isWaypoint }
            tripStats = stats
            delay(5_000)
        }
    }
    LaunchedEffect(mapController, offlineMapController, recordingTrack, recordingWaypoints) {
        mapController?.setRecordingTrack(recordingTrack, recordingWaypoints)
        offlineMapController?.setRecordingTrack(recordingTrack, recordingWaypoints)
    }

    // Steps since the trip started: TYPE_STEP_COUNTER counts since boot, so only a baseline is kept.
    var tripSteps by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(tripActive) {
        val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)
        if (!tripActive || sensor == null) { tripSteps = null; return@DisposableEffect onDispose {} }
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                val value = event.values[0]
                if (prefs.stepBaseline < 0f) prefs.stepBaseline = value
                if (value < prefs.stepBaseline) { // phone rebooted mid-trip: counter restarted at 0
                    prefs.stepCarry += prefs.stepLast - prefs.stepBaseline
                    prefs.stepBaseline = 0f
                }
                prefs.stepLast = value
                tripSteps = (prefs.stepCarry + value - prefs.stepBaseline).toInt()
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm.unregisterListener(listener) }
    }

    // Compass heading for the stats panel's small compass.
    var showStatsPanel by remember { mutableStateOf(prefs.statsPanelExpanded) }
    var showProfile by remember { mutableStateOf(false) } // 📈 高度剖面
    // The bottom status bar wraps to 2–3 lines (long 行程名稱, 伺服器離線, an error message, large
    // system font) — the stats panel sits on top of its measured height, not a fixed guess.
    var statusBarHeight by remember { mutableStateOf(44.dp) }
    val density = LocalDensity.current
    // 量距: once the map is slid away from the GPS position, a dashed line runs from the fix to the
    // screen-centre crosshair with its straight-line distance — slide, stop, read. With GPS 永遠置中
    // it shows only after a hand pan (until the map recenters); with it off, whenever off-centre.
    var measure by remember { mutableStateOf<Pair<Float, Float>?>(null) } // metres, bearing° (single line)
    // 📏 多段測距: points added at the crosshair one by one; the path runs start → points → crosshair.
    var measuring by remember { mutableStateOf(false) }
    val measurePoints = remember { mutableStateListOf<Pair<Double, Double>>() } // lat, lon (start first)
    var measureTotal by remember { mutableStateOf(0.0) }
    var measureLeg by remember { mutableStateOf<Pair<Float, Float>?>(null) } // last leg: metres, bearing°
    fun currentFixLatLon(): Pair<Double, Double>? =
        if (mapsforgeActive) offlineMapController?.currentFix()?.let { it.latitude to it.longitude }
        else mapController?.currentFix()?.let { it.latitude to it.longitude }
    fun mapCenterLatLon(): Pair<Double, Double>? =
        if (mapsforgeActive) offlineMapController?.mapCenter()?.let { it.latitude to it.longitude }
        else mapController?.mapCenter()?.let { it.latitude to it.longitude }
    LaunchedEffect(mapController, offlineMapController, mapsforgeActive, gpsFollowing, measuring) {
        val out = FloatArray(2)
        while (true) {
            val fix = currentFixLatLon()
            val c = mapCenterLatLon()
            var path: List<Pair<Double, Double>>? = null
            var single: Pair<Float, Float>? = null
            if (measuring && c != null) {
                path = measurePoints.toList() + c
                var total = 0.0
                for (k in 1 until path.size) {
                    android.location.Location.distanceBetween(path[k - 1].first, path[k - 1].second, path[k].first, path[k].second, out)
                    total += out[0]
                }
                measureTotal = total
                measureLeg = if (path.size >= 2) out[0] to out[1] else null
            } else if (!measuring && gpsFollowing && fix != null && c != null) {
                android.location.Location.distanceBetween(fix.first, fix.second, c.first, c.second, out)
                if (out[0] > MEASURE_MIN_M) { path = listOf(fix, c); single = out[0] to out[1] }
            }
            if (mapsforgeActive) {
                offlineMapController?.setMeasurePath(path?.map { org.mapsforge.core.model.LatLong(it.first, it.second) })
                mapController?.setMeasurePath(null)
            } else {
                mapController?.setMeasurePath(path?.map { GeoPoint(it.first, it.second) })
                offlineMapController?.setMeasurePath(null)
            }
            measure = single
            delay(250)
        }
    }
    fun startMeasuring() {
        val start = currentFixLatLon() ?: mapCenterLatLon() ?: return
        measurePoints.clear(); measurePoints.add(start)
        measureTotal = 0.0; measureLeg = null; measuring = true
    }

    // 比例尺: bottom-left, just above the bottom status bar (which overlaps the map's lower edge).
    LaunchedEffect(mapController, offlineMapController, scaleBarEnabled, statusBarHeight) {
        val bottomPx = with(density) { (statusBarHeight + 6.dp).roundToPx() }
        mapController?.setScaleBarEnabled(scaleBarEnabled, bottomPx)
        offlineMapController?.setScaleBarEnabled(scaleBarEnabled, bottomPx)
    }
    var compassAzimuth by remember { mutableStateOf(0f) }
    DisposableEffect(tripActive && showStatsPanel) {
        val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        if (!(tripActive && showStatsPanel) || sensor == null) return@DisposableEffect onDispose {}
        val listener = object : android.hardware.SensorEventListener {
            private val rotation = FloatArray(9)
            private val orientation = FloatArray(3)
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(rotation, event.values)
                android.hardware.SensorManager.getOrientation(rotation, orientation)
                compassAzimuth = ((Math.toDegrees(orientation[0].toDouble()).toFloat()) + 360f) % 360f
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }

    // Once-a-second tick for the trip clock.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tripActive) {
        while (tripActive) { nowMs = System.currentTimeMillis(); delay(1_000) }
    }

    // 航點 dialog (top bar 🚩, or right after a 📷 photo).
    var showWaypointDialog by remember { mutableStateOf(false) }
    var waypointName by remember { mutableStateOf("") }
    var waypointPhoto by remember { mutableStateOf<String?>(null) } // relative to the gpx folder, e.g. "photos/IMG_….jpg"
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    val gpxFolder = remember { File(context.getExternalFilesDir(null), "gpx") }
    val takePhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto
        pendingPhoto = null
        if (ok && f != null && f.length() > 0) {
            waypointPhoto = "photos/${f.name}"
            if (waypointName.isBlank()) waypointName = "照片 " + SimpleDateFormat("HH:mm", Locale.TAIWAN).format(System.currentTimeMillis())
            showWaypointDialog = true
        } else {
            f?.delete()
        }
    }
    fun takeWaypointPhoto() {
        val dir = File(gpxFolder, "photos").apply { mkdirs() }
        val f = File(dir, "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg")
        pendingPhoto = f
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        try {
            takePhotoLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingPhoto = null
            Toast.makeText(context, "找不到相機 App", Toast.LENGTH_LONG).show()
        }
    }
    if (showWaypointDialog) Isolated {
        val thumb = remember(waypointPhoto) {
            waypointPhoto?.let { rel ->
                runCatching {
                    BitmapFactory.decodeFile(File(gpxFolder, rel).path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
                }.getOrNull()
            }
        }
        PanelDialog(
            onDismissRequest = { showWaypointDialog = false },
            title = { Text("新增航點") },
            text = {
                Column {
                    Text(
                        "以目前位置新增航點" + (if (hasActiveHike) "，名稱會同步給留守人" else "") +
                            (if (gpxRecording) "；照片存在手機並連結到 GPX 航點。" else "。"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = waypointName, onValueChange = { waypointName = it },
                        label = { Text("名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (thumb != null) {
                            androidx.compose.foundation.Image(thumb, contentDescription = "航點照片", modifier = Modifier.size(72.dp))
                        }
                        OutlinedButton(onClick = { takeWaypointPhoto() }) { Text(if (waypointPhoto == null) "📷 拍照" else "📷 重拍") }
                        if (waypointPhoto != null) TextButton(onClick = { waypointPhoto = null }) { Text("不附照片") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = waypointName.isNotBlank(), onClick = {
                    context.startService(
                        Intent(context, LocationForegroundService::class.java)
                            .setAction(LocationForegroundService.ACTION_ADD_WAYPOINT)
                            .putExtra(LocationForegroundService.EXTRA_WAYPOINT_NAME, waypointName.trim())
                            .apply { waypointPhoto?.let { putExtra(LocationForegroundService.EXTRA_WAYPOINT_PHOTO, it) } }
                    )
                    showWaypointDialog = false
                    waypointPhoto = null
                }) { Text("新增") }
            },
            dismissButton = { TextButton(onClick = { showWaypointDialog = false; waypointPhoto = null }) { Text("取消") } },
        )
    }

    // ---- 3D 飛行回放：pick recorded trails and/or loaded route files, one or several merged ----
    var showReliveDialog by remember { mutableStateOf(false) }
    val reliveSelection = remember { mutableStateListOf<String>() }
    if (showReliveDialog) Isolated {
        val recorded = remember(showReliveDialog) { recordedTracks(context) }
        PanelDialog(
            onDismissRequest = { showReliveDialog = false },
            title = { Text("3D 飛行回放") },
            text = {
                Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "3D 地形飛行回放，經過航點時會跳出照片；可錄成 720p／1080p／4K 影片。需要網路（地形與衛星影像）。可勾選多筆合併回放。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    @Composable
                    fun pick(path: String, label: String) {
                        val checked = path in reliveSelection
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { if (checked) reliveSelection.remove(path) else reliveSelection.add(path) },
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (recorded.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("我記錄的軌跡", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        recorded.forEach { pick(it.absolutePath, it.nameWithoutExtension) }
                    }
                    if (loadedRoutes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("匯入的軌跡檔", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        loadedRoutes.forEach { pick(it.file, it.name) }
                    }
                    if (recorded.isEmpty() && loadedRoutes.isEmpty()) Text("還沒有可以回放的軌跡。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = reliveSelection.isNotEmpty(), onClick = {
                    val files = reliveSelection.map(::File)
                    val names = files.map { f -> loadedRoutes.firstOrNull { it.file == f.absolutePath }?.name?.substringBeforeLast('.') ?: f.nameWithoutExtension }
                    ReliveActivity.start(context, files, if (names.size == 1) names.first() else "${names.first()} 等 ${names.size} 筆")
                    showReliveDialog = false
                }) { Text("開始回放") }
            },
            dismissButton = { TextButton(onClick = { showReliveDialog = false }) { Text("關閉") } },
        )
    }

    // "Connecting" (no fix yet) resets every time GPS is turned back on — polls rather than
    // relying on osmdroid's runOnFirstFix, which only ever fires once per MapView instance and
    // wouldn't notice a fresh fix after a later GPS-off/on cycle.
    LaunchedEffect(gpsFollowing, mapController, offlineMapController, mapsforgeActive) {
        val activeController: Any? = if (mapsforgeActive) offlineMapController else mapController
        if (!gpsFollowing || activeController == null) {
            gpsHasFix = false
            return@LaunchedEffect
        }
        gpsHasFix = false
        while (gpsFollowing) {
            val hasFix = if (mapsforgeActive) offlineMapController?.currentFix() != null else mapController?.currentFix() != null
            if (hasFix) {
                gpsHasFix = true
                break
            }
            delay(500)
        }
    }
    // GPS 永遠置中 (☰ → 地圖設定): only while GPS is on. The offline map centers inside its own fix
    // handler; osmdroid's follow mode needs re-arming after a drag, hence the 1-second tick.
    LaunchedEffect(keepGpsCentered, gpsFollowing, mapController, offlineMapController, mapsforgeActive) {
        val on = keepGpsCentered && gpsFollowing && !measuring // the map must stay where the hiker is measuring
        offlineMapController?.keepCentered = on && mapsforgeActive
        while (true) {
            mapController?.keepCenteredTick(on && !mapsforgeActive)
            if (!on) break
            delay(1_000)
        }
    }
    // Mapsforge has no built-in location provider (unlike osmdroid's MyLocationNewOverlay, which
    // already handles this for the raster/線上地圖 path) — feed it fixes directly, only while the
    // vector map is actually showing and GPS is toggled on, so it doesn't double up with osmdroid.
    DisposableEffect(mapsforgeActive, gpsFollowing, offlineMapController) {
        if (!mapsforgeActive || !gpsFollowing || offlineMapController == null) {
            return@DisposableEffect onDispose {}
        }
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        // Per map instance: every time an offline map is (re)selected, its first fix recenters
        // on the phone — but only if the phone is inside that pack's coverage; otherwise the
        // view stays on the pack's own centre (e.g. 安娜普納 selected while still in Taiwan).
        var recentered = false
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                offlineMapController?.updateGpsFix(loc.latitude, loc.longitude, loc.accuracy)
                if (!recentered) {
                    recentered = true
                    if (offlineMapController?.covers(loc.latitude, loc.longitude) == true) offlineMapController?.recenterOnGps()
                    else offlineMapController?.showWholePack()
                }
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // Last known fix first — no waiting for a fresh GPS lock just to center the map.
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && !recentered) callback.onLocationResult(LocationResult.create(listOf(loc)))
            }
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }
        onDispose { fusedClient.removeLocationUpdates(callback) }
    }

    val gpsPulseAlpha by rememberInfiniteTransition(label = "gps-pulse").animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600), repeatMode = RepeatMode.Reverse),
        label = "gps-pulse-alpha",
    )

    // Zoom-level readout between the ＋/－ buttons — neither engine exposes zoom changes as a
    // Compose-observable value, so this polls rather than needing a listener wired through.
    LaunchedEffect(mapController, offlineMapController, mapsforgeActive) {
        while (true) {
            zoomLevelDisplay = if (mapsforgeActive) {
                offlineMapController?.zoomLevel()?.let { "%.1f".format(it) } ?: "—"
            } else {
                mapController?.mapView?.zoomLevelDouble?.let { "%.1f".format(it) } ?: "—"
            }
            delay(500)
        }
    }

    Isolated { Box(modifier = Modifier.fillMaxSize()) { // the whole map screen, in its own method
        when {
            mapsforgeActive && currentOfflinePack != null -> {
                key(enabledLayerIds, currentOfflinePackId, mapTextSizePx) {
                    OfflineMapView(
                        modifier = Modifier.fillMaxSize(),
                        // 10px = the existing default scale (theme's text-scale 1.4, doubled).
                        fontScale = 2.8f * mapTextSizePx / 10f,
                        downloader = mapsforgeDownloader,
                        pack = currentOfflinePack,
                        hillshadingEnabled = hillshadingEnabled,
                        enabledLayerIds = enabledLayerIds,
                    ) { offlineMapController = it }
                }
            }
            currentMapSource == MapSource.OPENSTREETMAP -> {
                HikeMap(modifier = Modifier.fillMaxSize()) { mapController = it }
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFFF0ECE4)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        // side padding clears the left action buttons and the right zoom column
                        modifier = Modifier.padding(horizontal = 96.dp, vertical = 24.dp),
                    ) {
                        Text("${currentOfflinePack?.name ?: "離線地圖"}尚未安裝", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "請到「☰ → 地圖設定 → 離線地圖包」下載安裝，完成後才能使用。",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- 1. 正上方橫bar滿框：GPS位置／GPX錄製/暫停／GPX停止／GPS開關／切換地圖 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color(0xFF202020))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // (0) GPX 軌跡記錄 — separate from the 行程 (☰): ⏺ starts, ⏸/▶️ pauses, ⏹ stops (confirmed).
            if (!gpxRecording) {
                TopBarIconButton("@rec") {
                    if ((prefs.gpxFilePath ?: prefs.lastFinishedGpxPath) != null) showStartGpxDialog = true
                    else startRecording(resumeExisting = false)
                }
            } else {
                TopBarIconButton(
                    if (gpxPaused) "@play" else "@pause",
                    statusColor = if (gpxPaused) Color(0x55FFA000) else Color(0x55D32F2F), // red = 記錄中
                ) { setGpxPaused(!gpxPaused) }
                TopBarIconButton("@stop") { showStopGpxConfirm = true }
            }

            // (1) 回到現在手機 GPS 位置
            TopBarIconButton("@locate") {
                val hasFix = if (mapsforgeActive) offlineMapController?.currentFix() != null else mapController?.currentFix() != null
                if (hasFix) {
                    if (mapsforgeActive) {
                        val fix = offlineMapController?.currentFix()
                        offlineMapController?.recenterOnGps()
                        Toast.makeText(context, "定位座標：${fix?.latitude}, ${fix?.longitude}", Toast.LENGTH_LONG).show()
                    } else {
                        mapController?.recenterOnGps()
                    }
                } else if (!gpsFollowing) {
                    Toast.makeText(context, "GPS 已關閉，請先按上方 🚫 打開 GPS", Toast.LENGTH_LONG).show()
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    // The map hasn't had a fix yet — typical with no phone signal: without network
                    // assistance the GPS needs a cold start (30 s to a couple of minutes under open sky).
                    // Ask for one directly instead of silently leaving the map where it is.
                    Toast.makeText(
                        context,
                        "正在搜尋 GPS 衛星…沒有手機訊號時，第一次定位可能要 1～2 分鐘，請在空曠處稍候",
                        Toast.LENGTH_LONG,
                    ).show()
                    LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(
                        com.google.android.gms.location.CurrentLocationRequest.Builder()
                            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                            .setMaxUpdateAgeMillis(60_000) // a fix from the last minute is good enough
                            .setDurationMillis(180_000)
                            .build(),
                        com.google.android.gms.tasks.CancellationTokenSource().token,
                    ).addOnSuccessListener { loc ->
                        if (loc == null) {
                            Toast.makeText(context, "還找不到 GPS 訊號，請移到空曠處再按一次 📍", Toast.LENGTH_LONG).show()
                        } else {
                            if (mapsforgeActive) {
                                offlineMapController?.updateGpsFix(loc.latitude, loc.longitude, loc.accuracy)
                                offlineMapController?.recenterOnGps()
                            } else {
                                mapController?.animateTo(GeoPoint(loc.latitude, loc.longitude), 17.0)
                            }
                            Toast.makeText(context, "已定位（誤差約 ±${loc.accuracy.toInt()} m）", Toast.LENGTH_SHORT).show()
                        }
                    }.addOnFailureListener {
                        Toast.makeText(context, "定位失敗：${it.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, "沒有定位權限", Toast.LENGTH_LONG).show()
                }
            }

            // (2) 航點 — 以目前位置新增，輸入名稱
            TopBarIconButton("@flag") {
                if (!tripActive) {
                    Toast.makeText(context, "請先開始行程，才能新增航點", Toast.LENGTH_SHORT).show()
                } else {
                    waypointName = "航點 ${recordingWaypoints.size + 1}"
                    waypointPhoto = null
                    showWaypointDialog = true
                }
            }

            // (3) 即時拍照 → 航點
            TopBarIconButton("@camera") {
                if (!tripActive) {
                    Toast.makeText(context, "請先開始行程，才能拍照建立航點", Toast.LENGTH_SHORT).show()
                } else {
                    waypointName = ""
                    waypointPhoto = null
                    takeWaypointPhoto()
                }
            }

            // (5) 打開/關閉 GPS — 搜尋中（還沒拿到第一個定位）琥珀底＋閃爍；定位就緒綠底＋✓
            TopBarIconButton(
                label = if (gpsFollowing) "@gps" else "@gpsoff",
                modifier = if (gpsFollowing && !gpsHasFix) Modifier.alpha(gpsPulseAlpha) else Modifier,
                statusColor = when {
                    !gpsFollowing -> null
                    gpsHasFix -> Color(0xCC2E7D32)
                    else -> Color(0x99F9A825)
                },
                badge = if (gpsFollowing && gpsHasFix) "✓" else null,
            ) {
                gpsFollowing = !gpsFollowing
                if (!mapsforgeActive) mapController?.setGpsEnabled(gpsFollowing)
                if (!gpsFollowing) offlineMapController?.setGpsVisible(false)
            }

            // (5) 選用地圖 — 地圖頁是唯一選地圖的地方；地圖設定只管各地圖的設定。
            Box {
                TopBarIconButton("@layers") { showMapPicker = true }
                DropdownMenu(expanded = showMapPicker, onDismissRequest = { showMapPicker = false }) {
                    val choices = listOf(
                        Triple(MapSource.OPENSTREETMAP, null, "OpenStreetMap（線上）"),
                    ) + offlinePacks.map { pack ->
                        Triple(MapSource.OFFLINE, pack.id, pack.name + if (pack.id in installedPackIds) "" else "（未下載）")
                    }
                    choices.forEach { (source, packId, label) ->
                        val selected = source == currentMapSource && (packId == null || packId == currentOfflinePackId)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${mapSourceIcon(source, packId)}  $label",
                                    fontSize = 14.sp, maxLines = 1, softWrap = false,
                                )
                            },
                            trailingIcon = if (selected) ({ Text("✓") }) else null,
                            onClick = { showMapPicker = false; selectMapSource(source, packId) },
                        )
                    }
                }
            }
        }

        // ---- 3. 左側圓圈：登山者回報區（留守人追蹤）----
        Column(
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            // One shared centre line — otherwise each circle centres on its own caption, and a
            // wider caption (結束行程) pushes its circle out of line with the rest.
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Marks and SOS go to the guardian, so they need the reporting half of the trip.
            if (hasActiveHike) {
                LabeledMapButton("@smile", "我很好", Color(0xEE2E7D32)) {
                    context.startService(
                        Intent(context, LocationForegroundService::class.java)
                            .setAction(LocationForegroundService.ACTION_MARK_SAFE)
                    )
                }
                LabeledMapButton("@tent", "停駐中", Color(0xEEEF6C00)) {
                    context.startService(
                        Intent(context, LocationForegroundService::class.java)
                            .setAction(LocationForegroundService.ACTION_MARK_CAMPING)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Its red disc is 84% of outerSize (the rest is the hold-progress ring) — 62dp
                    // makes the disc 52dp, matching every other circle in this column.
                    SosHoldButton(outerSize = 62.dp, showCaption = false) {
                        context.startService(
                            Intent(context, LocationForegroundService::class.java)
                                .setAction(LocationForegroundService.ACTION_MARK_SOS)
                        )
                    }
                    MapButtonCaption("長按3秒")
                }
            }
        }

        // ---- 4. 右側圓圈：登山導航區（本階段先接 zoom，其餘留待下一階段）----
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MapCircleButton("@plus") { if (mapsforgeActive) offlineMapController?.zoomIn() else mapController?.zoomIn() }
            Text(
                zoomLevelDisplay,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(Color(0xEE202020), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            MapCircleButton("@minus") { if (mapsforgeActive) offlineMapController?.zoomOut() else mapController?.zoomOut() }
        }

        // ---- 偏離航道提示 ----
        distanceToRouteM?.let { d ->
            val off = d > offRouteThresholdM
            Text(
                if (off) "⚠️ 偏離航道 ${formatDistance(d)}" else "✅ 在航道上（${formatDistance(d)}）",
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .background(if (off) Color(0xEEC62828) else Color(0xCC2E7D32), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // ---- 5. 右下：可收合的行程統計（時間、里程、步數、爬升/下降、指北針）----
        if (tripActive && !showProfile && !measuring && !toolsOpen) { // the 📈 / 📏 panels take this spot
            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 72.dp, bottom = statusBarHeight + 8.dp)) {
                if (!showStatsPanel) {
                    MapCircleButton("@stats", size = 44.dp) { showStatsPanel = true; prefs.statsPanelExpanded = true }
                } else {
                    val startedAt = prefs.tripStartedAt
                    val totalMs = if (startedAt == 0L) 0L else (nowMs - startedAt).coerceAtLeast(0L)
                    val pausedMs = prefs.tripPausedTotalMs + (if (prefs.tripPausedSince != 0L) nowMs - prefs.tripPausedSince else 0L)
                    val movingMs = (totalMs - pausedMs).coerceAtLeast(0L)
                    val stats = tripStats
                    Column(
                        modifier = Modifier
                            .background(Color(0xE6202020), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .width(168.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiniCompass(compassAzimuth, Modifier.size(40.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("方位 ${compassDirection(compassAzimuth)} ${compassAzimuth.toInt()}°", color = Color.White, fontSize = 12.sp)
                                Text("行程統計", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                            Text(
                                "✕", color = Color.White, fontSize = 16.sp,
                                modifier = Modifier.clickable { showStatsPanel = false; prefs.statsPanelExpanded = false }.padding(4.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        StatRow("總時間", formatDuration(totalMs))
                        StatRow("移動時間", formatDuration(movingMs))
                        StatRow("總里程", stats?.let { formatDistance(it.distanceM) } ?: "—（未記錄 GPX）")
                        StatRow("步數", tripSteps?.let { "%,d".format(it) } ?: "—")
                        StatRow("累積爬升", stats?.let { "↑ ${it.ascentM.toInt()} m" } ?: "—")
                        StatRow("累積下降", stats?.let { "↓ ${it.descentM.toInt()} m" } ?: "—")
                    }
                }
            }
        }

        if (measuring) {
            Text("✛", color = Color(0xFF2196F3), fontSize = 30.sp, modifier = Modifier.align(Alignment.Center))
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = statusBarHeight + 8.dp, start = 8.dp, end = 8.dp)
                    .background(Color(0xF2202020), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "📏 多段測距　總長 " + formatDistance(measureTotal) + "（${measurePoints.size} 段）",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
                measureLeg?.let { (m, b) ->
                    val deg = (b + 360f) % 360f
                    Text("本段 " + formatDistance(m.toDouble()) + "・" + compassDirection(deg) + " ${deg.toInt()}°", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                }
                Text("移動地圖讓 ✛ 對準下一點，再按「＋ 加點」", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { mapCenterLatLon()?.let { measurePoints.add(it) } }) { Text("＋ 加點", color = Color.White) }
                    OutlinedButton(enabled = measurePoints.size > 1, onClick = { measurePoints.removeAt(measurePoints.lastIndex) }) {
                        Text("↶ 復原", color = Color.White)
                    }
                    Button(
                        onClick = { measuring = false; measurePoints.clear() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    ) { Text("結束測距") }
                }
            }
        }
        if (!measuring) measure?.let { (meters, bearing) ->
            Text("✛", color = Color(0xFF2196F3), fontSize = 28.sp, modifier = Modifier.align(Alignment.Center))
            Text(
                "📏 " + formatDistance(meters.toDouble()) + "・" + compassDirection(((bearing + 360f) % 360f)) + " ${((bearing + 360f) % 360f).toInt()}°",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).padding(top = 64.dp)
                    .background(Color(0xCC202020), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        if (showProfile) {
            val profiles = remember(visibleRoutes, recordingTrack) {
                visibleRoutes.mapNotNull { Profile.of(it.name, it.route.segments) } +
                    listOfNotNull(Profile.of("記錄中的軌跡", recordingTrack))
            }
            val here: GeoPoint? = phoneLocation?.let { GeoPoint(it.latitude, it.longitude) }
                ?: (if (mapsforgeActive) offlineMapController?.currentFix()?.let { GeoPoint(it.latitude, it.longitude) }
                    else mapController?.currentFix()?.let { GeoPoint(it.latitude, it.longitude) })
            ElevationProfilePanel(
                profiles = profiles, here = here, onClose = { showProfile = false },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = statusBarHeight + 8.dp),
            )
        }

        // (drawn last so the sheet and the open 工具 list sit above every other map overlay)
        // ---- 2. 底部導覽列的分頁內容（行程／軌跡／設定）：從導覽列上方彈出 ----
        if (navTab != "map") {
            Box(modifier = Modifier.fillMaxSize().background(Color(0x55000000)).clickable { navTab = "map" })
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = statusBarHeight + 6.dp)
                    .background(Color(0xF2202020), RoundedCornerShape(14.dp)).padding(vertical = 6.dp),
            ) {
                @Composable
                fun Item(icon: String, text: String, sub: String? = null, color: Color = Color.White, onClick: () -> Unit) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { navTab = "map"; onClick() }.padding(horizontal = 16.dp, vertical = 11.dp),
                    ) {
                        LineIcon(icon, tint = color, size = 20.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text, color = color, fontSize = 15.sp)
                            if (sub != null) Text(sub, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                        }
                        LineIcon("chev", tint = Color.White.copy(alpha = 0.35f), size = 16.dp)
                    }
                }
                when (navTab) {
                    "trip" -> {
                        Text("行程（回報位置給留守人）", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp))
                        if (!hasActiveHike) Item("play", "開始行程", color = Color(0xFF81C784)) {
                            error = null; continuingHikeId = null; startMode = null; showStartHikeDialog = true
                        } else {
                            Item(if (isPaused) "play" else "pause", if (isPaused) "繼續行程回報" else "暫停行程回報") { setHikePaused(!isPaused) }
                            Item("stop", "結束行程", color = Color(0xFFFF8A80)) { showEndTripConfirm = true }
                            Item("clock", "定位頻率", sub = intervalLabel(prefs.intervalSeconds)) { showIntervalDialog = true }
                        }
                        Item("link", "留守人連結", sub = "複製／分享／Email") { showShareLinkDialog = true }
                    }
                    "track" -> {
                        Text("軌跡", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp))
                        Item("route", "GPX 記錄設定", sub = gpxIntervalLabel(gpxMinIntervalSec) + "・最短 $gpxMinDistanceM 米・匯出") { exportSelection.clear(); showTrackSettingsDialog = true }
                        Item("download", "軌跡檔管理", sub = "匯入、顯示／隱藏、偏離提醒" + if (loadedRoutes.isNotEmpty()) "・${loadedRoutes.size} 個" else "") { showLoadRouteDialog = true }
                        Item("merge", "GPX 合併匯出", sub = "可匯入手錶的 GPX，原檔保留") { mergeSelection.clear(); showMergeDialog = true }
                        Item("film", "3D 飛行回放") { reliveSelection.clear(); showReliveDialog = true }
                    }
                    else -> {
                        Text("設定", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp))
                        Item("map", "地圖設定", sub = "地圖包、圖層、比例尺、文字、方向、GPS 置中") { showMapSettingsDialog = true }
                        Item("battery", "背景活動管理") { showBackgroundExecDialog = true }
                        Item("home", "切換身分", sub = "需要身分 PIN") { RoleLock.switchRole(context as ComponentActivity) }
                        Item("logout", "登出") { prefs.authToken = null; prefs.shareToken = null; onLoggedOut() }
                        Item("power", "結束程式") { showExitConfirmDialog = true }
                        Text("版本 " + appVersionName(context), color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp))
                    }
                }
            }
        }

        // ---- 工具（收合）：補點／天氣／高度剖面／測距／山峰辨識 ----
        if (toolsOpen) Box(modifier = Modifier.fillMaxSize().background(Color(0x33000000)).clickable { toolsOpen = false })
        if (navTab == "map") Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = statusBarHeight + 10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (toolsOpen) {
                @Composable
                fun Tool(icon: String, label: String, onClick: () -> Unit) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { toolsOpen = false; onClick() }) {
                        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.background(Color(0xCC202020), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 4.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.size(46.dp).background(Color(0xFF00796B), CircleShape), contentAlignment = Alignment.Center) { LineIcon(icon) }
                    }
                }
                Tool("refresh", "補點") {
                    // 取回 GPS 暫存的定位點（訊號中斷／飛航模式期間）、立即補傳離線點給留守人、重畫軌跡
                    if (!tripActive) {
                        Toast.makeText(context, "請先開始行程", Toast.LENGTH_SHORT).show()
                    } else {
                        context.startService(Intent(context, LocationForegroundService::class.java).setAction(LocationForegroundService.ACTION_FLUSH))
                        scope.launch {
                            delay(1_500) // let the flushed fixes land in the GPX file and the upload queue
                            trackReloadKey++
                            val hikeId = prefs.activeHikeId
                            val pending = if (hikeId == -1L) 0 else withContext(Dispatchers.IO) {
                                tw.umaya.tracker.data.AppDatabase.get(context).trackPointDao().pendingCount(hikeId)
                            }
                            Toast.makeText(
                                context,
                                if (pending > 0) "軌跡已更新；$pending 個離線點待補傳給留守人（有網路時立即上傳）" else "軌跡已更新，留守人那邊已是最新",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                Tool("cloud", "天氣") {
                    // for where the hiker is (best fix we have), else wherever the map is looking
                    val here: Pair<Double, Double>? =
                        phoneLocation?.let { it.latitude to it.longitude }
                            ?: (if (mapsforgeActive) offlineMapController?.currentFix()?.let { it.latitude to it.longitude }
                                else mapController?.currentFix()?.let { it.latitude to it.longitude })
                            ?: (if (mapsforgeActive) offlineMapController?.mapCenter()?.let { it.latitude to it.longitude }
                                else mapController?.mapCenter()?.let { it.latitude to it.longitude })
                    WeatherActivity.start(context, here?.first, here?.second)
                }
                Tool("chart", "高度剖面") { showProfile = !showProfile }
                Tool("ruler", if (measuring) "結束測距" else "測距") { if (measuring) { measuring = false; measurePoints.clear() } else startMeasuring() }
                Tool("mountain", "山峰辨識") { PeakFinderActivity.start(context) }
            }
            Box(
                modifier = Modifier.size(54.dp).background(if (toolsOpen) Color(0xFF202020) else Color(0xEE00796B), CircleShape)
                    .clickable { toolsOpen = !toolsOpen },
                contentAlignment = Alignment.Center,
            ) { LineIcon(if (toolsOpen) "x" else "tools", size = 24.dp) }
        }

        // ---- 狀態列／錯誤訊息（原本頁面上的伺服器狀態、行程狀態，移到底部一條窄列）----
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { statusBarHeight = with(density) { it.height.toDp() } }
                .background(Color(0xFF202020))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // One line, never wrapping: a long 行程名稱 is cut with … first, the status parts stay whole.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (hasActiveHike) hikeName.ifBlank { "行程進行中" } else "尚未開始行程", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (tripActive) {
                    Text(
                        (if (!hasActiveHike) "" else if (isPaused) "・回報暫停" else "・進行中") +
                            (if (!gpxRecording) "" else if (gpxPaused) " ⏸記錄" else " ⏺"),
                        color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                Text(
                    when (serverOnline) { true -> "🟢 伺服器"; false -> "🔴 伺服器離線（點我回報）"; null -> "⚪ 伺服器檢查中" },
                    fontSize = 12.sp, maxLines = 1, softWrap = false,
                    color = if (serverOnline == false) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.8f),
                    modifier = if (serverOnline == false) {
                        Modifier.clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:")
                                    putExtra(Intent.EXTRA_EMAIL, arrayOf("ajchen2017@gmail.com"))
                                    putExtra(Intent.EXTRA_SUBJECT, "登山健行定位追蹤 - 系統異常回報")
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "App 顯示伺服器狀態異常（離線），行程：$hikeName\n請盡快協助排除，謝謝。",
                                    )
                                })
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, "找不到可用的郵件 App", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else Modifier,
                )
            }
            error?.let { msg ->
                Text(
                    msg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }, // full text on tap
                )
            }
            // 底部導覽列: 地圖 (close any sheet) / 行程 / 軌跡 / 設定
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                listOf("map" to ("map" to "地圖"), "trip" to ("play" to "行程"), "track" to ("route" to "軌跡"), "settings" to ("gear" to "設定"))
                    .forEach { (tab, iconLabel) ->
                        val on = navTab == tab
                        val c = if (on) Color(0xFF64B5F6) else Color(0xFFBDBDBD)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f).clickable { navTab = if (on && tab != "map") "map" else tab; toolsOpen = false }.padding(vertical = 4.dp),
                        ) {
                            LineIcon(iconLabel.first, tint = c, size = 22.dp)
                            Text(iconLabel.second, color = c, fontSize = 11.sp)
                        }
                    }
            }
        }
    } }
}
