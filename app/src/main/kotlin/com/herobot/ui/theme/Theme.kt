package com.herobot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = HeroCobalt,
    onPrimary = HeroOnCobalt,
    primaryContainer = HeroCobaltContainer,
    onPrimaryContainer = HeroOnCobaltContainer,
    secondary = HeroCyanTeal,
    onSecondary = HeroOnCyanTeal,
    secondaryContainer = HeroCyanContainer,
    onSecondaryContainer = HeroOnCyanContainer,
    tertiary = HeroEmerald,
    tertiaryContainer = HeroEmeraldContainer,
    onTertiaryContainer = HeroOnEmeraldContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkCobalt,
    onPrimary = DarkOnCobalt,
    primaryContainer = DarkCobaltContainer,
    onPrimaryContainer = DarkOnCobaltContainer,
    secondary = DarkCyanTeal,
    onSecondary = DarkOnCyanTeal,
    secondaryContainer = DarkCyanContainer,
    onSecondaryContainer = DarkOnCyanContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline
)

val HeroBotShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun HeroBotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HeroBotTypography,
        shapes = HeroBotShapes,
        content = content
    )
}
