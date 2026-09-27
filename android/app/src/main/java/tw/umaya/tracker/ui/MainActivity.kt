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
import androidx.compose.ui.platform.LocalContext
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
import tw.umaya.tracker.data.LoginRequest
import tw.umaya.tracker.data.MapsforgeDownloader
import tw.umaya.tracker.data.Prefs
import tw.umaya.tracker.data.RegisterRequest
import tw.umaya.tracker.data.TAIWAN_PACK_ID
import tw.umaya.tracker.data.intervalLabel
import tw.umaya.tracker.location.LocationForegroundService
import tw.umaya.tracker.sync.HikeActionWorker
import tw.umaya.tracker.widget.TrackerWidgetProvider
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.io.File
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
private fun defaultGpxTrackName(): String =
    SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(System.currentTimeMillis())

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

/** A labeled dropdown of integers — used by the 開始追蹤 dialog's 最短間隔時間/距離 pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberDropdown(
    label: String,
    value: Int,
    range: IntRange,
    unit: String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = "$value$unit",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            range.forEach { v ->
                DropdownMenuItem(text = { Text("$v$unit") }, onClick = { onValueChange(v); expanded = false })
            }
        }
    }
}

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
    var hikeName by remember { mutableStateOf("") }
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
    var showFunctionMenu by remember { mutableStateOf(false) }
    var mapController by remember { mutableStateOf<HikeMapController?>(null) }
    var gpsFollowing by remember { mutableStateOf(true) }
    var gpsHasFix by remember { mutableStateOf(false) } // false while "connecting" — drives the pulse animation
    var zoomLevelDisplay by remember { mutableStateOf("—") } // shown between the ＋/－ buttons
    var gpxRecording by remember { mutableStateOf(prefs.isGpxRecording) }
    var gpxPaused by remember { mutableStateOf(prefs.isGpxPaused) }
    var showGpxStartDialog by remember { mutableStateOf(false) }
    var showGpxStopDialog by remember { mutableStateOf(false) }
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
    DisposableEffect(Unit) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { phoneLocation = result.lastLocation ?: return }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedClient.lastLocation.addOnSuccessListener { if (it != null && phoneLocation == null) phoneLocation = it }
            fusedClient.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000L).build(),
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
    var gpxStopFormat by remember { mutableStateOf("gpx") }
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
    val loadedRouteKeys = loadedRoutes.joinToString("\n") { it.file }
    LaunchedEffect(mapController, offlineMapController, loadedRouteKeys) {
        mapController?.let { c -> c.clearAllLoadedRoutes(); loadedRoutes.forEach { c.addLoadedRoute(it.file, it.route) } }
        offlineMapController?.let { c -> c.clearAllLoadedRoutes(); loadedRoutes.forEach { c.addLoadedRoute(it.file, it.route) } }
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

    fun finishRecording(output: Uri?) {
        if (output == null) {
            Toast.makeText(context, "沒有選擇儲存位置，仍在記錄中", Toast.LENGTH_LONG).show()
            return
        }
        gpxRecording = false; gpxPaused = false
        context.startService(
            Intent(context, LocationForegroundService::class.java)
                .setAction(LocationForegroundService.ACTION_GPX_STOP)
                .putExtra(LocationForegroundService.EXTRA_GPX_NAME, gpxStopName)
                .putExtra(LocationForegroundService.EXTRA_GPX_FORMAT, gpxStopFormat)
                .putExtra(LocationForegroundService.EXTRA_GPX_OUTPUT_URI, output.toString())
        )
    }
    val saveGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { finishRecording(it) }
    val saveKmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { finishRecording(it) }

    // The recorder lives in the service; after the process was killed or the app updated, nothing
    // restarts it on its own even though prefs still say 記錄中 — reopening the app does.
    LaunchedEffect(Unit) {
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

    if (showLoadRouteDialog) {
        AlertDialog(
            onDismissRequest = { showLoadRouteDialog = false },
            title = { Text("GPX/KML 路線") },
            text = {
                Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        if (hasActiveHike) "行程進行中：載入的路線會同步顯示在留守人的地圖上，移除時也會一併移除；行程結束後自動從伺服器刪除。"
                        else "目前沒有進行中的行程，路線只顯示在這支手機；開始新行程時可以勾選要同步給留守人的路線。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (importingRoutes) {
                        Spacer(Modifier.height(8.dp))
                        Text("載入中…", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    loadedRoutes.forEach { route ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "✅ ${route.name}（${route.summary}）" + if (route.serverId != null) "・已同步給留守人" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
    LaunchedEffect(mapController, offlineMapController, scaleBarEnabled, mapOrientationMode) {
        mapController?.setScaleBarEnabled(scaleBarEnabled)
        mapController?.setOrientationMode(mapOrientationMode)
        offlineMapController?.setScaleBarEnabled(scaleBarEnabled)
    }

    val visibleLoraDevicePoints = if (showLoraDevicePoints) loraDevicePoints.toList() else emptyList()
    LaunchedEffect(mapController, offlineMapController, visibleLoraDevicePoints) {
        mapController?.setLoraDevicePoints(visibleLoraDevicePoints)
        offlineMapController?.setLoraDevicePoints(visibleLoraDevicePoints)
    }

    packPendingDelete?.let { pack ->
        AlertDialog(
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

    if (showMapSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showMapSettingsDialog = false },
            title = { Text("地圖設定") },
            text = {
                Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
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

    if (showLayerSettingsDialog) {
        // 開關先存在待確認清單裡，不會立刻套用；按「確認」才寫回 enabledLayerIds/prefs 並讓地圖
        // 用新圖層重新載入一次（key(enabledLayerIds) 那邊會處理 remount，不必再手動切換地圖/重開App）。
        var pendingLayerIds by remember(showLayerSettingsDialog) { mutableStateOf(enabledLayerIds) }
        AlertDialog(
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


    if (showExitConfirmDialog) {
        AlertDialog(
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

    if (showIntervalDialog) {
        AlertDialog(
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

    if (showBackgroundExecDialog) {
        AlertDialog(
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

    if (showShareLinkDialog) {
        val shareText = "我的登山行程即時位置（留守人追蹤頁）：\n$shareUrl"
        AlertDialog(
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

    if (showStartHikeDialog) {
        AlertDialog(
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
                            loading = true
                            prefs.intervalSeconds = intervalSeconds
                            if (!isContinue) prefs.lastNickname = nickname
                            scope.launch {
                                try {
                                    if (isContinue) {
                                        if (continuingNeedsReactivation) {
                                            val token = prefs.authToken!!
                                            val res = ApiClient.service.reactivateHike("Bearer $token", continuingHikeId!!)
                                            if (!res.isSuccessful) throw Exception("重新啟用行程失敗")
                                        }
                                        prefs.activeHikeId = continuingHikeId!!
                                        prefs.isPaused = false
                                    } else {
                                        val token = prefs.authToken!!
                                        val res = ApiClient.service.createHike(
                                            "Bearer $token",
                                            CreateHikeRequest(hikeName, nickname.ifBlank { null }, intervalSeconds),
                                        )
                                        if (!res.isSuccessful) throw Exception("建立行程失敗")
                                        prefs.activeHikeId = res.body()!!.id
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

    if (showGpxStartDialog) {
        AlertDialog(
            onDismissRequest = { showGpxStartDialog = false },
            title = { Text("開始記錄") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberDropdown(
                            label = "最短間隔時間", value = gpxMinIntervalSec, range = 1..20, unit = "秒",
                            onValueChange = { gpxMinIntervalSec = it }, modifier = Modifier.weight(1f),
                        )
                        NumberDropdown(
                            label = "最短間隔距離", value = gpxMinDistanceM, range = 5..20, unit = "公尺",
                            onValueChange = { gpxMinDistanceM = it }, modifier = Modifier.weight(1f),
                        )
                    }
                    if (!gpsHasFix) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠️ 衛星定位尚未就緒，現在開始可能會錯過最前面幾個點",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                // (1) 重新開始 — abandons any unfinished on-disk trail and starts fresh.
                TextButton(onClick = {
                    if (!gpsHasFix) Toast.makeText(context, "衛星定位尚未就緒，仍會開始記錄", Toast.LENGTH_LONG).show()
                    prefs.gpxMinIntervalSec = gpxMinIntervalSec
                    prefs.gpxMinDistanceM = gpxMinDistanceM
                    gpxRecording = true; gpxPaused = false
                    context.startForegroundService(
                        Intent(context, LocationForegroundService::class.java)
                            .setAction(LocationForegroundService.ACTION_GPX_START)
                            .putExtra(LocationForegroundService.EXTRA_GPX_RESUME_EXISTING, false)
                    )
                    showGpxStartDialog = false
                    Toast.makeText(context, "⏺ 開始記錄", Toast.LENGTH_SHORT).show()
                }) { Text("重新開始") }
            },
            dismissButton = {
                // (2) 繼續 — resumes the on-disk unfinished trail if one exists (e.g. the app/
                // service died mid-recording); safely falls back to starting fresh if not.
                TextButton(onClick = {
                    if (!gpsHasFix) Toast.makeText(context, "衛星定位尚未就緒，仍會開始記錄", Toast.LENGTH_LONG).show()
                    prefs.gpxMinIntervalSec = gpxMinIntervalSec
                    prefs.gpxMinDistanceM = gpxMinDistanceM
                    gpxRecording = true; gpxPaused = false
                    context.startForegroundService(
                        Intent(context, LocationForegroundService::class.java)
                            .setAction(LocationForegroundService.ACTION_GPX_START)
                            .putExtra(LocationForegroundService.EXTRA_GPX_RESUME_EXISTING, true)
                    )
                    showGpxStartDialog = false
                    Toast.makeText(context, "⏺ 繼續記錄上次未完成的軌跡", Toast.LENGTH_SHORT).show()
                }) { Text("繼續") }
            },
        )
    }

    if (showGpxStopDialog) {
        AlertDialog(
            onDismissRequest = { showGpxStopDialog = false },
            title = { Text("結束記錄") },
            text = {
                Column {
                    Text("匯出格式", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("gpx" to "GPX", "kml" to "KML").forEach { (value, label) ->
                            OutlinedButton(
                                onClick = { gpxStopFormat = value },
                                colors = if (gpxStopFormat == value) {
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else ButtonDefaults.outlinedButtonColors(),
                            ) { Text(label) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = gpxStopName, onValueChange = { gpxStopName = it },
                        label = { Text("軌跡名稱") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                // 確定 → the system save dialog, where the hiker picks the folder and edits the file
                // name; recording only stops once a location is actually chosen.
                TextButton(onClick = {
                    showGpxStopDialog = false
                    val fileName = "${gpxStopName.ifBlank { defaultGpxTrackName() }}.$gpxStopFormat"
                    if (gpxStopFormat == "kml") saveKmlLauncher.launch(fileName) else saveGpxLauncher.launch(fileName)
                }) { Text("確定") }
            },
            dismissButton = {
                // 取消 — just closes; recording carries on and 結束記錄 stays available.
                TextButton(onClick = { showGpxStopDialog = false }) { Text("取消") }
            },
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

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            mapsforgeActive && currentOfflinePack != null -> {
                key(enabledLayerIds, currentOfflinePackId) {
                    OfflineMapView(
                        modifier = Modifier.fillMaxSize(),
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
            // (1) 回到現在手機 GPS 位置
            TopBarIconButton("📍") {
                if (mapsforgeActive) {
                    val fix = offlineMapController?.currentFix()
                    offlineMapController?.recenterOnGps()
                    Toast.makeText(
                        context,
                        if (fix != null) "定位座標：${fix.latitude}, ${fix.longitude}" else "尚無定位資料",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    mapController?.recenterOnGps()
                }
            }

            // (2) 啟動/暫停記錄軌跡 GPX — 同一圖示，依錄製狀態切換
            TopBarIconButton(
                when {
                    !gpxRecording -> "⏺"
                    gpxPaused -> "▶"
                    else -> "⏸"
                }
            ) {
                when {
                    !gpxRecording -> {
                        gpxMinIntervalSec = prefs.gpxMinIntervalSec
                        gpxMinDistanceM = prefs.gpxMinDistanceM
                        showGpxStartDialog = true
                    }
                    gpxPaused -> {
                        gpxPaused = false; prefs.isGpxPaused = false
                        context.startService(
                            Intent(context, LocationForegroundService::class.java)
                                .setAction(LocationForegroundService.ACTION_GPX_RESUME)
                        )
                        Toast.makeText(context, "▶ 繼續記錄", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        gpxPaused = true; prefs.isGpxPaused = true
                        context.startService(
                            Intent(context, LocationForegroundService::class.java)
                                .setAction(LocationForegroundService.ACTION_GPX_PAUSE)
                        )
                        Toast.makeText(context, "⏸ 暫停記錄（再按一次繼續）", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // (3) 停止記錄 GPX
            TopBarIconButton("⏹", enabled = gpxRecording) {
                gpxStopFormat = "gpx"
                gpxStopName = defaultGpxTrackName()
                showGpxStopDialog = true
            }

            // (4) 打開/關閉 GPS — 搜尋中（還沒拿到第一個定位）琥珀底＋閃爍；定位就緒綠底＋✓
            TopBarIconButton(
                label = if (gpsFollowing) "🛰️" else "🚫",
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
                TopBarIconButton(mapSourceIcon(currentMapSource, currentOfflinePackId)) { showMapPicker = true }
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

        // ---- 2. 左上功能選單（跳出視窗）----
        // 目前只接了已有對應功能的項目；其餘（載入GPX/KML、方位點、離線地圖安裝、
        // GPS/軌跡/方位點細節設定）屬於下一階段的獨立子系統，先不做假按鈕。
        Box(modifier = Modifier.align(Alignment.TopStart).padding(top = 60.dp, start = 8.dp)) {
            MapCircleButton("☰", size = 40.dp) { showFunctionMenu = true }
            DropdownMenu(expanded = showFunctionMenu, onDismissRequest = { showFunctionMenu = false }) {
                DropdownMenuItem(
                    text = { Text("載入 GPX/KML" + if (loadedRoutes.isNotEmpty()) "（已載入 ${loadedRoutes.size}）" else "") },
                    onClick = {
                        showFunctionMenu = false
                        if (loadedRoutes.isEmpty()) routePickerLauncher.launch(arrayOf("*/*")) else showLoadRouteDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("地圖設定") },
                    onClick = { showFunctionMenu = false; showMapSettingsDialog = true },
                )
                if (hasActiveHike) {
                    DropdownMenuItem(
                        text = { Text("回報設定（定位頻率）") },
                        onClick = { showFunctionMenu = false; showIntervalDialog = true },
                    )
                }
                DropdownMenuItem(
                    text = { Text("留守人連結（複製／分享／Email）") },
                    onClick = { showFunctionMenu = false; showShareLinkDialog = true },
                )
                DropdownMenuItem(
                    text = { Text("背景活動管理") },
                    onClick = { showFunctionMenu = false; showBackgroundExecDialog = true },
                )
                DropdownMenuItem(
                    text = { Text("程式設定：登出") },
                    onClick = {
                        showFunctionMenu = false
                        prefs.authToken = null
                        prefs.shareToken = null
                        onLoggedOut()
                    },
                )
                DropdownMenuItem(
                    text = { Text("結束") },
                    onClick = { showFunctionMenu = false; showExitConfirmDialog = true },
                )
            }
        }

        // Back to the role picker (登山者／留守人) — top-right, mirrors ☰ on the top-left.
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 8.dp)) {
            MapCircleButton("🏠", size = 40.dp) { (context as ComponentActivity).finish() }
        }

        // ---- 3. 左側圓圈：登山者回報區（留守人追蹤）----
        Column(
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            // One shared centre line — otherwise each circle centres on its own caption, and a
            // wider caption (結束行程) pushes its circle out of line with the rest.
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MapCircleButton(
                label = if (!hasActiveHike) "▶️" else if (isPaused) "▶️" else "⏸",
                background = Color(0xEE2D7DD2),
            ) {
                if (!hasActiveHike) {
                    error = null
                    continuingHikeId = null
                    startMode = null
                    showStartHikeDialog = true
                } else {
                    isPaused = !isPaused
                    prefs.isPaused = isPaused
                    context.startService(
                        Intent(context, LocationForegroundService::class.java).setAction(
                            if (isPaused) LocationForegroundService.ACTION_PAUSE
                            else LocationForegroundService.ACTION_RESUME
                        )
                    )
                }
            }
            // Only meaningful while a hike is in progress — hidden before it starts and after it ends.
            if (hasActiveHike) {
                LabeledMapButton("🏁", "結束行程", Color(0xEEC62828)) {
                    loading = true
                    // Local stop always proceeds immediately; the server-side end is handed to
                    // HikeActionWorker, which retries until delivered (or permanently rejected)
                    // and toasts the outcome — same contract as SOS/safe/camping, so a timeout
                    // here can no longer orphan the hike as "active" forever.
                    HikeActionWorker.enqueue(context, prefs.activeHikeId, HikeActionWorker.ACTION_END)
                    context.startService(
                        Intent(context, LocationForegroundService::class.java)
                            .setAction(LocationForegroundService.ACTION_STOP)
                    )
                    prefs.clearActiveHike()
                    // The server deletes this hike's shared routes when it ends.
                    for (i in loadedRoutes.indices) loadedRoutes[i] = loadedRoutes[i].copy(serverId = null)
                    persistRoutes()
                    hasActiveHike = false
                    isPaused = false
                    loading = false
                    TrackerWidgetProvider.updateAllWidgets(context)
                }
                LabeledMapButton("😊", "我很好", Color(0xEE2E7D32)) {
                    context.startService(
                        Intent(context, LocationForegroundService::class.java)
                            .setAction(LocationForegroundService.ACTION_MARK_SAFE)
                    )
                }
                LabeledMapButton("⛺", "停駐中", Color(0xEEEF6C00)) {
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
            MapCircleButton("＋") { if (mapsforgeActive) offlineMapController?.zoomIn() else mapController?.zoomIn() }
            Text(
                zoomLevelDisplay,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(Color(0xEE202020), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            MapCircleButton("－") { if (mapsforgeActive) offlineMapController?.zoomOut() else mapController?.zoomOut() }
        }

        // ---- 狀態列／錯誤訊息（原本頁面上的伺服器狀態、行程狀態，移到底部一條窄列）----
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xFF202020))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row {
                Text(hikeName.ifBlank { "尚未開始行程" }, color = Color.White, fontWeight = FontWeight.Bold)
                if (hasActiveHike) {
                    Text(
                        if (isPaused) "（定位已暫停）" else "（進行中）",
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "伺服器：" + when (serverOnline) { true -> "正常"; false -> "離線（點擊回報）"; null -> "檢查中" },
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
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
