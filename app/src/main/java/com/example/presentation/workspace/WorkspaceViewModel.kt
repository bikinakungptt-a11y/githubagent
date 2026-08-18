package com.example.presentation.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.agent.AgentEngine
import com.example.agent.tools.ListFilesTool
import com.example.agent.tools.ReadFileTool
import com.example.agent.tools.SearchCodeTool
import com.example.agent.tools.UpdateFileTool
import com.example.data.security.SecureCredentialManager
import com.example.data.settings.SettingsRepository
import com.example.domain.model.AIProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WorkspaceViewModel(
    private val secureCredentialManager: SecureCredentialManager,
    private val settingsRepository: SettingsRepository,
    private val commitManager: com.example.agent.CommitManager,
    private val gitHubService: com.example.data.github.GitHubService,
    private val repositoryName: String
) : ViewModel() {

    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages.asStateFlow()

    private val _agentConfig = MutableStateFlow<AIProviderConfig?>(null)
    val agentConfig: StateFlow<AIProviderConfig?> = _agentConfig.asStateFlow()

    private val _isAgentBusy = MutableStateFlow(false)
    val isAgentBusy: StateFlow<Boolean> = _isAgentBusy.asStateFlow()

    private val _pendingPatches = MutableStateFlow<List<com.example.agent.patch.FilePatch>>(emptyList())
    val pendingPatches: StateFlow<List<com.example.agent.patch.FilePatch>> = _pendingPatches.asStateFlow()

    private val _branches = MutableStateFlow<List<String>>(listOf("main"))
    val branches: StateFlow<List<String>> = _branches.asStateFlow()

    private val _createAiBranch = MutableStateFlow(true)

    private val _selectedBranch = MutableStateFlow("main")
    val selectedBranch: StateFlow<String> = _selectedBranch.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val config = AIProviderConfig(
                    baseUrl = settingsRepository.baseUrlFlow.first(),
                    modelName = settingsRepository.modelNameFlow.first(),
                    reasoningLevel = settingsRepository.reasoningLevelFlow.first()
                )
                _agentConfig.value = config
                settingsRepository.saveLastSelectedRepo(repositoryName)
                _createAiBranch.value = settingsRepository.createAiBranchFlow.first()
                loadBranches()
            } catch (error: Exception) {
                _agentConfig.value = AIProviderConfig()
                _messages.value = listOf("System: Workspace settings were reset because they could not be loaded.")
            }
        }
    }

    private suspend fun loadBranches() {
        val token = secureCredentialManager.getGitHubToken().orEmpty()
        val parts = repositoryName.split("/", limit = 2)
        if (token.isBlank() || parts.size != 2) return
        try {
            val remoteBranches = gitHubService.getBranches("Bearer $token", parts[0], parts[1]).map { it.name }
            if (remoteBranches.isNotEmpty()) {
                _branches.value = remoteBranches
                if (_selectedBranch.value !in remoteBranches) {
                    _selectedBranch.value = remoteBranches.first()
                }
            }
        } catch (_: Exception) {
            // Keep main as a safe fallback and expose API failures during actual agent operations.
        }
    }

    fun selectBranch(branch: String) {
        if (branch in _branches.value) _selectedBranch.value = branch
    }

    fun submitRequest(
        request: String,
        mode: String = "Direct",
        attachments: List<com.example.agent.AgentAttachment> = emptyList()
    ) {
        val config = _agentConfig.value ?: return
        if (request.isBlank()) return

        val directInstruction = """
            DIRECT AGENT MODE:
            Understand the user's instruction naturally without requiring Ask/Edit/Fix/Auto Fix selection.
            Inspect the repository before answering when repository evidence is needed.
            If the user asks to create, edit, change, repair, remove, refactor, or implement code, use the repository tools and stage the requested file changes.
            If the user only asks a question, answer it without editing files unless editing is clearly requested.
            For multi-file work, continue until every requested file/change is completed or a real blocking error occurs.
            Do not stop after the first edited file when the request clearly requires multiple files.
        """.trimIndent()

        val textAttachments = attachments.mapNotNull { attachment ->
            attachment.textContent?.let { content ->
                "\n\nAttached file: ${attachment.name} (${attachment.mimeType})\n" + content.take(120_000)
            }
        }.joinToString("")

        val agentRequest = "$directInstruction\n\nUser request: $request$textAttachments"
        _messages.value = _messages.value + "User: $request"
        _isAgentBusy.value = true

        val token = secureCredentialManager.getGitHubToken() ?: ""
        val apiKey = secureCredentialManager.getApiKey() ?: ""
        val aiClient = com.example.agent.AIClient(config, apiKey)

        val tools = listOf(
            ListFilesTool(gitHubService, token, repositoryName, _selectedBranch.value),
            ReadFileTool(gitHubService, token, repositoryName, _selectedBranch.value),
            SearchCodeTool(gitHubService, token, repositoryName),
            UpdateFileTool()
        )

        val engine = AgentEngine(config, tools, aiClient)

        viewModelScope.launch {
            try {
                var finalAgentResponse = ""
                engine.processRequest(agentRequest, repositoryName, attachments).collect { status ->
                    if (status.finalResponse != null) {
                        finalAgentResponse = status.finalResponse
                    } else {
                        _messages.value = _messages.value + "System: ${status.message}"
                    }
                }

                if (finalAgentResponse.isNotBlank()) {
                    _messages.value = _messages.value + "Agent:\n$finalAgentResponse"
                }
            } catch (e: Exception) {
                _messages.value = _messages.value + "Agent Error: ${e.message}"
            } finally {
                // Preserve previous staged files and merge newly edited files by path.
                // If the same path is edited again, the latest version replaces the older staged version.
                val newlyPending = (tools.find { it.name == "updateFile" } as? UpdateFileTool)
                    ?.getPendingPatches()
                    .orEmpty()

                if (newlyPending.isNotEmpty()) {
                    val merged = (_pendingPatches.value + newlyPending)
                        .associateBy { it.path }
                        .values
                        .toList()
                    _pendingPatches.value = merged
                    _messages.value = _messages.value +
                        "System: ${merged.size} file(s) are ready in Changes for Commit / Push."
                }
                _isAgentBusy.value = false
            }
        }
    }

    fun confirmCommit(commitMessage: String) {
        val patches = _pendingPatches.value
        if (patches.isEmpty()) return

        _isAgentBusy.value = true
        _messages.value = _messages.value + "System: Committing and pushing ${patches.size} files..."

        viewModelScope.launch {
            try {
                val parts = repositoryName.split("/")
                val owner = parts.getOrNull(0) ?: "owner"
                val repo = parts.getOrNull(1) ?: "repo"

                val newBranch = commitManager.commitAndPush(
                    owner = owner,
                    repo = repo,
                    baseBranch = _selectedBranch.value,
                    patches = patches,
                    commitMessage = commitMessage,
                    createAiBranch = _createAiBranch.value
                )
                _pendingPatches.value = emptyList()
                _messages.value = _messages.value + "System: Successfully pushed to branch $newBranch"
            } catch (e: Exception) {
                _messages.value = _messages.value +
                    "System Error pushing: ${e.message}\nChanges were kept so you can retry."
            } finally {
                _isAgentBusy.value = false
            }
        }
    }

    fun cancelCommit() {
        _pendingPatches.value = emptyList()
        _messages.value = _messages.value + "System: Commit cancelled by user."
    }
}

class WorkspaceViewModelFactory(
    private val secureCredentialManager: SecureCredentialManager,
    private val settingsRepository: SettingsRepository,
    private val commitManager: com.example.agent.CommitManager,
    private val gitHubService: com.example.data.github.GitHubService,
    private val repositoryName: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorkspaceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WorkspaceViewModel(
                secureCredentialManager,
                settingsRepository,
                commitManager,
                gitHubService,
                repositoryName
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
