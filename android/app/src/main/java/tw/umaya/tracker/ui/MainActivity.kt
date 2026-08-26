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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                    if (loggedIn) HikeScreen(prefs) else LoginScreen(prefs) { loggedIn = true }
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
                            prefs.authToken = res.body()!!.token
                            onLoggedIn()
                        }
                    } catch (e: Exception) {
                        error = e.message
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
fun HikeScreen(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasActiveHike by remember { mutableStateOf(prefs.hasActiveHike) }
    var shareToken by remember { mutableStateOf(prefs.activeShareToken) }
    var hikeName by remember { mutableStateOf("") }
    var intervalSeconds by remember { mutableStateOf(prefs.intervalSeconds) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

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
                title = { Text("登山健行定位追蹤") },
                actions = {
                    if (hasActiveHike) {
                        Box {
                            TextButton(onClick = { menuExpanded = true }) {
                                Text("⋮", style = MaterialTheme.typography.titleLarge)
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("定位頻率") },
                                    onClick = { menuExpanded = false; showIntervalDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("地圖與軌跡設定") },
                                    onClick = {
                                        menuExpanded = false
                                        try {
                                            context.startActivity(Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://tracker.umaya.tw/t/$shareToken/settings"),
                                            ))
                                        } catch (_: ActivityNotFoundException) {
                                            Toast.makeText(context, "找不到可用的瀏覽器", Toast.LENGTH_LONG).show()
                                        }
                                    },
                                )
                            }
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
                    scope.launch {
                        try {
                            val token = prefs.authToken!!
                            val res = ApiClient.service.createHike("Bearer $token", CreateHikeRequest(hikeName))
                            if (!res.isSuccessful) throw Exception("建立行程失敗")
                            val hike = res.body()!!
                            prefs.activeHikeId = hike.id
                            prefs.activeShareToken = hike.shareToken
                            context.startForegroundService(
                                Intent(context, LocationForegroundService::class.java)
                                    .setAction(LocationForegroundService.ACTION_START)
                            )
                            hasActiveHike = true
                            shareToken = hike.shareToken
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            loading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("開始行程") }
        } else {
            val shareUrl = "https://tracker.umaya.tw/t/$shareToken"

            Text("行程進行中", style = MaterialTheme.typography.titleMedium)
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
                ) { Text("✉️ 寄給家人") }

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
                ) { Text("⛺ 紮營中", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = {
                    loading = true
                    scope.launch {
                        try {
                            val token = prefs.authToken!!
                            ApiClient.service.endHike("Bearer $token", prefs.activeHikeId)
                        } catch (_: Exception) {
                            // Ending on the server can be retried later; local stop must still proceed.
                        } finally {
                            context.startService(
                                Intent(context, LocationForegroundService::class.java)
                                    .setAction(LocationForegroundService.ACTION_STOP)
                            )
                            prefs.clearActiveHike()
                            hasActiveHike = false
                            loading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("結束行程") }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
    }
}
