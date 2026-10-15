package hero.flipper.app

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hero.flipper.agent.ApprovalRequest
import hero.flipper.agent.RiskLevel as AgentRisk
import hero.flipper.app.nav.ApprovalPresentation
import hero.flipper.app.nav.ConfirmControl
import hero.flipper.app.nav.ConfirmKind
import hero.flipper.app.nav.FilesRoute
import hero.flipper.app.nav.HeroNav
import hero.flipper.app.nav.RiskLevel
import hero.flipper.app.theme.ThemeColors
import hero.flipper.kit.FlipperKey
import kotlinx.coroutines.withTimeoutOrNull

private val Accent = Color(ThemeColors.accent or 0xFF000000.toInt())
private val Card = Color(ThemeColors.card or 0xFF000000.toInt())
private val Stroke = Color(ThemeColors.stroke or 0xFF000000.toInt())
private val Ok = Color(ThemeColors.ok or 0xFF000000.toInt())
private val Danger = Color(ThemeColors.danger or 0xFF000000.toInt())
private val Ink = Color(0xFFF2F2F2)

private fun t(context: Context, english: String) = Catalog.text(context, english)

@Composable
fun DeviceScreen(model: HeroModel) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t(context, "Flipper"), color = Ink, fontSize = 28.sp)
        DeviceCard(model)
        model.statusMessage?.let { Text(it, color = Danger, fontSize = 13.sp) }
        model.shortcutStatus?.let { Text(it, color = Ink, fontSize = 13.sp) }
        if (model.firmwareStatus != "idle") Text(model.firmwareStatus, color = Ink, fontSize = 13.sp)
        model.firmwareNotice?.let { Text(it, color = Accent, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.scan() }) { Text(if (model.scanning) t(context, "Scanning") else t(context, "Scan")) }
            if (model.connected) {
                Button(onClick = { model.disconnect() }) { Text(t(context, "Disconnect")) }
            }
        }
        if (!model.connected && model.found.isEmpty() && !model.scanning) {
            Text(
                t(context, "No Flipper found yet. Turn Bluetooth on at the Flipper (Settings > Bluetooth) and keep it unlocked and close."),
                color = Ink.copy(alpha = 0.7f),
            )
        }
        model.found.forEach { device ->
            Row(
                Modifier.fillMaxWidth().card().clickable { model.connect(device) }.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(device.name, color = Ink)
                Text("${device.rssi} dBm", color = Ink.copy(alpha = 0.6f))
            }
        }
        if (model.connected) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.nav.showRename(); model.renameDraft = ""; model.touch() }) { Text(t(context, "Rename")) }
                Button(onClick = { model.nav.showConfirm(ConfirmKind.Restart); model.touch() }) { Text(t(context, "Restart")) }
                Button(onClick = { model.nav.showConfirm(ConfirmKind.Firmware); model.touch() }) { Text(t(context, "Update")) }
            }
            model.profile?.let { profile ->
                Text(t(context, "Known to the agent"), color = Accent, fontSize = 13.sp)
                Text("${t(context, "Installed apps")}: ${profile.apps.size}", color = Ink, fontSize = 12.sp)
                profile.folders.filter { it.fileCount > 0 }.forEach { folder ->
                    Text("${folder.path.substringAfterLast('/')}: ${folder.fileCount}", color = Ink, fontSize = 12.sp)
                }
            }
            TextButton(onClick = { model.rescanDevice() }) { Text(t(context, "Rescan")) }
            Text("Shortcuts", color = Accent, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { model.openShortcut(HeroShortcut.Ask) }) { Text(t(context, "Ask the Agent")) }
                TextButton(onClick = { model.openShortcut(HeroShortcut.Battery) }) { Text(t(context, "Battery")) }
                TextButton(onClick = { model.openShortcut(HeroShortcut.Stop) }) { Text(t(context, "Stop Flipper App")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { model.openShortcut(HeroShortcut.Emulate) }) { Text(t(context, "Emulate Card or Key")) }
                TextButton(onClick = { model.openShortcut(HeroShortcut.Send) }) { Text(t(context, "Send Signal")) }
            }
        }
    }
}

