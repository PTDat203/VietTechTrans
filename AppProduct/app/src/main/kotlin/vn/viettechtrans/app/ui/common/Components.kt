package vn.viettechtrans.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vn.viettechtrans.app.R
import vn.viettechtrans.app.ui.theme.LocalGradients

/** Large screen header on a soft brand gradient: optional back button, title, subtitle, actions. */
@Composable
fun VttHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalGradients.current.brandSoft)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                FilledTonalIconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = cardColor(),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = backDescription)
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Header action: round tonal icon button. */
@Composable
fun HeaderAction(icon: Int, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.padding(start = 6.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = cardColor(),
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(painterResource(icon), contentDescription = description)
    }
}

/** Pill button filled with the brand gradient. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Int? = null,
    brush: Brush = LocalGradients.current.brand,
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(brush)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/** Small "VI" / "EN" badge. */
@Composable
fun LangBadge(code: String, brush: Brush, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(brush)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(code, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** Card color: white on the light background, a raised gray in dark mode. */
@Composable
fun cardColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainerLowest

/** Neutral fill for a small control sitting on a card (visible in both themes). */
@Composable
fun onCardControlColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) MaterialTheme.colorScheme.outlineVariant
    else MaterialTheme.colorScheme.surfaceContainerHigh

/** Rounded content card with a soft shadow; optional icon + title row. */
@Composable
fun VttCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = cardColor(), shadowElevation = 2.dp) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    if (icon != null) {
                        IconBubble(icon, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
            }
            content()
        }
    }
}

/** Icon on a round brand-gradient bubble. */
@Composable
fun IconBubble(icon: Int, size: Dp = 48.dp, brush: Brush = LocalGradients.current.brand) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(brush), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** Centered illustration + message for screens that have nothing to show yet. */
@Composable
fun EmptyState(icon: Int, text: String, modifier: Modifier = Modifier, title: String? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            IconBubble(icon, size = 72.dp)
        }
        Spacer(Modifier.height(20.dp))
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Highlighted notice (offline banner, errors) with an info icon. */
@Composable
fun NoticeCard(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
