package tw.umaya.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * The 行程統計 panel's look (dark translucent card, compact white text, ✕ to close), used for the
 * ☰ menu and every settings dialog on the hiker map so they all read as one family.
 */

private val PanelBackground = Color(0xF2202020) // the stats panel is 0xE6202020; a touch more opaque over a busy map
private val PanelColors = darkColorScheme(
    primary = Color(0xFF64B5F6), onPrimary = Color.Black,
    secondaryContainer = Color(0xFF37474F), onSecondaryContainer = Color.White,
    background = Color(0xFF202020), onBackground = Color.White,
    surface = Color(0xFF202020), onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2C), onSurfaceVariant = Color.White.copy(alpha = 0.72f),
    surfaceContainer = Color(0xFF262626), surfaceContainerHigh = Color(0xFF2C2C2C), surfaceContainerHighest = Color(0xFF333333),
    outline = Color.White.copy(alpha = 0.35f), outlineVariant = Color.White.copy(alpha = 0.18f),
    error = Color(0xFFFF8A80),
)
private val PanelType = Typography().let { t ->
    t.copy(
        titleMedium = t.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
        titleSmall = t.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
        bodyLarge = t.bodyLarge.copy(fontSize = 14.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = t.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 15.sp),
        labelLarge = t.labelLarge.copy(fontSize = 13.sp),
    )
}

/** Dark panel theme for anything shown over the map (e.g. the ☰ menu). */
@Composable
fun PanelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PanelColors, typography = PanelType) {
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}

/** Drop-in for AlertDialog (same slots) in the 行程統計 panel style. */
@Composable
fun PanelDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PanelTheme {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .background(PanelBackground, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        ProvideTextStyle(MaterialTheme.typography.titleMedium) { title() }
                    }
                    Text(
                        "✕", color = Color.White, fontSize = 16.sp,
                        modifier = Modifier.clickable(onClick = onDismissRequest).padding(4.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
