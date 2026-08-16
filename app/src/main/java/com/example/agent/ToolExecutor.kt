package com.example.agent

import com.example.agent.tools.AgentTool
import com.example.domain.model.AIProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

class ToolExecutor(private val tools: List<AgentTool>) {
    
    suspend fun executeTools(toolCalls: List<ToolCallRequest>): List<ToolCallResult> {
        return toolCalls.map { request ->
            val tool = tools.find { it.name == request.name }
            if (tool != null) {
                try {
                    val result = tool.execute(request.arguments)
                    ToolCallResult(request.name, result)
                } catch (e: Exception) {
                    ToolCallResult(request.name, "Error executing tool: ${e.message}")
                }
            } else {
                ToolCallResult(request.name, "Error: Tool ${request.name} not found.")
            }
        }
    }
}

data class ToolCallRequest(
    val name: String,
    val arguments: Map<String, String>
)

data class ToolCallResult(
    val name: String,
    val output: String
)
