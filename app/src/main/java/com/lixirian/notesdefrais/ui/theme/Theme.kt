package com.lixirian.notesdefrais.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Palette maison (pas de couleurs dynamiques) : indigo vif en primaire, menthe en secondaire,
 * ambre en tertiaire. Même identité en clair et en sombre, avec des surfaces à tons
 * (surfaceContainer*) pour les cartes empilées, à la manière de Material 3 Expressive.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4CF5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E0FF),
    onPrimaryContainer = Color(0xFF1A0F6B),
    secondary = Color(0xFF00897B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC8F2EA),
    onSecondaryContainer = Color(0xFF00352F),
    tertiary = Color(0xFFD97706),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE8B8),
    onTertiaryContainer = Color(0xFF4A2E00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F5FB),
    onBackground = Color(0xFF1B1B23),
    surface = Color(0xFFF6F5FB),
    onSurface = Color(0xFF1B1B23),
    surfaceVariant = Color(0xFFE8E6F2),
    onSurfaceVariant = Color(0xFF4A4860),
    outline = Color(0xFF7A7890),
    outlineVariant = Color(0xFFCAC7D8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFBFF),
    surfaceContainer = Color(0xFFEFEDF7),
    surfaceContainerHigh = Color(0xFFE9E7F1),
    surfaceContainerHighest = Color(0xFFE3E1EC),
    inverseSurface = Color(0xFF303039),
    inverseOnSurface = Color(0xFFF2EFF9),
    inversePrimary = Color(0xFFC4BDFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC4BDFF),
    onPrimary = Color(0xFF2A1D9E),
    primaryContainer = Color(0xFF4335D9),
    onPrimaryContainer = Color(0xFFE4E0FF),
    secondary = Color(0xFF6FDCC9),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005047),
    onSecondaryContainer = Color(0xFFC8F2EA),
    tertiary = Color(0xFFFFC65C),
    onTertiary = Color(0xFF3E2E00),
    tertiaryContainer = Color(0xFF6D4A00),
    onTertiaryContainer = Color(0xFFFFE8B8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111118),
    onBackground = Color(0xFFE6E4EE),
    surface = Color(0xFF111118),
    onSurface = Color(0xFFE6E4EE),
    surfaceVariant = Color(0xFF2A2936),
    onSurfaceVariant = Color(0xFFC8C6D4),
    outline = Color(0xFF918FA2),
    outlineVariant = Color(0xFF46454F),
    surfaceContainerLowest = Color(0xFF0C0C12),
    surfaceContainerLow = Color(0xFF191921),
    surfaceContainer = Color(0xFF1E1D27),
    surfaceContainerHigh = Color(0xFF28272F),
    surfaceContainerHighest = Color(0xFF33323B),
    inverseSurface = Color(0xFFE6E4EE),
    inverseOnSurface = Color(0xFF303039),
    inversePrimary = Color(0xFF5B4CF5),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Style utilitaire pour les gros montants. */
val AmountTextStyle: TextStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-1).sp, lineHeight = 44.sp)

@Composable
fun NotesDeFraisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
