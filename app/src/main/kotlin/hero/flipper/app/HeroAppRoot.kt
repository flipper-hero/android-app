package hero.flipper.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HeroColors = darkColorScheme(
    primary = Color(0xFFFF8200),
    background = Color(0xFF0A0A0B),
    surface = Color(0xFF1A1A1A),
)

@Composable
fun HeroAppRoot(model: HeroModel) {
    MaterialTheme(colorScheme = HeroColors) {
        Surface(color = MaterialTheme.colorScheme.background) {
            HeroScaffold(model)
        }
    }
}
