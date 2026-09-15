package com.lixirian.notesdefrais.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Fond « aurore » de l'app : une base douce et trois halos de couleur (indigo, rose, menthe)
 * dessinés en dégradés radiaux, sans flou coûteux. Les écrans posent leurs cartes par-dessus
 * (Scaffold transparent), ce qui donne de la profondeur sans nuire à la lisibilité.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val base = if (dark) Color(0xFF0E0D16) else Color(0xFFF4F3FB)
    val indigo = if (dark) Color(0xFF5B4CF5).copy(alpha = 0.42f) else Color(0xFF7C6CFF).copy(alpha = 0.30f)
    val pink = if (dark) Color(0xFFEC4899).copy(alpha = 0.22f) else Color(0xFFF472B6).copy(alpha = 0.22f)
    val mint = if (dark) Color(0xFF2DD4BF).copy(alpha = 0.16f) else Color(0xFF34D399).copy(alpha = 0.20f)
    val amber = if (dark) Color(0xFFF59E0B).copy(alpha = 0.10f) else Color(0xFFFBBF24).copy(alpha = 0.16f)

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(base)
            val w = size.width
            val h = size.height
            fun halo(color: Color, center: Offset, radius: Float) {
                drawCircle(
                    brush = Brush.radialGradient(colors = listOf(color, color.copy(alpha = 0f)), center = center, radius = radius),
                    radius = radius,
                    center = center,
                )
            }
            halo(indigo, Offset(w * 0.05f, h * 0.02f), w * 0.95f)
            halo(pink, Offset(w * 1.05f, h * 0.18f), w * 0.75f)
            halo(mint, Offset(w * 0.15f, h * 0.78f), w * 0.70f)
            halo(amber, Offset(w * 0.95f, h * 1.0f), w * 0.60f)
        }
        content()
    }
}
