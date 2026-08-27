package tw.umaya.tracker.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tw.umaya.tracker.data.ApiClient
import tw.umaya.tracker.data.CreateHikeRequest
import tw.umaya.tracker.data.ForgotPasswordRequest
import tw.umaya.tracker.data.HikeListItemDto
import tw.umaya.tracker.data.INTERVAL_PRESETS
import tw.umaya.tracker.data.LoginRequest
import tw.umaya.tracker.data.Prefs
import tw.umaya.tracker.data.RegisterRequest
import tw.umaya.tracker.data.intervalLabel
import tw.umaya.tracker.location.LocationForegroundService
import tw.umaya.tracker.sync.HikeActionWorker
import tw.umaya.tracker.widget.TrackerWidgetProvider
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

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

/** Snaps to the fixed [INTERVAL_PRESETS] list rather than any continuous value. */
@Composable
private fun IntervalSlider(seconds: Int, onSecondsChange: (Int) -> Unit, onChangeFinished: () -> Unit = {}) {
    val index = INTERVAL_PRESETS.indexOfFirst { it.first == seconds }.let { if (it < 0) 3 else it }
    Text("定位頻率：每 ${intervalLabel(seconds)} 一筆", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = index.toFloat(),
        onValueChange = { onSecondsChange(INTERVAL_PRESETS[it.toInt()].first) },
        onValueChangeFinished = onChangeFinished,
        valueRange = 0f..(INTERVAL_PRESETS.size - 1).toFloat(),
        steps = INTERVAL_PRESETS.size - 2,
    )
}

/**
 * Big red circle, not a tap — SOS is consequential enough that it shouldn't fire from a
 * stray touch. Holding fills the ring over [HOLD_MS]; releasing early cancels with no effect.
 */
