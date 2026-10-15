package hero.flipper.app

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hero.flipper.agent.AgentEdition
import hero.flipper.agent.EngagementProfile
import hero.flipper.agent.EngagementState
import hero.flipper.agent.EngagementReport
import hero.flipper.agent.FlipperControlling
import hero.flipper.agent.FullEdition
import hero.flipper.agent.MarauderBridge
import hero.flipper.agent.RawCommands
import hero.flipper.kit.FlipperRpcClient
import java.time.Instant
import kotlinx.coroutines.launch

fun edition(): AgentEdition = FullEdition

/** Full edition sends raw command JSON through the connected RPC client. */
class RawDevice(
    private val inner: FlipperControlling,
    private val rpc: FlipperRpcClient,
) : FlipperControlling by inner {
    override suspend fun sendRaw(request: String): String = RawCommands.exchange(rpc, request)
    override suspend fun marauder(action: String, mode: String, seconds: Int, index: Int?): String =
        MarauderBridge(rpc).run(action, mode, seconds, index)
}

fun wrapDevice(inner: FlipperControlling, rpc: FlipperRpcClient): FlipperControlling = RawDevice(inner, rpc)

fun auditEngagementChange(model: HeroModel, enabled: Boolean, note: String) {
    model.recordEngagementChange("set_engagement_mode", if (enabled) "Arm engagement mode" else "End engagement mode",
        high = enabled, note = note)
}

@Composable
fun EditionSettings(model: HeroModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showArm by remember { mutableStateOf(false) }
    var autoApprovals by remember { mutableStateOf(true) }
    var rawRpc by remember { mutableStateOf(false) }
    var autoBadKb by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    Column {
        Text(Catalog.text(context, "Engagement mode"))
        if (model.engagement.active) {
            Text("Armed (${edition().engagementSummary(model.engagement.profile)})")
            if (model.engagement.profile.note.isNotEmpty()) Text("${Catalog.text(context, "Scope")}: ${model.engagement.profile.note}")
            Button(onClick = { model.disarm() }) { Text(Catalog.text(context, "Disarm")) }
        } else {
            Text(Catalog.text(context, "Off"))
            Button(onClick = { showArm = true }, enabled = model.connected) {
                Text(context.getString(R.string.kbfddfe24d657))
            }
        }
        Button(onClick = {
            scope.launch {
                val report = EngagementReport.markdown(model.auditRecords(400), model.engagement)
                val share = Intent(Intent.ACTION_SEND)
                    .setType("text/markdown")
                    .putExtra(Intent.EXTRA_TEXT, report)
                context.startActivity(Intent.createChooser(share, Catalog.text(context, "Export report")))
            }
        }) { Text(Catalog.text(context, "Export report")) }
    }
    if (showArm) AlertDialog(
        onDismissRequest = { showArm = false },
        title = { Text(context.getString(R.string.kbfddfe24d657)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(context.getString(R.string.k10b781509d0d))
                Text(Catalog.text(context, "Run actions without prompts"))
                Switch(checked = autoApprovals, onCheckedChange = { autoApprovals = it })
                Text(context.getString(R.string.k3fef64768db0))
                Switch(checked = rawRpc, onCheckedChange = { rawRpc = it })
                Text(context.getString(R.string.kd60c4751fe70))
                Switch(checked = autoBadKb, onCheckedChange = { autoBadKb = it })
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(Catalog.text(context, "Scope note, e.g. client name or location")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            HoldButton(Catalog.text(context, "Hold to arm")) {
                val profile = EngagementProfile.cleaned(autoApprovals, rawRpc, autoBadKb, note)
                model.arm(EngagementState(true, profile, Instant.now()))
                showArm = false
            }
        },
        dismissButton = { TextButton(onClick = { showArm = false }) { Text(Catalog.text(context, "Cancel")) } },
    )
}
