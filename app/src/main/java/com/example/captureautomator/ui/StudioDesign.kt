package com.example.captureautomator.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Mint = Color(0xFF63E6BB)
private val StudioDark = darkColorScheme(
    primary = Mint, onPrimary = Color(0xFF09291F), secondary = Color(0xFFD9C7A5),
    onSecondary = Color(0xFF29241A), tertiary = Color(0xFFA7CDBF),
    background = Color(0xFF121716), onBackground = Color(0xFFF2F5F0),
    surface = Color(0xFF1B2421), onSurface = Color(0xFFF2F5F0),
    surfaceVariant = Color(0xFF293630), onSurfaceVariant = Color(0xFFBAC9C1),
    outline = Color(0xFF65796F), outlineVariant = Color(0xFF3A4A42),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    surfaceContainer = Color(0xFF222E28), surfaceContainerHigh = Color(0xFF2C3932),
    surfaceContainerLow = Color(0xFF18201C), surfaceContainerHighest = Color(0xFF34443B)
)
private val StudioLight = lightColorScheme(
    primary = Color(0xFF146C50), onPrimary = Color.White, secondary = Color(0xFF6D5939),
    background = Color(0xFFF3F5EE), onBackground = Color(0xFF17231D),
    surface = Color(0xFFEAF0E7), onSurface = Color(0xFF17231D),
    surfaceVariant = Color(0xFFDAE5DB), onSurfaceVariant = Color(0xFF44564A),
    outline = Color(0xFF6D7E72), outlineVariant = Color(0xFFB8C9BC),
    surfaceContainer = Color(0xFFE7EEE3), surfaceContainerLow = Color(0xFFEEF3EA),
    surfaceContainerHigh = Color(0xFFE0E9DD), surfaceContainerHighest = Color(0xFFD6E3D5)
)

@Composable internal fun StudioTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) StudioDark else StudioLight,
        shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(30.dp)),
        typography = Typography(
            displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
            headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = (-.7).sp),
            titleLarge = TextStyle(fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        ), content = content
    )
}

@Composable internal fun StudioBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.background(Brush.linearGradient(listOf(colors.background,
        colors.surface.copy(alpha = .92f), colors.secondary.copy(alpha = .08f), colors.background))), content = content)
}

@Composable internal fun StudioButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    outlined: Boolean = false, quiet: Boolean = false, content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) .97f else 1f,
        spring(dampingRatio = .8f, stiffness = 700f), label = "Button pressure")
    val target = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.heightIn(min = 48.dp)
    when {
        quiet -> TextButton(onClick, target, enabled, interactionSource = interaction, content = content)
        outlined -> OutlinedButton(onClick, target, enabled, interactionSource = interaction,
            shape = RoundedCornerShape(50), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            content = content)
        else -> Button(onClick, target, enabled, interactionSource = interaction, shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp), content = content)
    }
}

@Composable internal fun StudioPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
        tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}


// One lightweight vector family for workflow destinations. Labels carry the semantics.
@Composable internal fun StudioMark(label: String) {
    val ink = MaterialTheme.colorScheme.primary
    Box(Modifier.size(42.dp).background(ink.copy(alpha = .09f), RoundedCornerShape(14.dp))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val w = size.width
            val h = size.height
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
            when {
                label.contains("history", true) -> {
                    drawCircle(ink, w * .38f, center, style = stroke)
                    drawLine(ink, center, androidx.compose.ui.geometry.Offset(w * .5f, h * .25f), stroke.width)
                    drawLine(ink, center, androidx.compose.ui.geometry.Offset(w * .72f, h * .55f), stroke.width)
                }
                label.contains("Device") -> drawRoundRect(ink,
                    androidx.compose.ui.geometry.Offset(w * .2f, 0f),
                    androidx.compose.ui.geometry.Size(w * .6f, h),
                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = stroke)
                label.contains("Settings") -> {
                    for (i in 1..3) {
                        val y = h * i / 4
                        drawLine(ink, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y), stroke.width)
                        drawCircle(ink, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(w * (if (i == 2) .7f else .3f), y))
                    }
                }
                else -> {
                    for (i in 0..2) drawRoundRect(ink,
                        androidx.compose.ui.geometry.Offset(i * w * .12f, i * h * .25f),
                        androidx.compose.ui.geometry.Size(w * .7f, h * .17f),
                        androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
                }
            }
        }
    }
}
