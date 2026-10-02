package vn.viettechtrans.app.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vn.viettechtrans.app.R

/**
 * Tap-to-talk button (≥ 72 dp). While [listening], shows a stop icon and a ring
 * that grows with the microphone level (RMS), so the user sees the mic works.
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
) {
    val ring by animateFloatAsState(targetValue = if (listening) 1f + (level * 6f).coerceAtMost(0.45f) else 1f, label = "level")
    Box(modifier = modifier.size(size * 1.5f), contentAlignment = Alignment.Center) {
        if (listening) {
            Box(
                Modifier
                    .size(size)
                    .scale(ring)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape),
            )
        }
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(size),
            colors = if (listening) {
                IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                IconButtonDefaults.filledIconButtonColors()
            },
        ) {
            Icon(
                painterResource(if (listening) R.drawable.ic_stop_circle else R.drawable.ic_mic),
                contentDescription = contentDescription,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}
