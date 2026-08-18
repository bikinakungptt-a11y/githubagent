package com.example.agent

import com.example.agent.tools.AgentTool
import com.example.domain.model.AIProviderConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AgentEngine(
    private val config: AIProviderConfig,
    private val tools: List<AgentTool>,
    private val aiClient: AIClient
) {
    fun processRequest(
        request: String,
        repoContext: String,
        attachments: List<AgentAttachment> = emptyList(),
        requireToolOnStart: Boolean = false
    ): Flow<AgentStatus> = flow {
        emit(AgentStatus("Analyzing Request..."))

        val listFilesTool = tools.find { it.name == "listFiles" }
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
        val repositorySnapshot = repositoryRoot + workflowContext

        val toolDefinitions = tools.map { tool ->
            AgentFunctionDefinition(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema
            )
        }

        var currentPrompt = """
            You are a senior autonomous software-engineering AI agent working at maximum effort.
            You have access to repository: $repoContext through real tools provided by this Android application.
            The application has already inspected the repository and returned this real result:

            --- REPOSITORY SNAPSHOT ---
            $repositorySnapshot
            --- END REPOSITORY SNAPSHOT ---

            CORE EXECUTION RULES:
            - Work on ANY repository task the user asks for: frontend, backend, Android, APIs, infrastructure, configuration, CI, refactors, debugging, migrations, tests, documentation, or other software work.
            - Treat the user's complete request as a checklist of requirements. Keep working until every explicit requirement is completed, intentionally excluded with a clear reason, or blocked by a real external limitation.
            - Do not stop after one file or one successful change when the task requires more work.
            - Prefer evidence over assumptions. Inspect relevant files and dependencies before changing them.
            - After editing important files, re-read staged versions and check imports, references, routes, configuration, syntax relationships, and cross-file consistency.
            - readFile returns the staged Changes version when that file has already been edited, so use it to inspect and improve your own latest work.
            - Never claim a file was changed unless updateFile successfully staged it.
            - Never commit or push. The user reviews staged Changes and performs Commit / Push separately.

            TOOL EXECUTION RULES:
            - Native function/tool calling is the PRIMARY tool mechanism. When the API exposes repository functions, CALL them directly instead of writing that you will call them.
            - Never answer only "I will read the files", "Saya akan membaca repo", "I will continue", or similar future-tense narration. Actually invoke the required tool in that same turn.
            - You may request up to 6 tool calls in one iteration.
            - For independent inspection, batch several listFiles/readFile/searchCode calls together.
            - You may stage several independent updateFile calls in one iteration when their complete contents are known.
            - Tool calls execute in the order requested. You can updateFile and then readFile the same path to verify the staged result.
            - For updateFile, always provide the COMPLETE final file content, never only a patch fragment.

            LEGACY TOOL FALLBACK:
            - Only when native tool calling is unavailable, you may request tools with this exact text format:
              <tool name="toolName">{"argName":"argValue"}</tool>
            - Several legacy <tool> tags may be returned in one response, up to 6.

            REPOSITORY ACCESS RULES:
            - The repository tools are real and available through this Android application.
            - Never say repository access is unavailable when the snapshot above contains repository entries.
            - Use listFiles, searchCode, and readFile whenever more evidence is needed.
            - Do not invent file names or repository contents.

            NATURAL-LANGUAGE RULES:
            - Understand Indonesian and English, including informal spelling, short instructions, typos, and non-technical wording.
            - Infer the user's goal from context instead of searching only the exact words they typed.
            - Translate user intent into likely technical concepts, file names, APIs, classes, dependencies, and related search terms.
            - Never conclude that a feature is absent after only one literal keyword search.
            - Ask one short clarification only when two materially different interpretations remain after inspecting the repository and guessing would materially risk the user's project.
            - Reply in the same language as the user unless they request otherwise.

            Available tools:
            ${tools.joinToString("\n") { "- ${it.name}: ${it.description}" }}

            COMPLETION RULE:
            - Before finishing any task, perform a completion audit against the ORIGINAL user request.
            - If anything is incomplete, CALL tools and continue working. Do not merely say that you will continue.
            - Only when the task is genuinely complete should the final answer contain no tool calls/tags and end with:
              <task_complete>true</task_complete>

            User request: $request
        """.trimIndent()

        val maxIterations = 48
        val maxToolsPerIteration = 6
        val toolRegex = "<tool name=\"([a-zA-Z][a-zA-Z0-9_]*)\">(.*?)</tool>"
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val legacyMapAdapter = moshi.adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )

        var iterations = 0
        var accessCorrectionAttempts = 0
        var completionAuditRequested = false
        var completionCorrectionAttempts = 0
        var noToolActionCorrectionAttempts = 0
        var forceToolNextTurn = requireToolOnStart

        while (iterations < maxIterations) {
            emit(AgentStatus("Thinking deeply... (Iteration ${iterations + 1})"))

            val modelResponse = aiClient.analyze(
                prompt = currentPrompt,
                attachments = if (iterations == 0) attachments else emptyList(),
                toolDefinitions = toolDefinitions,
                requireTool = forceToolNextTurn
            )
            forceToolNextTurn = false

            val responseText = modelResponse.text
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
                emit(
                    AgentStatus(
                        "Executing ${batch.size} $mechanism tool${if (batch.size == 1) "" else "s"} in iteration ${iterations + 1}..."
                    )
                )

                val resultBlock = StringBuilder()
                if (responseText.isNotBlank()) {
                    resultBlock.appendLine("Assistant text:")
                    resultBlock.appendLine(responseText)
                    resultBlock.appendLine()
                }
                resultBlock.appendLine("--- EXECUTED TOOL RESULTS ($mechanism) ---")

                for ((index, invocation) in batch.withIndex()) {
                    emit(AgentStatus("Tool ${index + 1}/${batch.size}: ${invocation.name}..."))
                    val tool = tools.find { it.name == invocation.name }
                    if (tool == null) {
                        resultBlock.appendLine("Tool Error (${invocation.name}): Tool not found.")
                        resultBlock.appendLine()
                        continue
                    }

                    try {
                        val args = invocation.arguments.mapValues { (_, value) -> argumentToString(value) }
                        val result = tool.execute(args)
                        resultBlock.appendLine("Tool Result (${invocation.name}):")
                        resultBlock.appendLine(result)
                        resultBlock.appendLine()
                    } catch (error: Exception) {
                        resultBlock.appendLine(
                            "Tool Error (${invocation.name}): ${error.message ?: error.javaClass.simpleName}"
                        )
                        resultBlock.appendLine()
                    }
                }

                if (requestedCalls.size > maxToolsPerIteration) {
                    resultBlock.appendLine(
                        "BATCH LIMIT: ${requestedCalls.size} tools were requested but only the first $maxToolsPerIteration were executed. Request the remaining tools again next iteration."
                    )
                }
                resultBlock.appendLine("--- END TOOL RESULTS ---")
                resultBlock.appendLine(
                    "Continue the ORIGINAL task now. Use additional tools immediately if more work remains."
                )

                currentPrompt += "\n\n$resultBlock\n"
                iterations++
                completionAuditRequested = false
                completionCorrectionAttempts = 0
                noToolActionCorrectionAttempts = 0
                continue
            }

            val accessWasVerified = !repositorySnapshot.contains("failed", ignoreCase = true) &&
                !repositorySnapshot.contains("PAT is missing", ignoreCase = true) &&
                !repositorySnapshot.contains("not configured", ignoreCase = true)
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
                currentPrompt += """

                    Assistant response rejected: it incorrectly claimed repository access was unavailable.
                    The application already accessed $repoContext successfully. Continue now and CALL the available repository tools directly.
                """.trimIndent()
                emit(AgentStatus("Correcting repository access response..."))
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
                currentPrompt += """

                    Assistant response rejected because it described future repository work but did not actually call a tool:
                    $responseText

                    Do not narrate the next action. CALL one or more repository tools NOW. Native function/tool calling is preferred. If native tools are unavailable, use the legacy <tool> format.
                """.trimIndent()
                emit(AgentStatus("Forcing repository tool execution..."))
                continue
            }

            if (!completionAuditRequested) {
                completionAuditRequested = true
                iterations++
                currentPrompt += """

                    Assistant draft response:
                    $responseText

                    COMPLETION AUDIT REQUIRED NOW:
                    Re-check the ORIGINAL user request requirement by requirement.
                    If anything is missing, uncertain, inconsistent, or only partially implemented, CALL repository tools immediately and continue working.
                    Do not answer only that you will continue.
                    Only if everything is genuinely complete may you return the final answer ending with <task_complete>true</task_complete>.
                """.trimIndent()
                emit(AgentStatus("Auditing completion against the original request..."))
                continue
            }

            if (!hasCompletionMarker && completionCorrectionAttempts < 3) {
                completionCorrectionAttempts++
                iterations++
                forceToolNextTurn = requireToolOnStart
                currentPrompt += """

                    Completion response rejected because the task was not confirmed complete.
                    If work remains, CALL repository tools now. If the original request is fully complete, provide the final answer and end exactly with:
                    <task_complete>true</task_complete>
                """.trimIndent()
                emit(AgentStatus("Completion audit needs another pass..."))
                continue
            }

            val cleanedResponse = responseText
                .replace("<task_complete>true</task_complete>", "")
                .trim()
            emit(AgentStatus("Ready for review.", cleanedResponse))
            break
        }

        if (iterations >= maxIterations) {
            emit(
                AgentStatus(
                    "Reached the 48-iteration safety limit. Any successfully staged files were preserved in Changes so work can continue in a new instruction."
                )
            )
        }
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

data class AgentStatus(
    val message: String,
    val finalResponse: String? = null
)
