package com.example.agent

import com.example.agent.tools.AgentTool
import com.example.domain.model.AIProviderConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

class AgentEngine(
    private val config: AIProviderConfig,
    private val tools: List<AgentTool>,
    private val aiClient: AIClient
) {
    fun processRequest(
        request: String,
        repoContext: String,
        attachments: List<AgentAttachment> = emptyList(),
        requireToolOnStart: Boolean = false,
        resumeState: AgentResumeState? = null,
        onCheckpoint: (AgentResumeState) -> Unit = {}
    ): Flow<AgentStatus> = channelFlow {
        val listFilesTool = tools.find { it.name == "listFiles" }

        val basePrompt: String
        var iterations: Int
        var accessCorrectionAttempts: Int
        var completionAuditRequested: Boolean
        var completionCorrectionAttempts: Int
        var noToolActionCorrectionAttempts: Int
        var forceToolNextTurn: Boolean
        var controlInstruction: String
        val contextManager: AgentContextManager

        if (resumeState == null) {
            send(AgentStatus("Analyzing Request..."))

            val repositoryRoot = if (listFilesTool == null) {
                "Repository listing tool is not configured."
            } else {
                try {
                    listFilesTool.execute(emptyMap())
                } catch (error: Exception) {
                    "Repository listing failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }

            val normalizedRequest = request.lowercase()
            val workflowContext = if (
                listFilesTool != null &&
                listOf("github", "workflow", "action", "build", ".yml", ".yaml")
                    .any { normalizedRequest.contains(it) }
            ) {
                try {
                    "\n\n" + listFilesTool.execute(mapOf("path" to ".github/workflows"))
                } catch (error: Exception) {
                    "\n\nWorkflow folder inspection failed: ${error.message ?: error.javaClass.simpleName}"
                }
            } else {
                ""
            }

            val repositorySnapshot = compactBaseText(repositoryRoot + workflowContext, 50_000)
            basePrompt = buildBasePrompt(repoContext, repositorySnapshot, request)
            contextManager = AgentContextManager(basePrompt)
            iterations = 0
            accessCorrectionAttempts = 0
            completionAuditRequested = false
            completionCorrectionAttempts = 0
            noToolActionCorrectionAttempts = 0
            forceToolNextTurn = requireToolOnStart
            controlInstruction = "Start the task now. Use repository tools immediately when repository evidence or edits are required."
        } else {
            send(
                AgentStatus(
                    "Resuming saved agent session from iteration ${resumeState.iterations + 1} without re-reading completed work..."
                )
            )
            basePrompt = resumeState.basePrompt
            contextManager = AgentContextManager(basePrompt, resumeState.contextSnapshot)
            iterations = resumeState.iterations
            accessCorrectionAttempts = resumeState.accessCorrectionAttempts
            completionAuditRequested = resumeState.completionAuditRequested
            completionCorrectionAttempts = resumeState.completionCorrectionAttempts
            noToolActionCorrectionAttempts = resumeState.noToolActionCorrectionAttempts
            forceToolNextTurn = resumeState.forceToolNextTurn
            controlInstruction = resumeState.controlInstruction.ifBlank {
                "Resume the ORIGINAL task from the saved working memory. Do not repeat completed repository reads or edits unless verification requires it."
            }
        }

        val toolDefinitions = tools.map { tool ->
            AgentFunctionDefinition(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema
            )
        }

        val maxIterations = 48
        val maxToolsPerIteration = 6
        val maxProviderResumeAttempts = 2
        val toolRegex = "<tool name=\"([a-zA-Z][a-zA-Z0-9_]*)\">(.*?)</tool>"
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val legacyMapAdapter = moshi.adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )

        var providerResumeAttempts = 0

        fun checkpoint() {
            onCheckpoint(
                AgentResumeState(
                    basePrompt = basePrompt,
                    originalRequest = request,
                    repoContext = repoContext,
                    contextSnapshot = contextManager.snapshot(),
                    iterations = iterations,
                    accessCorrectionAttempts = accessCorrectionAttempts,
                    completionAuditRequested = completionAuditRequested,
                    completionCorrectionAttempts = completionCorrectionAttempts,
                    noToolActionCorrectionAttempts = noToolActionCorrectionAttempts,
                    forceToolNextTurn = forceToolNextTurn,
                    requireToolOnStart = requireToolOnStart,
                    controlInstruction = controlInstruction
                )
            )
        }

        checkpoint()

        while (iterations < maxIterations) {
            send(AgentStatus("Thinking deeply... (Iteration ${iterations + 1})"))
            send(AgentStatus("Opening streamed AI response...", transient = true))
            checkpoint()

            val modelResponse = try {
                aiClient.analyze(
                    prompt = contextManager.buildPrompt(controlInstruction),
                    attachments = if (iterations == 0 && resumeState == null) attachments else emptyList(),
                    toolDefinitions = toolDefinitions,
                    requireTool = forceToolNextTurn,
                    onStreamProgress = { progress ->
                        trySend(
                            AgentStatus(
                                message = "Streaming AI response... ${progress.chunks} chunks received",
                                transient = true
                            )
                        )
                    }
                )
            } catch (error: AIProviderException) {
                if (error.retryable && providerResumeAttempts < maxProviderResumeAttempts) {
                    providerResumeAttempts++
                    val codeText = error.statusCode?.let { "HTTP $it" } ?: "connection error"
                    controlInstruction =
                        "The provider connection was interrupted ($codeText). Resume the SAME iteration from the saved working memory. " +
                            "Do not repeat completed work. Use tools only for work that is still needed."
                    contextManager.rememberInstruction(controlInstruction)
                    checkpoint()
                    send(
                        AgentStatus(
                            "Provider interrupted ($codeText). Resuming iteration ${iterations + 1} from checkpoint, attempt $providerResumeAttempts/$maxProviderResumeAttempts..."
                        )
                    )
                    delay(if (providerResumeAttempts == 1) 5_000L else 12_000L)
                    continue
                }
                checkpoint()
                throw error
            }

            providerResumeAttempts = 0
            forceToolNextTurn = false

            if (modelResponse.streamed) {
                send(
                    AgentStatus(
                        "Stream received successfully (${modelResponse.streamChunks} chunks).",
                        transient = true
                    )
                )
            }

            val responseText = modelResponse.text
            contextManager.rememberAssistantText(responseText)

            val nativeCalls = modelResponse.toolCalls
            val legacyCalls = if (nativeCalls.isEmpty()) {
                toolRegex.findAll(responseText).mapNotNull { match ->
                    val name = match.groupValues[1]
                    val argsJson = match.groupValues[2]
                    val args = runCatching { legacyMapAdapter.fromJson(argsJson) }
                        .getOrNull()
                        .orEmpty()
                    PendingToolInvocation(name, args)
                }.toList()
            } else {
                emptyList()
            }

            val requestedCalls = if (nativeCalls.isNotEmpty()) {
                nativeCalls.map { call -> PendingToolInvocation(call.name, call.arguments) }
            } else {
                legacyCalls
            }

            if (requestedCalls.isNotEmpty()) {
                val batch = requestedCalls.take(maxToolsPerIteration)
                val mechanism = if (nativeCalls.isNotEmpty()) {
                    "native ${modelResponse.apiFormat.name}"
                } else {
                    "legacy XML"
                }
                send(
                    AgentStatus(
                        "Executing ${batch.size} $mechanism tool${if (batch.size == 1) "" else "s"} in iteration ${iterations + 1}..."
                    )
                )

                for ((index, invocation) in batch.withIndex()) {
                    send(AgentStatus("Tool ${index + 1}/${batch.size}: ${invocation.name}..."))
                    val tool = tools.find { it.name == invocation.name }
                    if (tool == null) {
                        val result = "Tool Error: Tool ${invocation.name} was not found."
                        contextManager.rememberToolResult(
                            invocation.name,
                            invocation.arguments,
                            result
                        )
                        continue
                    }

                    val result = try {
                        val args = invocation.arguments.mapValues { (_, value) -> argumentToString(value) }
                        tool.execute(args)
                    } catch (error: Exception) {
                        "Tool Error: ${error.message ?: error.javaClass.simpleName}"
                    }

                    contextManager.rememberToolResult(
                        toolName = invocation.name,
                        arguments = invocation.arguments,
                        result = result
                    )
                }

                if (requestedCalls.size > maxToolsPerIteration) {
                    contextManager.rememberInstruction(
                        "The model requested ${requestedCalls.size} tools, but only the first $maxToolsPerIteration were executed. Request remaining tools again if still needed."
                    )
                }

                iterations++
                completionAuditRequested = false
                completionCorrectionAttempts = 0
                noToolActionCorrectionAttempts = 0
                controlInstruction =
                    "Continue the ORIGINAL task now using the compact working memory. Do not repeat completed work. " +
                        "Call additional tools immediately if more work remains."
                checkpoint()
                continue
            }

            val accessWasVerified = !basePrompt.contains("failed", ignoreCase = true) &&
                !basePrompt.contains("PAT is missing", ignoreCase = true) &&
                !basePrompt.contains("not configured", ignoreCase = true)
            val falseAccessDenial = listOf(
                "can't access that repository",
                "cannot access that repository",
                "can't access the repository",
                "cannot access the repository",
                "don't have access to the repository",
                "do not have access to the repository",
                "tidak bisa mengakses repository",
                "tidak dapat mengakses repository"
            ).any { responseText.contains(it, ignoreCase = true) }

            if (accessWasVerified && falseAccessDenial && accessCorrectionAttempts < 3) {
                accessCorrectionAttempts++
                iterations++
                forceToolNextTurn = requireToolOnStart
                controlInstruction =
                    "The previous response incorrectly claimed repository access was unavailable. Repository access is already verified. " +
                        "Continue now and CALL repository tools directly when needed."
                contextManager.rememberInstruction(controlInstruction)
                checkpoint()
                send(AgentStatus("Correcting repository access response..."))
                continue
            }

            val hasCompletionMarker = responseText.contains("<task_complete>true</task_complete>")

            if (
                requireToolOnStart &&
                !hasCompletionMarker &&
                looksLikeUnfinishedNarration(responseText) &&
                noToolActionCorrectionAttempts < 4
            ) {
                noToolActionCorrectionAttempts++
                iterations++
                forceToolNextTurn = true
                controlInstruction =
                    "The previous response only described future work and did not call a tool. Do not narrate the next action. " +
                        "CALL one or more repository tools NOW. Native function/tool calling is preferred."
                contextManager.rememberInstruction(controlInstruction)
                checkpoint()
                send(AgentStatus("Forcing repository tool execution..."))
                continue
            }

            if (!completionAuditRequested) {
                completionAuditRequested = true
                iterations++
                controlInstruction =
                    "COMPLETION AUDIT: Re-check the ORIGINAL user request requirement by requirement against working memory and staged changes. " +
                        "If anything is missing, uncertain or incomplete, CALL repository tools immediately and continue. " +
                        "Only if everything is genuinely complete may the final answer end with <task_complete>true</task_complete>."
                contextManager.rememberInstruction(controlInstruction)
                checkpoint()
                send(AgentStatus("Auditing completion against the original request..."))
                continue
            }

            if (!hasCompletionMarker && completionCorrectionAttempts < 3) {
                completionCorrectionAttempts++
                iterations++
                forceToolNextTurn = requireToolOnStart
                controlInstruction =
                    "Completion was not confirmed. If work remains, CALL tools now. If the ORIGINAL request is fully complete, " +
                        "provide the final answer and end exactly with <task_complete>true</task_complete>."
                contextManager.rememberInstruction(controlInstruction)
                checkpoint()
                send(AgentStatus("Completion audit needs another pass..."))
                continue
            }

            val cleanedResponse = responseText
                .replace("<task_complete>true</task_complete>", "")
                .trim()
            send(AgentStatus("Ready for review.", cleanedResponse))
            return@channelFlow
        }

        checkpoint()
        send(
            AgentStatus(
                "Reached the 48-iteration safety limit. Successfully staged files were preserved in Changes and the session checkpoint can be resumed."
            )
        )
    }

    private fun buildBasePrompt(
        repoContext: String,
        repositorySnapshot: String,
        request: String
    ): String = """
        You are a senior autonomous software-engineering AI agent working at maximum effort.
        You have access to repository: $repoContext through real tools provided by this Android application.
        The application has already inspected the repository and returned this real snapshot:

        --- REPOSITORY SNAPSHOT ---
        $repositorySnapshot
        --- END REPOSITORY SNAPSHOT ---

        CORE EXECUTION RULES:
        - Work on ANY repository task the user asks for: frontend, backend, Android, APIs, infrastructure, configuration, CI, refactors, debugging, migrations, tests, documentation, or other software work.
        - Treat the user's complete request as a checklist. Keep working until every explicit requirement is completed, intentionally excluded with a clear reason, or blocked by a real external limitation.
        - Do not stop after one file or one successful change when the task requires more work.
        - Prefer evidence over assumptions. Inspect relevant files and dependencies before changing them.
        - After editing important files, re-read staged versions when useful and check imports, references, routes, configuration, syntax relationships, and cross-file consistency.
        - readFile returns the latest staged Changes version when that file has already been edited.
        - Never claim a file was changed unless updateFile successfully staged it.
        - Never commit or push. The user reviews staged Changes and performs Commit / Push separately.

        CONTEXT MANAGEMENT RULES:
        - Working memory is bounded for reliability. Older redundant tool output may be compacted.
        - The ORIGINAL user request is always authoritative.
        - A latest read for the same file replaces its older read in memory.
        - If exact omitted content is needed, re-read only that file or relevant line range instead of re-reading the entire repository.
        - Do not repeat already completed reads/edits merely because older verbose output was compacted.

        TOOL EXECUTION RULES:
        - Native function/tool calling is the PRIMARY mechanism. CALL tools directly instead of saying you will call them.
        - Never answer only "I will read the files", "Saya akan membaca repo", "I will continue", or similar future-tense narration.
        - You may request up to 6 tool calls in one iteration.
        - Batch independent listFiles/readFile/searchCode calls when useful.
        - For large edits, request at most 1-2 updateFile calls per iteration so the provider can stream the response reliably; continue remaining large files in the next iteration.
        - Small updateFile payloads may still be batched when they are clearly safe.
        - For updateFile, always provide the COMPLETE final file content, never only a patch fragment.

        LEGACY TOOL FALLBACK:
        - Only when native tool calling is unavailable, tools may be requested as:
          <tool name="toolName">{"argName":"argValue"}</tool>

        REPOSITORY ACCESS RULES:
        - Repository tools are real and available through this Android application.
        - Never claim repository access is unavailable when the snapshot contains repository entries.
        - Use listFiles, searchCode and readFile whenever more evidence is needed.
        - Do not invent file names or repository contents.

        NATURAL-LANGUAGE RULES:
        - Understand Indonesian and English, including informal spelling, short instructions and typos.
        - Infer user intent from context instead of searching only exact words.
        - Ask one short clarification only when materially different interpretations remain after inspecting the repository.
        - Reply in the same language as the user unless requested otherwise.

        Available tools:
        ${tools.joinToString("\n") { "- ${it.name}: ${it.description}" }}

        COMPLETION RULE:
        - Before finishing, perform a completion audit against the ORIGINAL user request.
        - If anything is incomplete, CALL tools and continue working.
        - Only when genuinely complete should the final answer contain no tool calls/tags and end with:
          <task_complete>true</task_complete>

        ORIGINAL USER REQUEST:
        $request
    """.trimIndent()

    private fun compactBaseText(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val marker = "\n\n[...repository snapshot compacted; use listFiles/readFile/searchCode for omitted entries...]\n\n"
        val available = (limit - marker.length).coerceAtLeast(4_000)
        val head = (available * 3) / 4
        return text.take(head) + marker + text.takeLast(available - head)
    }

    private fun looksLikeUnfinishedNarration(text: String): Boolean {
        if (text.isBlank()) return true
        val normalized = text.lowercase()
        return listOf(
            "saya akan",
            "akan membaca",
            "akan langsung",
            "saya lanjut",
            "saya akan lanjut",
            "belum selesai",
            "belum_selesai",
            "saya periksa dulu",
            "saya baca dulu",
            "i will",
            "i'll",
            "let me",
            "going to",
            "i need to read",
            "i need to inspect",
            "continue working",
            "will continue"
        ).any { normalized.contains(it) }
    }

    private fun argumentToString(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Int, is Long, is Short, is Byte -> value.toString()
        is Float -> if (value % 1f == 0f) value.toLong().toString() else value.toString()
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is Boolean -> value.toString()
        else -> value.toString()
    }
}

private data class PendingToolInvocation(
    val name: String,
    val arguments: Map<String, Any?>
)

data class AgentResumeState(
    val basePrompt: String,
    val originalRequest: String,
    val repoContext: String,
    val contextSnapshot: AgentContextSnapshot,
    val iterations: Int,
    val accessCorrectionAttempts: Int,
    val completionAuditRequested: Boolean,
    val completionCorrectionAttempts: Int,
    val noToolActionCorrectionAttempts: Int,
    val forceToolNextTurn: Boolean,
    val requireToolOnStart: Boolean,
    val controlInstruction: String
)

data class AgentStatus(
    val message: String,
    val finalResponse: String? = null,
    val transient: Boolean = false
)
