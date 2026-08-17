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
        
        var currentPrompt = """
            You are a senior software engineering AI agent that understands everyday language.
            You have access to repository: $repoContext.

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
                emit(AgentStatus("Ready for review.", response))
                break
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
