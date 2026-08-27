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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RoleTile(
                    onPickHiker = { startActivity(Intent(this, MainActivity::class.java)) },
                    onPickGuardian = { startActivity(Intent(this, GuardianActivity::class.java)) },
                )
            }
        }
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
        }
    }
}

@Composable
private fun RoleSquare(iconRes: Int, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
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
