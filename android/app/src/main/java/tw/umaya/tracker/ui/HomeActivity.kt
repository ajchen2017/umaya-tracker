package tw.umaya.tracker.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.umaya.tracker.R
import tw.umaya.tracker.guardian.GuardianActivity

/**
 * App launcher — a role picker, not a screen of its own. This one app now covers two
 * roles that used to be separate installs (登山者/hiker and 留守人/guardian); everything
 * past this point belongs to whichever role was tapped, and each role's screen has its
 * own 🏠 button that calls finish() to land back here.
 */
class HomeActivity : ComponentActivity() {

    /** After a crash, offer to share its stack trace (e.g. by email/LINE) so it can be diagnosed remotely.
     *  [then] runs once the offer is answered (or right away when there was no crash). */
    private fun offerCrashReport(then: () -> Unit) {
        val file = tw.umaya.tracker.crashFile(this)
        if (!file.exists()) return then()
        val report = file.readText()
        file.delete()
        android.app.AlertDialog.Builder(this)
            .setOnDismissListener { then() }
            .setTitle("上次 App 異常結束")
            .setMessage("要把錯誤資訊傳給開發者嗎？（只有錯誤記錄，不含位置或個人資料）")
            .setPositiveButton("分享") { _, _ ->
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_EMAIL, arrayOf("ws.chen2011@gmail.com"))
                        .putExtra(Intent.EXTRA_SUBJECT, "登山健行定位追蹤 當機記錄")
                        .putExtra(Intent.EXTRA_TEXT, report),
                    "分享錯誤記錄",
                ))
            }
            .setNegativeButton("不用", null)
            .show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = tw.umaya.tracker.data.Prefs(this)
        offerCrashReport {
            // 身分鎖定: a phone that already has its role opens straight into it.
            prefs.appRole?.let { open(it); return@offerCrashReport }
        }
        if (prefs.appRole != null) return
        setContent {
            MaterialTheme {
                RoleTile(onPickHiker = { pick(prefs, RoleLock.HIKER) }, onPickGuardian = { pick(prefs, RoleLock.GUARDIAN) })
            }
        }
    }

    /** First pick on this phone: set the PIN that guards switching later, then lock the role. */
    private fun pick(prefs: tw.umaya.tracker.data.Prefs, role: String) {
        val lock = { prefs.appRole = role; open(role) }
        if (prefs.rolePinHash == null) RoleLock.askNewPin(this) { h -> prefs.rolePinHash = h; lock() } else lock()
    }

    private fun open(role: String) {
        startActivity(Intent(this, if (role == RoleLock.GUARDIAN) GuardianActivity::class.java else MainActivity::class.java))
        finish()
    }
}

@Composable
private fun RoleTile(onPickHiker: () -> Unit, onPickGuardian: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "選擇身分",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 32.dp),
            )
            RoleSquare(iconRes = R.mipmap.tile_hiker, label = "我是徒步健行者", onClick = onPickHiker)
            Spacer(Modifier.height(28.dp))
            RoleSquare(iconRes = R.mipmap.tile_guardian, label = "我是留守人員", onClick = onPickGuardian)
            Spacer(Modifier.height(36.dp))
            Text(
                "版本 " + appVersionName(androidx.compose.ui.platform.LocalContext.current),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoleSquare(iconRes: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}
