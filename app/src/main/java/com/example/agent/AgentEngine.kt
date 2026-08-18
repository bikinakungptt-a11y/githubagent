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
        attachments: List<AgentAttachment> = emptyList()
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

            MULTI-TOOL RULES:
            - You may request up to 6 tool calls in a single response/iteration.
            - For independent repository inspection, batch several listFiles/readFile/searchCode calls together instead of unnecessarily waiting for a new AI turn after every single file.
            - You may stage several independent updateFile calls in one iteration when their complete contents are known.
            - Tool calls are executed in the order you output them. This means you can updateFile and then readFile the same path later in the same batch to verify the staged result.
            - If more than 6 tools are needed, request the next batch in the following iteration.
            - For updateFile, always provide the COMPLETE final content of that file, not a patch fragment.

            REPOSITORY ACCESS RULES:
            - The repository tools below are real application tools and are available now.
            - Never say that you cannot access the repository when the snapshot above contains repository entries.
            - Do not confuse these application tools with tools from another chat product.
            - Use listFiles, searchCode, and readFile whenever more evidence is needed.
            - Do not invent file names or repository contents.

            NATURAL-LANGUAGE RULES:
            - Understand Indonesian and English, including informal spelling, short instructions, typos, and non-technical wording.
            - Infer the user's goal from context instead of searching only the exact words they typed.
            - Translate user intent into likely technical concepts, file names, APIs, classes, dependencies, and related search terms.
            - Never conclude that a feature is absent after only one literal keyword search.
            - Ask one short clarification only when two materially different interpretations remain after inspecting the repository and guessing would materially risk the user's project.
            - Reply in the same language as the user unless they request otherwise.

            TOOL FORMAT:
            Output each tool call exactly like this, with one complete JSON object per tag:
            <tool name="toolName">{"argName":"argValue"}</tool>

            You may output several tool tags in the same response, up to 6.

            Available tools:
            ${tools.joinToString("\n") { "- ${it.name}: ${it.description}" }}

            COMPLETION RULE:
            - Before finishing any task, perform a completion audit against the ORIGINAL user request.
            - If anything is incomplete, use tools and continue working.
            - Only when the task is genuinely complete should your final answer contain no tool tags and end with this exact marker:
              <task_complete>true</task_complete>
            - Do not use that marker merely because one subtask succeeded.

            User request: $request
        """.trimIndent()

        val maxIterations = 48
        val maxToolsPerIteration = 6
        val toolRegex = "<tool name=\"([a-zA-Z][a-zA-Z0-9_]*)\">(.*?)</tool>"
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val mapAdapter = moshi.adapter<Map<String, String>>(
            Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        )

        var iterations = 0
        var accessCorrectionAttempts = 0
        var completionAuditRequested = false
        var completionCorrectionAttempts = 0

        while (iterations < maxIterations) {
            emit(AgentStatus("Thinking deeply... (Iteration ${iterations + 1})"))
            val response = aiClient.analyze(
                currentPrompt,
                if (iterations == 0) attachments else emptyList()
            )

            val allToolMatches = toolRegex.findAll(response).toList()
            if (allToolMatches.isNotEmpty()) {
                val batch = allToolMatches.take(maxToolsPerIteration)
                emit(
                    AgentStatus(
                        "Executing ${batch.size} tool${if (batch.size == 1) "" else "s"} in iteration ${iterations + 1}..."
                    )
                )

                val resultBlock = buildString {
                    appendLine("Assistant requested tools:")
                    appendLine(response)
                    appendLine()
                    appendLine("--- EXECUTED TOOL RESULTS ---")

                    batch.forEachIndexed { index, match ->
                        val toolName = match.groupValues[1]
                        val argsJson = match.groupValues[2]
                        emit(AgentStatus("Tool ${index + 1}/${batch.size}: $toolName..."))

                        val tool = tools.find { it.name == toolName }
                        if (tool == null) {
                            appendLine("Tool Error ($toolName): Tool not found.")
                            appendLine()
                            return@forEachIndexed
                        }

                        try {
                            val args = mapAdapter.fromJson(argsJson) ?: emptyMap()
                            val result = tool.execute(args)
                            appendLine("Tool Result ($toolName):")
                            appendLine(result)
                            appendLine()
                        } catch (error: Exception) {
                            appendLine("Tool Error ($toolName): ${error.message ?: error.javaClass.simpleName}")
                            appendLine()
                        }
                    }

                    if (allToolMatches.size > maxToolsPerIteration) {
                        appendLine(
                            "BATCH LIMIT: The response requested ${allToolMatches.size} tools, but only the first $maxToolsPerIteration were executed. Request any remaining tools again in the next iteration."
                        )
                    }
                    appendLine("--- END TOOL RESULTS ---")
                }

                currentPrompt += "\n\n$resultBlock\n"
                iterations++
                completionAuditRequested = false
                completionCorrectionAttempts = 0
                continue
            }

            val accessWasVerified = !repositorySnapshot.contains("failed", ignoreCase = true) &&
                !repositorySnapshot.contains("PAT is missing", ignoreCase = true) &&
                !repositorySnapshot.contains("not configured", ignoreCase = true)
            val falseAccessDenial = listOf(
                "can\'t access that repository",
                "cannot access that repository",
                "can\'t access the repository",
                "cannot access the repository",
                "don\'t have access to the repository",
                "do not have access to the repository",
                "tidak bisa mengakses repository",
                "tidak dapat mengakses repository"
            ).any { response.contains(it, ignoreCase = true) }

            if (accessWasVerified && falseAccessDenial && accessCorrectionAttempts < 3) {
                accessCorrectionAttempts++
                iterations++
                currentPrompt += """

                    Assistant response rejected: it incorrectly claimed repository access was unavailable.
                    The Android application already accessed $repoContext successfully, as proven by the repository snapshot.
                    Continue the task now. Use the available application tools when more evidence or edits are needed.
                    Do not repeat the access-denial statement.
                """.trimIndent()
                emit(AgentStatus("Correcting repository access response..."))
                continue
            }

            val hasCompletionMarker = response.contains("<task_complete>true</task_complete>")

            if (!completionAuditRequested) {
                completionAuditRequested = true
                iterations++
                currentPrompt += """

                    Assistant draft response:
                    $response

                    COMPLETION AUDIT REQUIRED NOW:
                    Do not finalize yet. Re-check the ORIGINAL user request requirement by requirement.
                    Compare it with the repository evidence and all successful Tool Results above.
                    Check whether every requested file, feature, fix, configuration change, integration, cleanup, and verification step is actually complete.
                    If any item is missing, uncertain, inconsistent, or only partially implemented, use repository tools now and continue the work.
                    If edits were made, inspect staged versions where useful and repair problems before finalizing.
                    Only if everything is genuinely complete may you return the final answer ending with <task_complete>true</task_complete>.
                """.trimIndent()
                emit(AgentStatus("Auditing completion against the original request..."))
                continue
            }

            if (!hasCompletionMarker && completionCorrectionAttempts < 3) {
                completionCorrectionAttempts++
                iterations++
                currentPrompt += """

                    Completion response rejected because it did not confirm the required completion audit.
                    If work remains, continue with tools. If the original request is fully complete, provide the final answer and end it exactly with:
                    <task_complete>true</task_complete>
                """.trimIndent()
                emit(AgentStatus("Completion audit needs another pass..."))
                continue
            }

            val cleanedResponse = response
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
}

data class AgentStatus(
    val message: String,
    val finalResponse: String? = null
)
