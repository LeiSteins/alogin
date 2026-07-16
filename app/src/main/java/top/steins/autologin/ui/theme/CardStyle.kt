package top.steins.autologin.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val AppCardShape = RoundedCornerShape(16.dp)

@Composable
fun appCardBorder(): BorderStroke {
    val borderColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        Color.White.copy(alpha = 0.8f)
    }
    return BorderStroke(1.dp, borderColor)
}

@Composable
fun appCardElevation(): CardElevation {
    val isDark = isSystemInDarkTheme()
    return CardDefaults.cardElevation(
        defaultElevation = if (isDark) 2.dp else 0.dp,
        pressedElevation = if (isDark) 2.dp else 0.dp,
        focusedElevation = if (isDark) 2.dp else 0.dp,
        hoveredElevation = if (isDark) 2.dp else 0.dp,
        draggedElevation = if (isDark) 3.dp else 0.dp
    )
}