@Composable
fun ShortcutPicker(model: HeroModel) {
    val kind = model.shortcutKind ?: return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { model.closeShortcut() },
        title = { Text(t(context, if (kind == HeroShortcut.Emulate) "Emulate Card or Key" else "Send Signal")) },
        text = {
            Column {
                if (!model.connected) Text(t(context, "Connect to a Flipper in the Device tab."))
                else {
                    if (kind == HeroShortcut.Send) OutlinedTextField(
                        value = model.shortcutButton,
                        onValueChange = { model.shortcutButton = it },
                        label = { Text(t(context, "Infrared button")) },
                        singleLine = true,
                    )
                    model.shortcutStatus?.let { Text(it) }
                    LazyColumn(Modifier.height(320.dp)) {
                        items(model.shortcutFiles.toList()) { path ->
                            Text(path, color = Ink,
                                modifier = Modifier.fillMaxWidth().clickable { model.selectShortcutFile(path) }.padding(12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { model.closeShortcut() }) { Text(t(context, "Cancel")) } },
    )
}

@Composable
private fun DeviceCard(model: HeroModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(model.deviceTitle, color = Ink, fontSize = 22.sp)
            Text(t(context, if (model.connected) "Connected" else "Not connected"), color = if (model.connected) Ok else Danger, fontSize = 12.sp)
        }
        Text(model.firmwareLine, color = Ink.copy(alpha = 0.65f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Meter(t(context, "Battery"), model.batteryFraction, model.batterySummary, Modifier.weight(1f))
            Meter(t(context, "SD card"), model.sdFraction, model.sdSummary, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Meter(title: String, fraction: Float?, caption: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Accent, fontSize = 12.sp)
        LinearProgressIndicator(
            progress = { (fraction ?: 0f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = Accent,
            trackColor = Color.White.copy(alpha = 0.08f),
        )
        Text(caption, color = Ink.copy(alpha = 0.65f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
fun RemoteScreen(model: HeroModel) {
    val context = LocalContext.current
    if (!model.connected) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t(context, "Remote"), color = Ink, fontSize = 28.sp)
            Text(t(context, "Not connected"), color = Danger)
            Text(t(context, "Connect to a Flipper in the Device tab."), color = Ink.copy(alpha = 0.7f))
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(t(context, if (model.screenLive) "LIVE" else "WAITING FOR SCREEN"), color = if (model.screenLive) Ok else Ink.copy(alpha = 0.7f))
        val bitmap = model.screenBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Flipper screen",
                modifier = Modifier.fillMaxWidth().height(220.dp).border(2.dp, Accent, RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(Modifier.fillMaxWidth().height(220.dp).background(Accent, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(t(context, "WAITING FOR SCREEN"), color = Color.Black)
            }
        }
        model.remoteError?.let { Text(it, color = Danger, fontSize = 12.sp) }
        Text(t(context, "Tap for a short press, hold for a long press. Hold Back to leave an app."), color = Ink.copy(alpha = 0.7f), fontSize = 12.sp)
        Dpad(model)
    }
}

@Composable
private fun Dpad(model: HeroModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PadKey("Up", FlipperKey.Up, model)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadKey("Left", FlipperKey.Left, model)
            PadKey("Ok", FlipperKey.Ok, model)
            PadKey("Right", FlipperKey.Right, model)
        }
        PadKey("Down", FlipperKey.Down, model)
        PadKey("Back", FlipperKey.Back, model)
    }
}

@Composable
private fun PadKey(label: String, key: FlipperKey, model: HeroModel) {
    Box(
        Modifier
            .width(84.dp)
            .height(56.dp)
            .background(Accent, RoundedCornerShape(12.dp))
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown()
                    val outcome = withTimeoutOrNull(400) { waitForUpOrCancellation() != null }
                    when (outcome) {
                        null -> {
                            model.pressPad(key, true)
                            waitForUpOrCancellation()
                        }
                        true -> model.pressPad(key, false)
                        false -> Unit
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.Black, fontSize = 16.sp)
    }
}

@Composable
fun FilesScreen(model: HeroModel, nav: HeroNav) {
    val context = LocalContext.current
    if (!model.connected) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(t(context, "Files"), color = Ink, fontSize = 28.sp)
            Text(t(context, "Not connected"), color = Danger)
            Text(t(context, "Connect to a Flipper in the Device tab."), color = Ink.copy(alpha = 0.7f))
        }
        return
    }
    if (nav.filesRoute() == FilesRoute.Preview) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TextButton(onClick = { nav.systemBack(); model.touch() }) { Text("Back") }
            Text(model.previewTitle, color = Ink, fontSize = 20.sp)
            Text(
                model.previewText,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            )
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(if (model.filePath == "/ext") t(context, "SD card") else model.filePath.substringAfterLast('/'), color = Ink, fontSize = 22.sp)
        Text(model.filePath, color = Ink.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        if (model.fileSpace.isNotEmpty()) Text(model.fileSpace, color = Ink.copy(alpha = 0.7f), fontSize = 12.sp)
        model.fileError?.let { Text(it, color = Danger, fontSize = 12.sp) }
        if (model.filePath != "/ext") {
            TextButton(onClick = { model.popDirectory() }) { Text("Up") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(model.fileEntries.size) { index ->
                val entry = model.fileEntries[index]
                Row(
                    Modifier.fillMaxWidth().clickable { model.openEntry(entry) }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text((if (entry.isDirectory) "▸ " else "") + entry.name, color = Ink, modifier = Modifier.weight(1f))
                    if (!entry.isDirectory) Text("${entry.size}", color = Ink.copy(alpha = 0.55f), fontSize = 12.sp)
                }
            }
            if (!model.fileLoading && model.fileEntries.isEmpty() && model.fileError == null) {
                item { Text(t(context, "Empty"), color = Ink.copy(alpha = 0.6f)) }
            }
        }
    }
}

@Composable
fun AgentScreen(model: HeroModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(t(context, "Agent"), color = Ink, fontSize = 28.sp)
            TextButton(onClick = { model.newChat() }, enabled = !model.busy) { Text(t(context, "New chat")) }
        }
        if (!model.connected) Text("Not connected. Connect to a Flipper in the Device tab.", color = Danger, fontSize = 13.sp)
        if (!model.keyStored) Text(t(context, "Add your API key in Settings"), color = Danger, fontSize = 13.sp)
        if (model.engagement.active) {
            Text("ENGAGED ${edition().engagementSummary(model.engagement.profile)}", color = Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (model.transcript.isEmpty() && !model.busy) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                        Text(t(context, "Ask FlipperHero"), color = Ink, fontSize = 22.sp)
                        Text(t(context, "Read files, check the device, make changes you approve."), color = Ink.copy(alpha = 0.7f))
                        listOf(
                            "How full are the battery and the SD card?",
                            "Which firmware is my Flipper running?",
                            "Show me what is in /ext",
                            "Which NFC cards have I saved?",
                        ).forEach { suggestion ->
                            Text(
                                suggestion,
                                color = Ink,
                                modifier = Modifier.fillMaxWidth().card().clickable { model.draft = suggestion; model.sendDraft() }.padding(12.dp),
                            )
                        }
                    }
                }
            }
            items(model.transcript.size) { index ->
                Bubble(model.transcript[index])
            }
        }
        if (model.photoAttached) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t(context, "Photo attached"), color = Ink.copy(alpha = 0.7f), fontSize = 12.sp)
                TextButton(onClick = { model.clearPhoto() }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = model.draft,
                onValueChange = { model.draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { model.sendDraft() }),
            )
            TextButton(onClick = { model.listen() }) { Text("Mic") }
            TextButton(onClick = { model.capturePhoto() }) { Text("Cam") }
            Button(onClick = { model.sendDraft() }, enabled = !model.busy) { Text(if (model.busy) "…" else "Send") }
        }
    }
}

@Composable
private fun Bubble(entry: Transcript) {
    when (entry.kind) {
        "user" -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            Column(Modifier.widthIn(max = 280.dp).background(Accent, RoundedCornerShape(16.dp)).padding(12.dp)) {
                Text(entry.text, color = Color.Black)
                if (entry.photo) Text("Photo attached", color = Color.Black.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }
        "tool" -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).card().padding(10.dp)) {
            val color = when (entry.status) {
                "ok" -> Ok
                "running" -> Accent
                else -> Danger
            }
            Text("${entry.tool}  ${entry.status}", color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(entry.text, color = Ink, fontSize = 13.sp)
        }
        else -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(entry.text, color = Ink, modifier = Modifier.widthIn(max = 320.dp).background(Card, RoundedCornerShape(16.dp)).padding(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: HeroModel) {
    val context = LocalContext.current
    var testNote by remember { mutableStateOf<String?>(null) }
    var showModels by remember { mutableStateOf(false) }
    var modelSearch by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t(context, "Settings"), color = Ink, fontSize = 28.sp)
        Text(t(context, "API key"), color = Accent, fontSize = 13.sp)
        Text(if (model.keyStored) "A key is stored for this provider. It is not shown." else "No key stored.", color = Ink.copy(alpha = 0.75f), fontSize = 13.sp)
        OutlinedTextField(
            value = model.keyDraft,
            onValueChange = { model.keyDraft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(t(context, "API key")) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.saveKey() }, enabled = !model.keySaving && model.keyDraft.isNotBlank()) {
                Text(if (model.keySaving) "Testing…" else t(context, "Test and save"))
            }
            TextButton(onClick = { model.beginConnectionTest { testNote = it } }) { Text("Test") }
            if (model.keyStored) TextButton(onClick = { model.removeKey() }) { Text(t(context, "Remove key")) }
        }
        model.keyStatus?.let { Text(it, color = Ink, fontSize = 12.sp) }
        testNote?.let { Text(it, color = Ink, fontSize = 13.sp) }
        Text(t(context, "Provider"), color = Accent, fontSize = 13.sp)
        hero.flipper.agent.AIProvider.entries.forEach { provider ->
            Text(
                provider.displayName + if (provider.wire == model.provider) "  •" else "",
                color = if (provider.wire == model.provider) Accent else Ink,
                modifier = Modifier.fillMaxWidth().clickable { model.chooseProvider(provider.wire) }.padding(vertical = 4.dp),
            )
        }
        OutlinedTextField(
            value = model.modelId,
            onValueChange = { model.chooseModel(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(t(context, "Model")) },
            singleLine = true,
        )
        OutlinedTextField(
            value = model.apiBaseURL,
            onValueChange = { model.chooseBaseURL(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(t(context, "API base URL")) },
            singleLine = true,
        )
        TextButton(onClick = { modelSearch = ""; showModels = true; model.refreshModels() }, enabled = model.keyStored) {
            Text(t(context, "Choose model"))
        }
        ToggleRow("Auto-approve medium", model.autoApproveMedium) { model.setAutoApprove(it) }
        Text("High-risk actions always ask unless YOLO mode is on. After the agent has read content from the Flipper, every change asks again.", color = Ink.copy(alpha = 0.6f), fontSize = 12.sp)
        ToggleRow("Auto-connect", model.autoConnect) { model.setAutoConnectEnabled(it) }
        ToggleRow(t(context, "Read replies aloud"), model.readAloud) { model.setReadAloudEnabled(it) }
        ToggleRow("YOLO", model.yolo) { enabled ->
            if (enabled) {
                model.nav.showConfirm(ConfirmKind.Yolo)
                model.touch()
            } else {
                model.setYoloEnabled(false)
            }
        }
        Text("The agent changes, deletes, transmits and emulates without asking. Blocked paths stay blocked. Resets when the app restarts.", color = Ink.copy(alpha = 0.6f), fontSize = 12.sp)
        if (model.yolo) {
            ToggleRow("Ask after untrusted reads", model.yoloAsksAfterReading) { model.setYoloAsks(it) }
        }
        EditionSettings(model)
        Text("Audit", color = Accent, fontSize = 13.sp)
        if (model.auditLines.isEmpty()) Text(t(context, "No actions yet"), color = Ink.copy(alpha = 0.6f))
        model.auditLines.take(20).forEach { line ->
            Text(line, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
    if (showModels) ModalBottomSheet(onDismissRequest = { showModels = false }, containerColor = Card) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(t(context, "Choose model"), color = Ink, fontSize = 20.sp)
            OutlinedTextField(value = modelSearch, onValueChange = { modelSearch = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Search models") }, singleLine = true)
            model.modelListStatus?.let { Text(it, color = Ink.copy(alpha = 0.7f), fontSize = 12.sp) }
            val shown = model.availableModels.filter {
                modelSearch.isBlank() || it.id.contains(modelSearch, ignoreCase = true) || it.name.contains(modelSearch, ignoreCase = true)
            }
            LazyColumn(Modifier.height(400.dp)) {
                items(shown) { item ->
                    Text(item.name + if (item.name != item.id) " (${item.id})" else "",
                        color = if (item.id == model.modelId) Accent else Ink,
                        modifier = Modifier.fillMaxWidth().clickable { model.chooseModel(item.id); showModels = false }.padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun HeroDialogs(model: HeroModel) {
    val nav = model.nav
    val context = LocalContext.current
    when (nav.confirm) {
        ConfirmKind.Restart -> AlertDialog(
            onDismissRequest = { nav.scrimTap(); model.touch() },
            confirmButton = { TextButton(onClick = { nav.acceptConfirm(); model.beginRestart(); model.touch() }) { Text(t(context, "Restart")) } },
            dismissButton = { TextButton(onClick = { nav.systemBack(); model.touch() }) { Text(t(context, "Cancel")) } },
            title = { Text(t(context, "Restart")) },
            text = { Text(t(context, "Whatever runs on the Flipper stops. The app reconnects on its own.")) },
        )
        ConfirmKind.Firmware -> AlertDialog(
            onDismissRequest = { nav.scrimTap(); model.touch() },
            confirmButton = { TextButton(onClick = { nav.acceptConfirm(); model.beginFirmware(); model.touch() }) { Text(t(context, "Update")) } },
            dismissButton = { TextButton(onClick = { nav.systemBack(); model.touch() }) { Text(t(context, "Cancel")) } },
            title = { Text(t(context, "Update")) },
            text = { Text(t(context, "About 12 MB go over Bluetooth, which takes 15 to 25 minutes. Keep the phone close and the app open; the battery needs 30% or a charger. The Flipper restarts and finishes on its own.")) },
        )
        ConfirmKind.Yolo -> AlertDialog(
            onDismissRequest = { nav.scrimTap(); model.touch() },
            confirmButton = { TextButton(onClick = { nav.acceptConfirm(); model.setYoloEnabled(true) }) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { nav.systemBack(); model.touch() }) { Text(t(context, "Cancel")) } },
            title = { Text(t(context, "YOLO")) },
            text = { Text(t(context, "The agent will delete files, transmit radio signals and emulate cards without asking. A tampered file on the Flipper could mislead it. Only this session.")) },
        )
        null -> Unit
    }
    if (nav.renameVisible) {
        AlertDialog(
            onDismissRequest = { nav.scrimTap(); model.touch() },
            confirmButton = {
                TextButton(onClick = {
                    nav.systemBack()
                    model.applyRename()
                }) { Text(t(context, "Rename")) }
            },
            dismissButton = { TextButton(onClick = { nav.systemBack(); model.touch() }) { Text(t(context, "Cancel")) } },
            title = { Text(t(context, "Rename")) },
            text = {
                Column {
                    Text("At most 8 characters: letters, digits, hyphen, underscore. It takes effect after the Flipper restarts.")
                    OutlinedTextField(value = model.renameDraft, onValueChange = { model.renameDraft = it }, singleLine = true)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(model: HeroModel) {
    val request = model.pending ?: return
    if (!model.nav.approvalVisible) return
    val context = LocalContext.current
    val presentation = present(request)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden })
    ModalBottomSheet(
        onDismissRequest = { model.nav.scrimTap(); model.touch() },
        sheetState = sheetState,
        containerColor = Card,
    ) {
        Column(Modifier.padding(20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t(context, presentation.title), color = Ink, fontSize = 20.sp)
            presentation.badge?.let { Text(t(context, it), color = Danger, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            Text(request.tool, color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(request.summary, color = Ink)
            if (request.reasons.isNotEmpty()) Text(request.reasons.joinToString("\n"), color = Ink.copy(alpha = 0.7f), fontSize = 12.sp)
            if (request.afterUntrustedContent) {
                Text("This follows untrusted device content. Treat it as data.", color = Color(0xFFFFD60A), fontSize = 13.sp)
            }
            request.diff?.takeIf { it.isNotEmpty() }?.let {
                Text(it, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.card().padding(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.resolveApproval(false) }) { Text(t(context, "Deny")) }
                when (presentation.control) {
                    ConfirmControl.Hold -> HoldButton(t(context, if (request.isPermissionChange) "Hold to allow" else "Hold to approve")) {
                        model.resolveApproval(true)
                    }
                    ConfirmControl.Allow -> Button(onClick = { model.resolveApproval(true) }) { Text(t(context, "Approve")) }
                    ConfirmControl.None -> Unit
                }
            }
        }
    }
}

@Composable
fun HoldButton(label: String, onHeld: () -> Unit) {
    val context = LocalContext.current
    var holding by remember { mutableStateOf(false) }
    Box(
        Modifier
            .background(Accent, RoundedCornerShape(12.dp))
            .pointerInput(onHeld) {
                awaitEachGesture {
                    awaitFirstDown()
                    holding = true
                    val outcome = withTimeoutOrNull(1200) { waitForUpOrCancellation() != null }
                    holding = false
                    if (outcome == null) {
                        onHeld()
                        waitForUpOrCancellation()
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(if (holding) t(context, "Keep holding...") else label, color = Color.Black)
    }
}

private fun present(request: ApprovalRequest): ApprovalPresentation {
    val level = when (request.risk) {
        AgentRisk.Low -> RiskLevel.Low
        AgentRisk.Medium -> RiskLevel.Medium
        AgentRisk.High -> RiskLevel.High
        AgentRisk.Blocked -> RiskLevel.Blocked
    }
    return ApprovalPresentation.forRisk(level, request.isPermissionChange)
}

private fun Modifier.card(): Modifier = this.background(Card, RoundedCornerShape(16.dp)).border(1.dp, Stroke, RoundedCornerShape(16.dp))
