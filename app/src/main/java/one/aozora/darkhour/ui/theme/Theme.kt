package one.aozora.darkhour.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = DarkHourPrimary,
    secondary = DarkHourSecondary,
    tertiary = DarkHourTertiary,
    surface = SurfaceDark,
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF24242B),
    onSurfaceVariant = Color(0xFFC7C7D1),
    outline = Color(0xFF474751),
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
)

@Composable
fun DarkHourTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content,
    )
}
