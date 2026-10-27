package com.flipperai.bridge

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFFFFA24D),
            onPrimary = Color(0xFF3A1F00),
            primaryContainer = Color(0xFF5A3200),
            onPrimaryContainer = Color(0xFFFFDCC0),
            background = Color(0xFF16130F),
            onBackground = Color(0xFFEDE0D4),
            surface = Color(0xFF16130F),
            onSurface = Color(0xFFEDE0D4),
            surfaceVariant = Color(0xFF2A241D),
            onSurfaceVariant = Color(0xFFD7C3B0)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFFB85C00),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDCC0),
            onPrimaryContainer = Color(0xFF3A1F00),
            background = Color(0xFFFFFBF7),
            onBackground = Color(0xFF211A12),
            surface = Color(0xFFFFFBF7),
            onSurface = Color(0xFF211A12),
            surfaceVariant = Color(0xFFF6E8DA),
            onSurfaceVariant = Color(0xFF50443A)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun PressButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    val animated = modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
    if (outlined) {
        OutlinedButton(onClick = onClick, modifier = animated, enabled = enabled, interactionSource = interaction, content = content)
    } else {
        Button(onClick = onClick, modifier = animated, enabled = enabled, interactionSource = interaction, content = content)
    }
}

private val appeared = HashSet<String>()

@Composable
fun Appear(key: String, delayMs: Int = 0, content: @Composable () -> Unit) {
    val already = remember { key in appeared }
    if (already) {
        content()
        return
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
        appeared.add(key)
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(380, delayMillis = delayMs)) +
            slideInVertically(tween(380, delayMillis = delayMs)) { it / 10 }
    ) {
        content()
    }
}

@Composable
fun ToastBanner(flow: kotlinx.coroutines.flow.StateFlow<String?>, modifier: Modifier = Modifier) {
    val message by flow.collectAsState()
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(message) {
        val current = message
        if (current != null) shown = current
    }
    Box(modifier = modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        AnimatedVisibility(
            visible = message != null,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { -it * 2 } + fadeIn(),
            exit = slideOutVertically(tween(250)) { -it * 2 } + fadeOut(tween(250))
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 6.dp
            ) {
                Text(
                    shown,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun ConnectionBadge(connected: Boolean) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Restart),
        label = "ring"
    )
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Restart),
        label = "ringAlpha"
    )
    val pop by animateFloatAsState(
        targetValue = if (connected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "pop"
    )
    Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    scaleX = ring
                    scaleY = ring
                    alpha = ringAlpha * pop
                }
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    scaleX = pop
                    scaleY = pop
                }
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("\u2713", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
        }
    }
}
