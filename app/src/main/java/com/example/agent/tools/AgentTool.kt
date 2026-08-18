package com.example.agent.tools

import com.example.agent.patch.FilePatch

abstract class AgentTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>
) {
    abstract suspend fun execute(arguments: Map<String, String>): String
}

private fun objectSchema(
    properties: Map<String, Map<String, Any>>,
    required: List<String> = emptyList()
): Map<String, Any> = mapOf(
    "type" to "object",
    "properties" to properties,
    "required" to required
)

private fun stringProperty(description: String): Map<String, Any> = mapOf(
    "type" to "string",
    "description" to description
)

private fun integerProperty(description: String): Map<String, Any> = mapOf(
    "type" to "integer",
    "description" to description
)

class ListFilesTool(
    private val gitHubService: com.example.data.github.GitHubService,
    private val githubToken: String,
    private val repositoryName: String,
    private val branch: String = "main"
) : AgentTool(
    name = "listFiles",
    description = "List the recursive file and folder tree of the selected GitHub repository. Optionally limit the listing to a folder path.",
    inputSchema = objectSchema(
        properties = mapOf(
            "path" to stringProperty("Optional repository folder path. Use an empty value or omit it for the repository root.")
        )
    )
) {
    override suspend fun execute(arguments: Map<String, String>): String {
        if (githubToken.isBlank()) return "Error: GitHub PAT is missing."
        val parts = repositoryName.split("/", limit = 2)
        if (parts.size != 2) return "Error: invalid repository name '$repositoryName'."
        val requestedPath = arguments["path"]?.trim()?.trim('/').orEmpty()

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
                .sortedWith(
                    compareBy<com.example.data.github.GitHubTreeEntry> { it.type != "tree" }
                        .thenBy { it.path }
                )
                .toList()

            if (entries.isEmpty()) {
                "No files found at '${requestedPath.ifBlank { "/" }}' on branch '$branch'."
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
    private val branch: String = "main",
    private val stagedContentProvider: (String) -> String? = { null }
) : AgentTool(
    name = "readFile",
    description = "Read the current UTF-8 content of a repository file. If the path has a staged edit in Changes, read that latest staged version. Use optional line numbers for focused reads.",
    inputSchema = objectSchema(
        properties = mapOf(
            "path" to stringProperty("Repository-relative file path to read."),
            "startLine" to integerProperty("Optional 1-based first line to return."),
            "endLine" to integerProperty("Optional 1-based last line to return.")
        ),
        required = listOf("path")
    )
) {
    override suspend fun execute(arguments: Map<String, String>): String {
        val path = arguments["path"]?.trim().orEmpty()
        if (path.isBlank()) return "Error: path argument is required."

        val staged = stagedContentProvider(path)
        if (staged != null) {
            return formatContent(path, staged, arguments, source = "staged Changes")
        }

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
                    formatContent(path, decoded, arguments, source = "GitHub branch $branch")
                }
            }
        } catch (error: Exception) {
            "Error reading '$path' from GitHub: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun formatContent(
        path: String,
        content: String,
        arguments: Map<String, String>,
        source: String
    ): String {
        val lines = content.lines()
        val requestedStart = arguments["startLine"]?.toIntOrNull()?.coerceAtLeast(1)
        val requestedEnd = arguments["endLine"]?.toIntOrNull()?.coerceAtLeast(1)

        if (requestedStart != null || requestedEnd != null) {
            val start = (requestedStart ?: 1).coerceAtMost(lines.size.coerceAtLeast(1))
            val end = (requestedEnd ?: lines.size).coerceIn(start, lines.size.coerceAtLeast(start))
            val selected = if (lines.isEmpty()) "" else lines.subList(start - 1, end).joinToString("\n")
            return "Source: $source\nFile: $path\nLines $start-$end of ${lines.size}\n\n$selected"
        }

        return if (content.length > 120_000) {
            "Source: $source\nFile: $path\n\n" + content.take(120_000) +
                "\n\n[Content truncated at 120,000 characters. Use startLine/endLine for focused reads.]"
        } else {
            "Source: $source\nFile: $path\n\n$content"
        }
    }
}

class SearchCodeTool(
    private val gitHubService: com.example.data.github.GitHubService,
    private val githubToken: String,
    private val repositoryName: String
) : AgentTool(
    name = "searchCode",
    description = "Search real code in the selected GitHub repository for a technical term, symbol, class, function, text, or related keyword.",
    inputSchema = objectSchema(
        properties = mapOf(
            "query" to stringProperty("Code search query. Use concise technical terms and try related terms when needed.")
        ),
        required = listOf("query")
    )
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

class UpdateFileTool(
    initialPatches: List<FilePatch> = emptyList()
) : AgentTool(
    name = "updateFile",
    description = "Create or replace a staged file version in Changes. Supply the repository-relative path and the COMPLETE final content of the file. Re-editing the same path replaces the older staged version.",
    inputSchema = objectSchema(
        properties = mapOf(
            "path" to stringProperty("Repository-relative file path to create or replace in staged Changes."),
            "modifiedContent" to stringProperty("Complete final UTF-8 content for the file, not a diff or patch fragment.")
        ),
        required = listOf("path", "modifiedContent")
    )
) {
    private val pendingPatches = linkedMapOf<String, FilePatch>()
    private val changedPaths = linkedSetOf<String>()

    init {
        initialPatches.forEach { patch -> pendingPatches[patch.path] = patch }
    }

    override suspend fun execute(arguments: Map<String, String>): String {
        val path = arguments["path"]?.trim().orEmpty()
        if (path.isBlank()) return "Error: path argument is required."
        val modifiedContent = arguments["modifiedContent"]
            ?: return "Error: modifiedContent is required."

        val previous = pendingPatches[path]
        pendingPatches[path] = FilePatch(
            path = path,
            originalContent = previous?.originalContent,
            modifiedContent = modifiedContent,
            explanation = "Updated by AI Agent"
        )
        changedPaths += path

        return if (previous == null) {
            "Successfully staged $path. The staged version is now available to readFile for verification."
        } else {
            "Successfully replaced the staged version of $path with the latest AI edit."
        }
    }

    fun getPendingPatches(): List<FilePatch> = pendingPatches.values.toList()

    fun getStagedContent(path: String): String? = pendingPatches[path]?.modifiedContent

    fun getChangedPaths(): Set<String> = changedPaths.toSet()
}
