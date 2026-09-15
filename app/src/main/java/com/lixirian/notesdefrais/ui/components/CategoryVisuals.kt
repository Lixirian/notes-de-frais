package com.lixirian.notesdefrais.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lixirian.notesdefrais.data.Category

/** Icône et couleur d'accent par catégorie (couleurs volontairement saturées, lisibles en sombre). */
data class CategoryVisual(val icon: ImageVector, val tint: Color, val container: Color)

val Category.visual: CategoryVisual
    get() = when (this) {
        Category.REPAS -> CategoryVisual(Icons.Rounded.Restaurant, Color(0xFFE85D3A), Color(0x33E85D3A))
        Category.TRANSPORT -> CategoryVisual(Icons.Rounded.Train, Color(0xFF2F80ED), Color(0x332F80ED))
        Category.HEBERGEMENT -> CategoryVisual(Icons.Rounded.Hotel, Color(0xFF9B51E0), Color(0x339B51E0))
        Category.CARBURANT -> CategoryVisual(Icons.Rounded.LocalGasStation, Color(0xFFF2994A), Color(0x33F2994A))
        Category.PARKING -> CategoryVisual(Icons.Rounded.LocalParking, Color(0xFF4F6D7A), Color(0x334F6D7A))
        Category.FOURNITURES -> CategoryVisual(Icons.Rounded.ShoppingBag, Color(0xFFEB5757), Color(0x33EB5757))
        Category.LOGICIELS -> CategoryVisual(Icons.Rounded.Cloud, Color(0xFF5B4CF5), Color(0x335B4CF5))
        Category.TELEPHONIE -> CategoryVisual(Icons.Rounded.Wifi, Color(0xFF00A88F), Color(0x3300A88F))
        Category.FORMATION -> CategoryVisual(Icons.Rounded.School, Color(0xFFD4A017), Color(0x33D4A017))
        Category.AUTRE -> CategoryVisual(Icons.Rounded.Category, Color(0xFF8A8A9E), Color(0x338A8A9E))
    }

@Composable
fun CategoryBadge(category: Category, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val v = category.visual
    Box(
        modifier = modifier.size(size).background(v.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(v.icon, contentDescription = category.label, tint = v.tint, modifier = Modifier.size(size * 0.5f))
    }
}
