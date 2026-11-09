package com.storyweaver.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Warm off-white / charcoal light; near-black / soft ivory dark; muted burgundy accent
private val Burgundy = Color(0xFF7A2E3D)
private val BurgundyLight = Color(0xFF9A4A5A)
private val OffWhite = Color(0xFFF7F3EE)
private val Charcoal = Color(0xFF1E1C1A)
private val SoftIvory = Color(0xFFEDE6DC)
private val NearBlack = Color(0xFF121110)

private val LightColors = lightColorScheme(
    primary = Burgundy,
    onPrimary = Color.White,
    secondary = BurgundyLight,
    background = OffWhite,
    surface = OffWhite,
    onBackground = Charcoal,
    onSurface = Charcoal,
    surfaceVariant = Color(0xFFE8E0D6),
    onSurfaceVariant = Color(0xFF4A4540),
)

private val DarkColors = darkColorScheme(
    primary = BurgundyLight,
    onPrimary = Color.White,
    secondary = Burgundy,
    background = NearBlack,
    surface = NearBlack,
    onBackground = SoftIvory,
    onSurface = SoftIvory,
    surfaceVariant = Color(0xFF2A2623),
    onSurfaceVariant = Color(0xFFC9C0B4),
)

@Composable
fun StoryWeaverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
