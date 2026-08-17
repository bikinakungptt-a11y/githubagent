package com.example.agent

import com.example.agent.tools.AgentTool
import com.example.domain.model.AIProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AgentEngine(
    private val config: AIProviderConfig,
    private val tools: List<AgentTool>,
    private val aiClient: AIClient
) {
    fun processRequest(request: String, repoContext: String, attachments: List<AgentAttachment> = emptyList()): Flow<AgentStatus> = flow {
        emit(AgentStatus("Analyzing Request..."))

        // The user explicitly approved sending selected repository context to the configured AI provider.
        // Read repository structure before asking the model so access does not depend on model compliance.
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
            You are a senior software engineering AI agent that understands everyday language.
            You have access to repository: $repoContext through tools provided by this Android application.
            The application has already inspected the repository and returned this real result:

            --- REPOSITORY SNAPSHOT ---
            $repositorySnapshot
            --- END REPOSITORY SNAPSHOT ---

            REPOSITORY ACCESS RULES:
            - The repository tools listed below are real application tools and are available to you now.
            - Never say that you cannot access the repository when the snapshot above contains repository entries.
            - Do not confuse these application tools with tools from another chat product.
            - Use listFiles, searchCode, and readFile whenever more evidence is needed.
            - Do not invent file names or repository contents.

            NATURAL-LANGUAGE RULES:
            - Understand Indonesian and English, including short sentences, informal spelling, typos, and non-technical wording.
            - Infer the user's goal from context instead of searching only the exact words they typed.
            - Translate the user's intent into likely technical concepts, file names, APIs, classes, and several related search terms.
            - Never conclude that a feature is absent after only one literal keyword search.
            - For vague or simple questions, first use listFiles on the repository root, inspect likely folders, then search related technical terms and read relevant files.
            - Base the final answer on actual repository contents. Clearly say what files were inspected.
            - Ask one short clarification only when two materially different interpretations remain after inspecting the repository.
            - Reply in the same language as the user, using simple wording unless technical detail is necessary.

            You can use tools by outputting EXACTLY this format:
            <tool name="toolName">{"argName": "argValue"}</tool>
            
            Available tools:
            ${tools.joinToString("\n") { "- ${it.name}: ${it.description}" }}
            
            When you are done, output your final response and DO NOT output any <tool> tags.
            
            User request: $request
        """.trimIndent()
        
        var iterations = 0
        var accessCorrectionAttempts = 0
        while (iterations < 8) {
            emit(AgentStatus("Thinking deeply... (Iteration ${iterations + 1})"))
            val response = aiClient.analyze(currentPrompt, if (iterations == 0) attachments else emptyList())
            
            val toolMatch = "<tool name=\"([a-zA-Z]+)\">(.*?)</tool>".toRegex(RegexOption.DOT_MATCHES_ALL).find(response)
            if (toolMatch != null) {
                val toolName = toolMatch.groupValues[1]
                val argsJson = toolMatch.groupValues[2]
                
                emit(AgentStatus("Executing tool: $toolName..."))
                
                val tool = tools.find { it.name == toolName }
                if (tool != null) {
                    try {
                        val moshi = com.squareup.moshi.Moshi.Builder()
                            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                            .build()
                        val mapAdapter = moshi.adapter<Map<String, String>>(
                            com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
                        )
                        val args = mapAdapter.fromJson(argsJson) ?: emptyMap()
                        val result = tool.execute(args)
                        
                        currentPrompt += "\n\nAssistant: $response\n\nTool Result ($toolName): $result\n"
                    } catch (e: Exception) {
                        currentPrompt += "\n\nAssistant: $response\n\nTool Error ($toolName): ${e.message}\n"
                    }
                } else {
                    currentPrompt += "\n\nAssistant: $response\n\nTool Error: Tool $toolName not found.\n"
                }
                iterations++
            } else {
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

                if (accessWasVerified && falseAccessDenial && accessCorrectionAttempts < 2) {
                    accessCorrectionAttempts++
                    iterations++
                    currentPrompt += """

                        Assistant response rejected: it incorrectly claimed repository access was unavailable.
                        The Android application already accessed $repoContext successfully, as proven by the
                        repository snapshot above. Continue the task now. Use the available application tools
                        in the exact <tool> format when you need a directory, search result, or file content.
                        Do not repeat the access-denial statement.
                    """.trimIndent()
                    emit(AgentStatus("Correcting repository access response..."))
                } else {
                    emit(AgentStatus("Ready for review.", response))
                    break
                }
            }
        }
        if (iterations >= 8) {
            emit(AgentStatus("Finished due to iteration limit."))
        }
    }
}

data class AgentStatus(
    val message: String,
    val finalResponse: String? = null
)
