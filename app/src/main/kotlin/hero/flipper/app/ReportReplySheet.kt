package hero.flipper.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportReplySheet(model: HeroModel, reply: ReportedReply, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var content by remember(reply) { mutableStateOf(reply.text.take(6000)) }
    var comment by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(ContentReports.Category.offensive) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!sending) onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(context.getString(if (sent) R.string.report_sent else R.string.report_reply), fontSize = 22.sp)
            if (sent) {
                Text(context.getString(R.string.report_thanks))
                Button(onClick = onDismiss) { Text(context.getString(R.string.report_done)) }
            } else {
                Text(context.getString(R.string.report_disclosure), fontSize = 14.sp)
                OutlinedTextField(value = content, onValueChange = { content = it.take(6000) },
                    modifier = Modifier.fillMaxWidth(), enabled = !sending, maxLines = 6,
                    label = { Text(context.getString(R.string.report_excerpt)) })
                ContentReports.Category.entries.forEach { item ->
                    val label = context.getString(when (item) {
                        ContentReports.Category.offensive -> R.string.report_offensive
                        ContentReports.Category.harmful -> R.string.report_harmful
                        ContentReports.Category.incorrect -> R.string.report_incorrect
                        ContentReports.Category.other -> R.string.report_other
                    })
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !sending) { category = item },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = category == item, onClick = { category = item }, enabled = !sending)
                        Text(label)
                    }
                }
                OutlinedTextField(value = comment, onValueChange = { comment = it.take(500) },
                    modifier = Modifier.fillMaxWidth(), enabled = !sending, maxLines = 3,
                    label = { Text(context.getString(R.string.report_comment)) })
                if (failed) Text(context.getString(R.string.report_failed), color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, enabled = !sending) { Text(Catalog.text(context, "Cancel")) }
                    Button(enabled = !sending && content.isNotBlank(), onClick = {
                        sending = true
                        failed = false
                        scope.launch {
                            try {
                                model.reportReply(reply.copy(text = content), category, comment)
                                sent = true
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (_: Exception) { failed = true }
                            finally { sending = false }
                        }
                    }) { Text(context.getString(if (sending) R.string.report_sending else R.string.report_send)) }
                }
            }
        }
    }
}
