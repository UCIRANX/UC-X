package ir.uciran.uctor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Violet = Color(0xFF8B5CF6)
val Cyan = Color(0xFF22D3EE)
val Pink = Color(0xFFF472B6)
val Green = Color(0xFF22C55E)
val Bg = Color(0xFF0B0B14)
val Surface1 = Color(0xFF161626)
val Surface2 = Color(0xFF24243D)

@Composable
fun UcTorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Violet,
            onPrimary = Color.White,
            secondary = Cyan,
            tertiary = Pink,
            background = Bg,
            onBackground = Color(0xFFEDEDF5),
            surface = Surface1,
            onSurface = Color(0xFFEDEDF5),
            surfaceVariant = Surface2,
            onSurfaceVariant = Color(0xFFB4B4C8),
            surfaceContainer = Surface2,
            error = Color(0xFFF87171),
        ),
        content = content,
    )
}
