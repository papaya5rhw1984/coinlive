package com.ryu.coinlive.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class CoinTheme(
    val name: String, val emoji: String,
    val accent: Long, val dark: Boolean, val bg: Long, val surface: Long
)

val COIN_THEMES = listOf(
    CoinTheme("다크",   "🌑", 0xFFF7A600, true,  0xFF14161C, 0xFF1F232C),
    CoinTheme("모던",   "🟦", 0xFF2E6BE6, false, 0xFFFFFFFF, 0xFFF1F4F9),
    CoinTheme("민트",   "🌿", 0xFF15B79A, false, 0xFFEFFBF8, 0xFFDFF4EE),
    CoinTheme("골드",   "🪙", 0xFFC9931A, false, 0xFFFFFBF0, 0xFFF6ECD5),
    CoinTheme("네온",   "💜", 0xFF8B5CF6, true,  0xFF16131F, 0xFF221C30)
)

object Brand { val Accent = Color(0xFFF7A600) }

@Composable
fun AppTheme(themeIndex: Int = 0, content: @Composable () -> Unit) {
    val t = COIN_THEMES[themeIndex.coerceIn(0, COIN_THEMES.size - 1)]
    val cs = if (t.dark)
        darkColorScheme(
            primary = Color(t.accent), background = Color(t.bg),
            surface = Color(t.surface), surfaceVariant = Color(t.surface)
        )
    else
        lightColorScheme(
            primary = Color(t.accent), background = Color(t.bg),
            surface = Color(t.surface), surfaceVariant = Color(t.surface)
        )
    MaterialTheme(colorScheme = cs, content = content)
}
