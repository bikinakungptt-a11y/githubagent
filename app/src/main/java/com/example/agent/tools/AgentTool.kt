package com.example.agent.tools

import com.example.agent.patch.FilePatch

abstract class AgentTool(
    val name: String,
    val description: String
) {
    abstract suspend fun execute(arguments: Map<String, String>): String
}

class ReadFileTool(private val githubToken: String) : AgentTool(
    name = "readFile",
    description = "Reads a file from the repository given a file path."
) {
    override suspend fun execute(arguments: Map<String, String>): String {
        val path = arguments["path"] ?: return "Error: path argument is required."
        // In a real implementation this would fetch from GitHub
        return "Simulated content of $path"
    }
}

class SearchCodeTool : AgentTool(
    name = "searchCode",
    description = "Searches the repository for a given query."
) {
    override suspend fun execute(arguments: Map<String, String>): String {
        val query = arguments["query"] ?: return "Error: query argument is required."
        return "Simulated search results for $query"
    }
}

class UpdateFileTool : AgentTool(
    name = "updateFile",
    description = "Updates the content of a file. Provide 'path' and 'modifiedContent'."
) {
    private val pendingPatches = mutableListOf<FilePatch>()

    override suspend fun execute(arguments: Map<String, String>): String {
        val path = arguments["path"] ?: return "Error: path argument is required."
        val modifiedContent = arguments["modifiedContent"] ?: return "Error: modifiedContent is required."
        
        pendingPatches.add(
            FilePatch(
                path = path,
                originalContent = null,
                modifiedContent = modifiedContent,
                explanation = "Updated by AI Agent"
            )
        )
        
        return "Successfully staged update for $path."
    }
    
    fun getPendingPatches(): List<FilePatch> = pendingPatches.toList()
}
