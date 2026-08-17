package com.example.agent.tools

import com.example.agent.patch.FilePatch

abstract class AgentTool(
    val name: String,
    val description: String
) {
    abstract suspend fun execute(arguments: Map<String, String>): String
}

class ListFilesTool(
    private val gitHubService: com.example.data.github.GitHubService,
    private val githubToken: String,
    private val repositoryName: String,
    private val branch: String = "main"
) : AgentTool(
    name = "listFiles",
    description = "Lists the complete recursive file and folder tree of the selected GitHub repository. Optional argument: path filters the tree to a folder. Use this before reading or editing files."
) {
    override suspend fun execute(arguments: Map<String, String>): String {
        if (githubToken.isBlank()) return "Error: GitHub PAT is missing."
        val parts = repositoryName.split("/", limit = 2)
        if (parts.size != 2) return "Error: invalid repository name \'$repositoryName\'."
        val requestedPath = arguments["path"]?.trim()?.trim(\'/\').orEmpty()

        return try {
            val response = gitHubService.getRepositoryTree(
                authHeader = "Bearer $githubToken",
                owner = parts[0],
                repo = parts[1],
                treeSha = branch
            )
            val entries = response.tree
                .asSequence()
                .filter { entry ->
                    requestedPath.isBlank() ||
                        entry.path == requestedPath ||
                        entry.path.startsWith("$requestedPath/")
                }
                .sortedWith(compareBy<com.example.data.github.GitHubTreeEntry> { it.type != "tree" }.thenBy { it.path })
                .toList()

            if (entries.isEmpty()) {
                "No files found at \'${requestedPath.ifBlank { "/" }}\' on branch \'$branch\'."
            } else {
                buildString {
                    appendLine("Complete repository tree at ${requestedPath.ifBlank { "/" }} on branch $branch:")
                    entries.forEach { entry ->
                        val kind = if (entry.type == "tree") "dir" else "file"
                        appendLine("- [$kind] ${entry.path}")
                    }
                    if (response.truncated) {
                        appendLine("[GitHub reported that this very large tree was truncated.]")
                    }
                }.trim()
            }
        } catch (error: Exception) {
            "Error listing repository files: ${error.message ?: error.javaClass.simpleName}"
        }
    }
}
class ReadFileTool(
    private val gitHubService: com.example.data.github.GitHubService,
    private val githubToken: String,
    private val repositoryName: String,
    private val branch: String = "main"
) : AgentTool(
    name = "readFile",
    description = "Reads the real UTF-8 content of a file from the selected GitHub repository. Argument: path."
) {
    override suspend fun execute(arguments: Map<String, String>): String {
        val path = arguments["path"]?.trim().orEmpty()
        if (path.isBlank()) return "Error: path argument is required."
        if (githubToken.isBlank()) return "Error: GitHub PAT is missing."

        val parts = repositoryName.split("/", limit = 2)
        if (parts.size != 2) return "Error: invalid repository name '$repositoryName'."

        return try {
            val file = gitHubService.getRepositoryContent(
                authHeader = "Bearer $githubToken",
                owner = parts[0],
                repo = parts[1],
                path = path,
                ref = branch
            )
            if (file.type != "file") {
                "Error: '$path' is not a file."
            } else {
                val encoded = file.content?.replace("\n", "").orEmpty()
                if (encoded.isBlank()) {
                    "Error: GitHub returned no content for '$path'."
                } else {
                    val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                        .toString(Charsets.UTF_8)
                    if (decoded.length > 120_000) {
                        decoded.take(120_000) + "\n\n[Content truncated at 120,000 characters]"
                    } else {
                        decoded
                    }
                }
            }
        } catch (error: Exception) {
            "Error reading '$path' from GitHub: ${error.message ?: error.javaClass.simpleName}"
        }
    }
}

class SearchCodeTool(
    private val gitHubService: com.example.data.github.GitHubService,
    private val githubToken: String,
    private val repositoryName: String
) : AgentTool(
    name = "searchCode",
    description = "Searches real code in the selected GitHub repository. Argument: query."
) {
    override suspend fun execute(arguments: Map<String, String>): String {
        val query = arguments["query"]?.trim().orEmpty()
        if (query.isBlank()) return "Error: query argument is required."
        if (githubToken.isBlank()) return "Error: GitHub PAT is missing."

        return try {
            val result = gitHubService.searchCode(
                authHeader = "Bearer $githubToken",
                query = "$query repo:$repositoryName"
            )
            if (result.items.isEmpty()) {
                "No GitHub code matches found for '$query'."
            } else {
                buildString {
                    appendLine("Found ${result.total_count} GitHub code matches. Top results:")
                    result.items.forEach { item ->
                        appendLine("- ${item.path} (${item.html_url})")
                    }
                }.trim()
            }
        } catch (error: Exception) {
            "Error searching GitHub code: ${error.message ?: error.javaClass.simpleName}"
        }
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
