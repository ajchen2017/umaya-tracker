package tw.umaya.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A single floating circular icon button — the visual unit both the 回報區 (left) and 導航區
 * (right) overlay clusters on the map screen are built from. [enabled]=false dims it instead of
 * hiding it, so the cluster's layout never shifts as state changes (e.g. 結束 disabled pre-hike).
 */
@Composable
fun MapCircleButton(
    label: String,
    background: Color = Color(0xEE202020),
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 52.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(if (enabled) background else background.copy(alpha = 0.35f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // "@name" = one of the line icons (LineIcons.kt); anything else is drawn as text/emoji
        if (label.startsWith("@")) LineIcon(label.drop(1), size = if (size < 48.dp) 22.dp else 24.dp)
        else Text(label, fontSize = 20.sp, color = Color.White)
    }
}

/** A caption pill under a map button — readable over any map background. */
@Composable
fun MapButtonCaption(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = Color.White,
        modifier = Modifier
            .background(Color(0xCC202020), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** [MapCircleButton] with a caption under it. */
@Composable
fun LabeledMapButton(label: String, caption: String, background: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MapCircleButton(label, background = background, onClick = onClick)
        MapButtonCaption(caption)
    }
}

/** Same visual language, sized for the top full-width bar rather than a floating cluster. */
@Composable
fun TopBarIconButton(
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    /** Optional status disc drawn behind the icon (e.g. GPS ready / searching). */
    statusColor: Color? = null,
    /** Optional small corner badge, e.g. "✓". */
    badge: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .then(if (statusColor != null) Modifier.background(statusColor, CircleShape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label.startsWith("@")) LineIcon(label.drop(1), tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
        else Text(
            label,
            fontSize = 22.sp,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            maxLines = 1,
            overflow = TextOverflow.Visible,
            softWrap = false,
        )
        if (badge != null) {
            Text(
                badge,
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color(0xFF2E7D32), CircleShape)
                    .padding(horizontal = 3.dp),
            )
        }
    }
}
