package vn.viettechtrans.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vn.viettechtrans.app.R
import vn.viettechtrans.app.ui.theme.LocalGradients

/**
 * Round gradient talk button. While [listening] it turns coral with a stop icon and
 * emits soft expanding rings that grow with the microphone level, so people see the
 * app is hearing them.
 */
@Composable
fun TalkButton(
    listening: Boolean,
    enabled: Boolean,
    level: Float,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    brush: Brush = LocalGradients.current.brand,
) {
    val g = LocalGradients.current
    val pressScale by animateFloatAsState(if (listening) 1.06f else 1f, spring(dampingRatio = 0.45f, stiffness = 400f), label = "press")
    val levelBoost by animateFloatAsState((level * 8f).coerceIn(0f, 0.5f), tween(90), label = "level")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring1 by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "r1")
    val ring2 by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, delayMillis = 700, easing = LinearEasing), RepeatMode.Restart), label = "r2")

    // Rings may grow past this box; Compose does not clip, so they glow over the surroundings.
    Box(modifier = modifier.size(size * 1.3f), contentAlignment = Alignment.Center) {
        if (listening) {
            for (r in listOf(ring1, ring2)) {
                Box(
                    Modifier
                        .size(size)
                        .scale(1f + r * (0.55f + levelBoost))
                        .alpha((1f - r) * 0.35f)
                        .background(g.listening, CircleShape),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(size)
                .scale(pressScale + if (listening) levelBoost * 0.25f else 0f)
                .alpha(if (enabled) 1f else 0.4f)
                .shadow(if (enabled) 10.dp else 0.dp, CircleShape, ambientColor = Color(0x664F46E5), spotColor = Color(0x664F46E5))
                .clip(CircleShape)
                .background(if (listening) g.listening else brush)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (listening) R.drawable.ic_stop_circle else R.drawable.ic_mic),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}
