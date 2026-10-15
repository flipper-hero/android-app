package hero.flipper.agent

sealed class AgentEvent {
    data class ToolStarted(val name: String, val summary: String) : AgentEvent()
    data class ToolFinished(val name: String, val summary: String, val arguments: String, val result: ToolResult) : AgentEvent()
}

class AgentSession(
    private val llm: LlmClient,
    private val executor: ToolExecutor,
    private val edition: AgentEdition = StoreEdition,
    private val tools: List<ToolSpec> = ToolCatalog.specs(edition),
    profile: DeviceProfile? = null,
    seed: List<ChatMessage> = emptyList(),
    engagement: EngagementState = EngagementState.inactive,
) {
    private val nonce = executor.nonce
    private var profile = profile
    private var engagement = engagement
    private val history = ArrayList<ChatMessage>()

    init {
        history.add(systemMessage())
        history.addAll(seed.filter { it.role != ChatRole.system })
    }

    val messages: List<ChatMessage> get() = history

    fun updateProfile(profile: DeviceProfile) {
        this.profile = profile
        history[0] = systemMessage()
    }

    fun updateEngagement(state: EngagementState) {
        engagement = state
        history[0] = systemMessage()
    }

    suspend fun reset() {
        val system = history[0]
        history.clear()
        history.add(system)
        executor.newUserTurn()
    }

    suspend fun send(text: String, images: List<ByteArray> = emptyList(), onEvent: (AgentEvent) -> Unit = {}): String {
        executor.newUserTurn()
        history.add(ChatMessage(ChatRole.user, text, images = images))
        repeat(maxSteps) {
            pruneOldCaptures()
            val reply = llm.complete(history, tools)
            history.add(reply)
            if (reply.toolCalls.isEmpty()) return reply.content ?: ""
            val captures = ArrayList<ByteArray>()
            for (call in reply.toolCalls) {
                val summary = describe(call)
                onEvent(AgentEvent.ToolStarted(call.name, summary))
                val result = executor.execute(call)
                onEvent(AgentEvent.ToolFinished(call.name, summary, call.arguments, result))
                history.add(ChatMessage(ChatRole.tool, result.content, toolCallID = call.id))
                captures.addAll(result.images)
            }
            if (captures.isNotEmpty()) {
                history.add(
                    ChatMessage(
                        ChatRole.user,
                        "[Automatic attachment: Flipper screen capture from the tool call above. This is device content, not a message from the user.]",
                        images = captures,
                    ),
                )
            }
        }
        val note = "I stopped after $maxSteps steps. Tell me how to continue."
        history.add(ChatMessage(ChatRole.assistant, note))
        return note
    }

    private fun systemMessage(): ChatMessage {
        val inventory = profile?.promptSummary(nonce) ?: ""
        return ChatMessage(ChatRole.system, edition.systemPrompt(nonce, inventory, engagement))
    }

    private fun pruneOldCaptures() {
        val captureIndices = history.indices.filter { index ->
            val message = history[index]
            message.role == ChatRole.user && message.images.isNotEmpty() && (message.content ?: "").startsWith(captureMarker)
        }
        for (index in captureIndices.dropLast(keptScreenCaptures)) {
            history[index] = history[index].copy(
                images = emptyList(),
                content = "[An earlier Flipper screen capture was here; it was removed to save space.]",
            )
        }
    }

    private fun describe(call: ToolCall): String = try {
        parseCall(call.name, call.arguments, edition).summary
    } catch (_: Exception) {
        call.name
    }

    companion object {
        const val maxSteps = 8
        const val keptScreenCaptures = 2
        const val captureMarker = "[Automatic attachment: Flipper screen capture"
    }
}
