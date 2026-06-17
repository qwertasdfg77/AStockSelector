package com.codex.astockselector.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B5CAD),
    onPrimary = Color.White,
    secondary = Color(0xFF47616F),
    tertiary = Color(0xFF7B5E2A),
    background = Color(0xFFF7F9FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFE7EDF2),
    outline = Color(0xFFB8C4CE),
)

@Composable
fun AStockSelectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
