package hero.flipper.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import hero.flipper.app.nav.FilesRoute
import hero.flipper.app.nav.HeroSection

@Composable
fun HeroScaffold(model: HeroModel) {
    val nav = model.nav
    val context = LocalContext.current
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 600.dp
        Scaffold(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!expanded) NavigationBar {
                    HeroSection.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == section,
                            onClick = {
                                nav.select(item)
                                model.touch()
                            },
                            icon = { Icon(sectionIcon(item), contentDescription = null) },
                            label = { Text(Catalog.text(context, item.title)) },
                        )
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (expanded) NavigationRail(Modifier.fillMaxHeight()) {
                    HeroSection.entries.forEach { item ->
                        NavigationRailItem(selected = item == section,
                            onClick = { nav.select(item); model.touch() },
                            icon = { Icon(sectionIcon(item), contentDescription = null) },
                            label = { Text(Catalog.text(context, item.title)) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
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
    }
}

private fun sectionIcon(section: HeroSection) = when (section) {
    HeroSection.Device -> Icons.Default.Home
    HeroSection.Remote -> Icons.Default.PlayArrow
    HeroSection.Files -> Icons.AutoMirrored.Filled.List
    HeroSection.Agent -> Icons.Default.Email
    HeroSection.Settings -> Icons.Default.Settings
}