@Composable
private fun SosHoldButton(onTriggered: () -> Unit) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(0f) }
    val holdMs = 3000L

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(200.dp)
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
                    .size(168.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "SOS",
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.displaySmall,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "長按 3 秒發送 SOS 求救",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Solid-color action button that visibly darkens while held — a hiker glancing at the screen
 *  mid-tap needs to see the press land, not just infer it from the default ripple. */
@Composable
private fun ActionButton(
    label: String,
    baseColor: Color,
    pressedColor: Color,
    height: Dp,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Button(
        modifier = modifier.height(height),
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(containerColor = if (isPressed) pressedColor else baseColor),
        onClick = onClick,
    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = textStyle) }
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
    var menuExpanded by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showClearRouteDialog by remember { mutableStateOf(false) }
    var showBackgroundExecDialog by remember { mutableStateOf(false) }
    var backgroundExecutionEnabled by remember { mutableStateOf(prefs.backgroundExecutionEnabled) }
    var isPaused by remember { mutableStateOf(prefs.isPaused) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    // Confirms the server actually has (or no longer has) the route — not just that
    // the PUT/DELETE returned 200 — before telling the hiker it's done. Polls briefly
    // since the write and this read can race.
    suspend fun verifyRouteStatus(token: String, expectPresent: Boolean): Boolean {
        repeat(5) { attempt ->
            val res = ApiClient.service.getRouteStatus("Bearer $token", prefs.activeHikeId)
            if (res.isSuccessful && res.body()?.hasRoute == expectPresent) return true
            if (attempt < 4) delay(500)
        }
        return false
    }

    val routePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw Exception("無法讀取檔案")
                if (!(text.contains("<gpx") || text.contains("<kml"))) throw Exception("檔案不是有效的 GPX 或 KML")
                val token = prefs.authToken!!
                val body = text.toRequestBody("application/xml".toMediaTypeOrNull())
                val res = ApiClient.service.uploadRoute("Bearer $token", prefs.activeHikeId, body)
                if (!res.isSuccessful) throw Exception("上傳失敗")
                if (!verifyRouteStatus(token, expectPresent = true)) throw Exception("已上傳，但伺服器尚未確認存好，請稍後查看留守人網頁")
                Toast.makeText(context, "✅ 規劃路線已上傳並確認", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, friendlyErrorMessage(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showClearRouteDialog) {
        AlertDialog(
            onDismissRequest = { showClearRouteDialog = false },
            title = { Text("清除規劃路線") },
            text = { Text("確定要清除已上傳到伺服器的規劃路線嗎？留守人網頁上的規劃路線會跟著消失。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearRouteDialog = false
                    scope.launch {
                        try {
                            val token = prefs.authToken!!
                            val res = ApiClient.service.deleteRoute("Bearer $token", prefs.activeHikeId)
                            if (!res.isSuccessful) throw Exception("清除失敗")
                            if (!verifyRouteStatus(token, expectPresent = false)) throw Exception("已送出清除，但伺服器尚未確認，請稍後查看留守人網頁")
                            Toast.makeText(context, "✅ 規劃路線已清除並確認", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, friendlyErrorMessage(e), Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { showClearRouteDialog = false }) { Text("取消") } },
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "⛰️ 登山健行定位追蹤",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        ),
                    )
                },
                actions = {
                    Box {
                        TextButton(onClick = { menuExpanded = true }) {
                            Text("⋮", style = MaterialTheme.typography.titleLarge)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            if (hasActiveHike) {
                                DropdownMenuItem(
                                    text = { Text("定位頻率") },
                                    onClick = { menuExpanded = false; showIntervalDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("上傳／更新規劃路線（GPX/KML）") },
                                    onClick = { menuExpanded = false; routePickerLauncher.launch("*/*") },
                                )
                                DropdownMenuItem(
                                    text = { Text("清除已上傳的規劃路線") },
                                    onClick = { menuExpanded = false; showClearRouteDialog = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("背景執行") },
                                onClick = { menuExpanded = false; showBackgroundExecDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text("登出") },
                                onClick = {
                                    menuExpanded = false
                                    prefs.authToken = null
                                    prefs.shareToken = null
                                    onLoggedOut()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
    Column(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp).verticalScroll(rememberScrollState()),
    ) {
        if (!hasActiveHike) {
            if (startMode == null) {
                Button(
                    onClick = {
                        error = null
                        continuingHikeId = null
                        nickname = prefs.lastNickname
                        hikeName = ""
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
                ) { Text("接續舊行程") }
            } else {
                val isContinue = startMode == "continue"
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

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { startMode = null; error = null },
                        modifier = Modifier.weight(1f),
                    ) { Text("返回") }

                    Button(
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
                                            CreateHikeRequest(hikeName, nickname.ifBlank { null }),
                                        )
                                        if (!res.isSuccessful) throw Exception("建立行程失敗")
                                        prefs.activeHikeId = res.body()!!.id
                                    }
                                    context.startForegroundService(
                                        Intent(context, LocationForegroundService::class.java)
                                            .setAction(LocationForegroundService.ACTION_START)
                                    )
                                    hasActiveHike = true
                                    startMode = null
                                    TrackerWidgetProvider.updateAllWidgets(context)
                                    if (prefs.backgroundExecutionEnabled) requestBackgroundExecutionExemption(context)
                                } catch (e: Exception) {
                                    error = friendlyErrorMessage(e)
                                } finally {
                                    loading = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("確定") }
                }
            }
        } else {
            val shareUrl = "https://tracker.umaya.tw/t/$shareToken"

            LaunchedEffect(Unit) {
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("伺服器狀態：")
                Text(
                    when (serverOnline) {
                        true -> "正常"
                        false -> "離線"
                        null -> "檢查中"
                    },
                    fontWeight = FontWeight.Bold,
                    color = if (serverOnline == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                if (serverOnline == false) {
                    IconButton(
                        modifier = Modifier.size(32.dp),
                        onClick = {
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
                        },
                    ) { Text("✉️") }
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("行程狀態：", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isPaused) "進行中（定位已暫停）" else "進行中",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("分享連結：")
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("寄給留守人") } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(
                        modifier = Modifier.size(32.dp),
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:")
                                    putExtra(Intent.EXTRA_SUBJECT, "登山健行定位追蹤 - $hikeName")
                                    putExtra(Intent.EXTRA_TEXT, "可以在這裡看到我的即時位置：\n$shareUrl")
                                })
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, "找不到可用的郵件 App", Toast.LENGTH_LONG).show()
                            }
                        },
                    ) { Text("✉️") }
                }
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("分享") } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(
                        modifier = Modifier.size(32.dp),
                        onClick = {
                            try {
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareUrl)
                                    },
                                    "分享行程連結",
                                ))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, "找不到可用的分享 App", Toast.LENGTH_LONG).show()
                            }
                        },
                    ) { Text("📤") }
                }
            }
            Text(shareUrl)

            Spacer(Modifier.height(24.dp))

            val actionButtonHeight = 56.dp
            val actionButtonTextStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    label = "😊 我很好",
                    baseColor = Color(0xFF2E7D32),
                    pressedColor = Color(0xFF1B5E20),
                    height = actionButtonHeight,
                    textStyle = actionButtonTextStyle,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        context.startService(
                            Intent(context, LocationForegroundService::class.java)
                                .setAction(LocationForegroundService.ACTION_MARK_SAFE)
                        )
                    },
                )

                ActionButton(
                    label = "⛺ 停駐中",
                    baseColor = Color(0xFFEF6C00),
                    pressedColor = Color(0xFFBF360C),
                    height = actionButtonHeight,
                    textStyle = actionButtonTextStyle,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        context.startService(
                            Intent(context, LocationForegroundService::class.java)
                                .setAction(LocationForegroundService.ACTION_MARK_CAMPING)
                        )
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    label = if (isPaused) "▶️ 繼續" else "⏸ 暫停",
                    baseColor = if (isPaused) Color(0xFF2E7D32) else Color(0xFFF9A825),
                    pressedColor = if (isPaused) Color(0xFF1B5E20) else Color(0xFFF57F17),
                    height = actionButtonHeight,
                    textStyle = actionButtonTextStyle,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        isPaused = !isPaused
                        prefs.isPaused = isPaused
                        context.startService(
                            Intent(context, LocationForegroundService::class.java).setAction(
                                if (isPaused) LocationForegroundService.ACTION_PAUSE
                                else LocationForegroundService.ACTION_RESUME
                            )
                        )
                    },
                )

                ActionButton(
                    label = "🏁 結束行程",
                    baseColor = Color(0xFFC62828),
                    pressedColor = Color(0xFF8E0000),
                    height = actionButtonHeight,
                    textStyle = actionButtonTextStyle,
                    modifier = Modifier.weight(1f),
                    onClick = {
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
                        hasActiveHike = false
                        isPaused = false
                        loading = false
                        TrackerWidgetProvider.updateAllWidgets(context)
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SosHoldButton {
                    context.startService(
                        Intent(context, LocationForegroundService::class.java)
                            .setAction(LocationForegroundService.ACTION_MARK_SOS)
                    )
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
    }
}
