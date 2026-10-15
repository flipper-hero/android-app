package hero.flipper.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import hero.flipper.app.nav.FilesRoute
import hero.flipper.app.nav.HeroSection

@Composable
fun HeroScaffold(model: HeroModel) {
    val nav = model.nav
    val tick = model.revision
    val section = nav.section
    val canPop = nav.approvalVisible || nav.confirm != null || nav.renameVisible ||
        (section == HeroSection.Files && (nav.filesRoute() == FilesRoute.Preview || model.filePath != "/ext"))
    BackHandler(enabled = canPop) {
        if (nav.approvalVisible) {
            nav.systemBack()
            return@BackHandler
        }
        if (section == HeroSection.Files && nav.filesRoute() == FilesRoute.List && model.filePath != "/ext") {
            model.popDirectory()
            return@BackHandler
        }
        nav.systemBack()
        model.touch()
    }
    LaunchedEffect(section, model.connected) {
        if (section == HeroSection.Files && model.connected) model.refreshFiles()
        model.watchScreen(section == HeroSection.Remote && model.connected)
    }
    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                HeroSection.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == section,
                        onClick = {
                            nav.select(item)
                            model.touch()
                        },
                        icon = { Text(item.title.take(1)) },
                        label = { Text(item.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (section) {
                HeroSection.Device -> DeviceScreen(model)
                HeroSection.Remote -> {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        RemoteScreen(model)
                    }
                }
                HeroSection.Files -> FilesScreen(model, nav)
                HeroSection.Agent -> AgentScreen(model)
                HeroSection.Settings -> SettingsScreen(model)
            }
            if (tick >= 0) {
                HeroDialogs(model)
                ApprovalSheet(model)
                ShortcutPicker(model)
            }
        }
    }
}
