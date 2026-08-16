package net.enjoytech.timeshiftradio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFF5F7FA),
    surface = Color.White,
    onSurface = Color(0xFF1C1C1E)
)

/**
 * UI設計ポリシー準拠のテーマ。
 * ヘッダー/フッターをAccent色で統一するため、Dynamic Color・ダークテーマ追従は使用しない。
 */
@Composable
fun TimeShiftRadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content
    )
}
