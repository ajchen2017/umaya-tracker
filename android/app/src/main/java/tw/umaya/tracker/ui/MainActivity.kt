package tw.umaya.tracker.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import tw.umaya.tracker.data.ApiClient
import tw.umaya.tracker.data.CreateHikeRequest
import tw.umaya.tracker.data.INTERVAL_PRESETS
import tw.umaya.tracker.data.LoginRequest
import tw.umaya.tracker.data.Prefs
import tw.umaya.tracker.data.RegisterRequest
import tw.umaya.tracker.data.intervalLabel
import tw.umaya.tracker.location.LocationForegroundService
import tw.umaya.tracker.sync.HikeActionWorker
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
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

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
    var intervalSeconds by remember { mutableStateOf(prefs.intervalSeconds) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showClearRouteDialog by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(prefs.isPaused) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

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
                Toast.makeText(context, "✅ 規劃路線已上傳", Toast.LENGTH_LONG).show()
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
                            Toast.makeText(context, "✅ 規劃路線已清除", Toast.LENGTH_LONG).show()
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
            OutlinedTextField(
                value = nickname, onValueChange = { nickname = it },
                label = { Text("暱稱（顯示給留守人看）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = hikeName, onValueChange = { hikeName = it },
                label = { Text("行程名稱（例如：北大武）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            IntervalSlider(intervalSeconds, onSecondsChange = { intervalSeconds = it })

            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !loading && hikeName.isNotBlank(),
                onClick = {
                    error = null
                    loading = true
                    prefs.intervalSeconds = intervalSeconds
                    prefs.lastNickname = nickname
                    scope.launch {
                        try {
                            val token = prefs.authToken!!
                            val res = ApiClient.service.createHike(
                                "Bearer $token",
                                CreateHikeRequest(hikeName, nickname.ifBlank { null }),
                            )
                            if (!res.isSuccessful) throw Exception("建立行程失敗")
                            val hike = res.body()!!
                            prefs.activeHikeId = hike.id
                            context.startForegroundService(
                                Intent(context, LocationForegroundService::class.java)
                                    .setAction(LocationForegroundService.ACTION_START)
                            )
                            hasActiveHike = true
                        } catch (e: Exception) {
                            error = friendlyErrorMessage(e)
                        } finally {
                            loading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("開始行程") }
        } else {
            val shareUrl = "https://tracker.umaya.tw/t/$shareToken"

            Text(if (isPaused) "行程進行中（定位已暫停）" else "行程進行中", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("分享連結：$shareUrl")
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
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
                ) { Text("✉️ 寄給留守人") }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
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
                ) { Text("分享") }
            }

            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        context.startService(
                            Intent(context, LocationForegroundService::class.java)
                                .setAction(LocationForegroundService.ACTION_MARK_SOS)
                        )
                    },
                ) { Text("🆘 SOS", maxLines = 1, overflow = TextOverflow.Ellipsis) }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    onClick = {
                        context.startService(
                            Intent(context, LocationForegroundService::class.java)
                                .setAction(LocationForegroundService.ACTION_MARK_SAFE)
                        )
                    },
                ) { Text("我很好", maxLines = 1, overflow = TextOverflow.Ellipsis) }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    onClick = {
                        context.startService(
                            Intent(context, LocationForegroundService::class.java)
                                .setAction(LocationForegroundService.ACTION_MARK_CAMPING)
                        )
                    },
                ) { Text("⛺ 停駐中", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
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
                ) { Text(if (isPaused) "▶️ 繼續" else "⏸ 暫停") }

                OutlinedButton(
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
                    },
                ) { Text("結束行程") }
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
    }
}
