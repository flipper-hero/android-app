package hero.flipper.app

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onLongClick
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
import kotlinx.coroutines.launch

private val Accent = Color(ThemeColors.accent or 0xFF000000.toInt())
private val Card = Color(ThemeColors.card or 0xFF000000.toInt())
private val Stroke = Color(ThemeColors.stroke or 0xFF000000.toInt())
private val Ok = Color(ThemeColors.ok or 0xFF000000.toInt())
private val Danger = Color(ThemeColors.danger or 0xFF000000.toInt())
private val Ink = Color(0xFFF2F2F2)

private fun t(context: Context, english: String, vararg args: Any) = Catalog.text(context, english, *args)

@Composable
fun DeviceScreen(model: HeroModel) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(t(context, "Flipper"), color = Ink, fontSize = 28.sp)
        DeviceCard(model)
        model.statusMessage?.let { Text(t(context, it), color = Danger, fontSize = 13.sp) }
        model.shortcutStatus?.let { Text(t(context, it), color = Ink, fontSize = 13.sp) }
        if (model.firmwareStatus != "idle") Text(model.firmwareStatus, color = Ink, fontSize = 13.sp)
        if (model.firmwareJobRunning) TextButton(onClick = { model.cancelFirmware() }) { Text(t(context, "Cancel update")) }
        model.firmwareNotice?.let { Text(t(context, it), color = Accent, fontSize = 13.sp) }
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
            Text(context.getString(R.string.shortcuts), color = Accent, fontSize = 13.sp)
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
                contentDescription = context.getString(R.string.flipper_screen),
                modifier = Modifier.fillMaxWidth().height(220.dp).border(2.dp, Accent, RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(Modifier.fillMaxWidth().height(220.dp).background(Accent, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(t(context, "WAITING FOR SCREEN"), color = Color.Black)
            }
        }
        model.remoteError?.let { Text(t(context, it), color = Danger, fontSize = 12.sp) }
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
@OptIn(ExperimentalFoundationApi::class)
private fun PadKey(label: String, key: FlipperKey, model: HeroModel) {
    Box(
        Modifier
            .width(84.dp)
            .height(56.dp)
            .background(Accent, RoundedCornerShape(12.dp))
            .combinedClickable(role = Role.Button, onClick = { model.pressPad(key, false) },
                onLongClick = { model.pressPad(key, true) }),
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
            TextButton(onClick = { nav.systemBack(); model.touch() }) { Text(context.getString(R.string.back)) }
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
        model.fileError?.let { Text(t(context, it), color = Danger, fontSize = 12.sp) }
        if (model.filePath != "/ext") {
            TextButton(onClick = { model.popDirectory() }) { Text("↑") }
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
    val listState = rememberLazyListState()
    LaunchedEffect(model.transcript.size, model.busy) {
        if (model.transcript.isNotEmpty()) listState.animateScrollToItem(model.transcript.lastIndex)
    }
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(t(context, "Agent"), color = Ink, fontSize = 28.sp)
            TextButton(onClick = { model.newChat() }, enabled = !model.busy) { Text(t(context, "New chat")) }
        }
        if (!model.connected) Text(t(context, "Connect to a Flipper in the Device tab."), color = Danger, fontSize = 13.sp)
        if (!model.keyStored) Text(t(context, "Add your API key in Settings"), color = Danger, fontSize = 13.sp)
        if (model.engagement.active) {
            Text("ENGAGED ${edition().engagementSummary(model.engagement.profile)}", color = Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
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
                                t(context, suggestion),
                                color = Ink,
                                modifier = Modifier.fillMaxWidth().card().clickable { model.draft = t(context, suggestion); model.sendDraft() }.padding(12.dp),
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
                TextButton(onClick = { model.clearPhoto() }) { Text(context.getString(R.string.remove)) }
            }
        }
        OutlinedTextField(
                value = model.draft,
                onValueChange = { model.draft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(t(context, "Ask about your Flipper...")) },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { model.sendDraft() }),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { model.listen() }) { Text(context.getString(R.string.microphone)) }
            TextButton(onClick = { model.capturePhoto() }) { Text(context.getString(R.string.camera)) }
            Box(Modifier.weight(1f))
            Button(onClick = { model.sendDraft() }, enabled = !model.busy && model.connected && model.keyStored && model.draft.isNotBlank()) {
                Text(if (model.busy) "…" else context.getString(R.string.send))
            }
        }
    }
}

