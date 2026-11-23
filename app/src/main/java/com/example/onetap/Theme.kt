package com.example.onetap

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Pal {
    val bg0 = Color(0xFF070B14)
    val bg1 = Color(0xFF151B3B)
    val card = Color(0xFF172036)
    val cardHi = Color(0xFF1F2A47)
    val stroke = Color(0xFF2B3757)
    val accent = Color(0xFF8B7CFF)
    val ok = Color(0xFF2DD4A0)
    val bad = Color(0xFFFF5C7A)
    val warn = Color(0xFFFFB84D)
    val text = Color(0xFFEAF0FF)
    val sub = Color(0xFF93A0C0)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.accent,
            onPrimary = Color.White,
            secondary = Pal.ok,
            background = Pal.bg0,
            surface = Pal.card,
            onSurface = Pal.text,
            onBackground = Pal.text,
            surfaceVariant = Pal.cardHi,
            onSurfaceVariant = Pal.sub,
            outline = Pal.stroke,
        ),
        content = content,
    )
}