@Composable
private fun Bubble(entry: Transcript) {
    val context = LocalContext.current
    SelectionContainer {
    when (entry.kind) {
        "user" -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            Column(Modifier.widthIn(max = 280.dp).background(Accent, RoundedCornerShape(16.dp)).padding(12.dp)) {
                Text(entry.text, color = Color.Black)
                if (entry.photo) Text(t(context, "Photo attached"), color = Color.Black.copy(alpha = 0.7f), fontSize = 12.sp)
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: HeroModel) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var showAudit by remember { mutableStateOf(false) }
    var auditRecords by remember { mutableStateOf<List<hero.flipper.agent.AuditRecord>>(emptyList()) }
    var showProviders by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var modelSearch by remember { mutableStateOf("") }
    val editing = !model.keySaving && !model.busy
    val selected = hero.flipper.agent.AIProvider.from(model.provider) ?: hero.flipper.agent.AIProvider.OpenRouter
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(t(context, "Settings"), color = Ink, fontSize = 28.sp)
        Text(t(context, "AI connection"), color = Accent, fontSize = 16.sp)
        TextButton(onClick = { showProviders = true }, enabled = editing) {
            Text(t(context, "Provider") + ": " + selected.displayName)
        }
        OutlinedTextField(
            value = model.keyDraft,
            onValueChange = { model.keyDraft = it; model.keyStatus = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(t(context, if (model.keyStored) "Key stored (enter a new one to replace)" else "API key")) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            singleLine = true,
            enabled = editing,
        )
        TextButton(onClick = { modelSearch = ""; showModels = true; model.refreshModels() },
            enabled = editing && (model.keyStored || model.keyDraft.isNotBlank())) {
            Text(t(context, "Choose model"))
        }
        OutlinedTextField(
            value = model.modelId,
            onValueChange = { model.chooseModel(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(t(context, "Model ID")) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            singleLine = true,
            enabled = editing,
        )
        OutlinedTextField(
            value = model.apiBaseURL,
            onValueChange = { model.chooseBaseURL(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(t(context, "API base URL")) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            singleLine = true,
            enabled = editing,
        )
        TextButton(onClick = { links.openUri(selected.documentationURL) }) { Text(t(context, "Provider documentation")) }
        Button(onClick = { model.saveKey() }, enabled = editing && model.modelId.isNotBlank() && (model.keyStored || model.keyDraft.isNotBlank())) {
            Text(t(context, if (model.keySaving) "Testing connection…" else "Test and save"))
        }
        if (model.keyStored) TextButton(onClick = { model.removeKey() }, enabled = editing) { Text(t(context, "Remove key")) }
        model.keyStatus?.let { Text(t(context, it), color = Ink, fontSize = 14.sp) }
        Text(context.getString(R.string.ai_data_disclosure), color = Ink.copy(alpha = 0.75f), fontSize = 14.sp)
        Text(t(context, "Saving sends a short test message. Provider charges may apply. Each provider keeps its own key and model."), color = Ink.copy(alpha = 0.75f), fontSize = 14.sp)
        Text(t(context, "Choose a model with tool calling. Images require vision support."), color = Ink.copy(alpha = 0.75f), fontSize = 14.sp)
        Text(t(context, "Approvals"), color = Accent, fontSize = 16.sp)
        ToggleRow(t(context, "Skip prompts for medium-risk actions"), model.autoApproveMedium) {
            model.requestSettingChange("set_auto_approve_medium", it)
        }
        Text(t(context, "High-risk actions (deleting, transmitting, emulating, anything that can run code) always ask unless YOLO mode is on. After the agent has read content from the Flipper, every change asks again, even with this switch on."), color = Ink.copy(alpha = 0.75f), fontSize = 14.sp)
        ToggleRow(t(context, "Connect to the last Flipper on launch"), model.autoConnect) { model.setAutoConnectEnabled(it) }
        ToggleRow(t(context, "Read replies aloud"), model.readAloud) { model.setReadAloudEnabled(it) }
        ToggleRow(t(context, "YOLO mode"), model.yolo) { model.requestSettingChange("set_yolo_mode", it) }
        Text(t(context, "The agent changes, deletes, transmits and emulates without asking. Blocked paths (internal storage, key files, top-level recursive deletes) stay blocked. Resets when the app restarts. With the second switch on, the agent still asks after it read files from your Flipper, which is when a planted instruction could trick it."), color = Ink.copy(alpha = 0.75f), fontSize = 14.sp)
        if (model.yolo) {
            ToggleRow(t(context, "Still ask after reading Flipper content"), model.yoloAsksAfterReading) {
                model.requestSettingChange("set_yolo_asks_after_reading", it)
            }
        }
        EditionSettings(model)
        TextButton(onClick = { links.openUri("https://github.com/flipper-hero/android-app/blob/main/PRIVACY.md") }) { Text(t(context, "Privacy Policy")) }
        TextButton(onClick = { links.openUri("https://github.com/flipper-hero/android-app/issues") }) { Text(t(context, "Support on GitHub")) }
        TextButton(onClick = {
            scope.launch { auditRecords = model.auditRecords(400).asReversed(); showAudit = true }
        }) { Text(t(context, "Audit log")) }
    }
    if (showAudit) ModalBottomSheet(onDismissRequest = { showAudit = false }, containerColor = Card) {
        val dateFormat = java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
            .withZone(java.time.ZoneId.systemDefault())
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(t(context, "Audit log"), fontSize = 22.sp) }
            if (auditRecords.isEmpty()) item { Text(t(context, "No actions yet")) }
            items(auditRecords, key = { it.id }) { record ->
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(dateFormat.format(record.date), fontSize = 12.sp, color = Ink.copy(alpha = 0.75f))
                        Text(record.summary, fontSize = 16.sp)
                        Text("${record.tool} · ${record.decision.name} · ${if (record.succeeded) "ok" else "failed"}",
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        if (record.detail.isNotBlank()) Text(record.detail, fontSize = 14.sp)
                    }
                }
            }
        }
    }
    if (showProviders) ModalBottomSheet(onDismissRequest = { showProviders = false }, containerColor = Card) {
        LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
            items(hero.flipper.agent.AIProvider.entries) { provider ->
                TextButton(onClick = { model.chooseProvider(provider.wire); showProviders = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(provider.displayName, color = if (provider == selected) Accent else Ink)
                }
            }
        }
    }
    if (showModels) ModalBottomSheet(onDismissRequest = { showModels = false }, containerColor = Card) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(t(context, "Choose model"), color = Ink, fontSize = 20.sp)
            OutlinedTextField(value = modelSearch, onValueChange = { modelSearch = it },
                modifier = Modifier.fillMaxWidth(), label = { Text(context.getString(R.string.search_models)) }, singleLine = true)
            model.modelListStatus?.let { Text(t(context, it), color = Ink.copy(alpha = 0.7f), fontSize = 12.sp) }
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
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
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
                    Text(t(context, "At most %lld characters: letters, digits, hyphen, underscore. It takes effect after the Flipper restarts.", 8))
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
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t(context, presentation.title), color = Ink, fontSize = 20.sp)
            presentation.badge?.let { Text(t(context, it), color = Danger, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            Text(request.tool, color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            SelectionContainer { Text(request.summary, color = Ink) }
            if (request.reasons.isNotEmpty()) Text(request.reasons.joinToString("\n"), color = Ink.copy(alpha = 0.7f), fontSize = 12.sp)
            if (request.afterUntrustedContent) {
                Text(t(context, "This request came right after the agent read files from your Flipper. Text planted in a file could have caused it. If you did not ask for this, deny."), color = Color(0xFFFFD60A), fontSize = 13.sp)
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
    val confirm by rememberUpdatedState(onHeld)
    Box(
        Modifier
            .background(Accent, RoundedCornerShape(12.dp))
            .semantics {
                role = Role.Button
                contentDescription = label
                onLongClick(label) { confirm(); true }
            }
            .heightIn(min = 48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    holding = true
                    val outcome = withTimeoutOrNull(1200) { waitForUpOrCancellation() != null }
                    holding = false
                    if (outcome == null) {
                        confirm()
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
