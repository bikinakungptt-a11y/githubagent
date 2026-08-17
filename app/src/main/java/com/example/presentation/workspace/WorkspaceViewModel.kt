package com.example.presentation.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.agent.AgentEngine
import com.example.agent.AgentStatus
import com.example.agent.tools.AgentTool
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

    init {
        viewModelScope.launch {
            val config = AIProviderConfig(
                baseUrl = settingsRepository.baseUrlFlow.first(),
                modelName = settingsRepository.modelNameFlow.first(),
                reasoningLevel = settingsRepository.reasoningLevelFlow.first()
            )
            _agentConfig.value = config
            
            settingsRepository.saveLastSelectedRepo(repositoryName)
        }
    }

    fun submitRequest(request: String) {
        val config = _agentConfig.value ?: return
        if (request.isBlank()) return
        
        _messages.value = _messages.value + "User: $request"
        _isAgentBusy.value = true

        val token = secureCredentialManager.getGitHubToken() ?: ""
        val apiKey = secureCredentialManager.getApiKey() ?: ""
        
        val aiClient = com.example.agent.AIClient(config, apiKey)

        val tools = listOf(
            ReadFileTool(gitHubService, token, repositoryName, "main"),
            SearchCodeTool(gitHubService, token, repositoryName),
            UpdateFileTool()
        )
        
        val engine = AgentEngine(config, tools, aiClient)
        
        viewModelScope.launch {
            try {
                var finalAgentResponse = ""
                engine.processRequest(request, repositoryName).collect { status ->
                    if (status.finalResponse != null) {
                        finalAgentResponse = status.finalResponse
                    } else {
                        // Append status messages (could be displayed differently, but here we add to messages)
                        _messages.value = _messages.value + "System: ${status.message}"
                    }
                }
                
                if (finalAgentResponse.isNotBlank()) {
                    _messages.value = _messages.value + "Agent:\n$finalAgentResponse"
                }

                // If UpdateFileTool was called, we should ideally parse the updated files from the tool. 
                // For now, we simulate finding the patches if any were requested.
                // In a robust implementation, the tools themselves would register pending patches.
                val pending = (tools.find { it.name == "updateFile" } as? UpdateFileTool)?.getPendingPatches() ?: emptyList()
                
                if (pending.isNotEmpty()) {
                    _pendingPatches.value = pending
                }
                
            } catch (e: Exception) {
                _messages.value = _messages.value + "Agent Error: ${e.message}"
            }
            _isAgentBusy.value = false
        }
    }

    fun confirmCommit(commitMessage: String) {
        val patches = _pendingPatches.value
        _pendingPatches.value = emptyList() // clear
        
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
                    baseBranch = "main", // in real app get from context
                    patches = patches,
                    commitMessage = commitMessage,
                    createAiBranch = true
                )
                _messages.value = _messages.value + "System: Successfully pushed to branch $newBranch"
            } catch(e: Exception) {
                _messages.value = _messages.value + "System Error pushing: ${e.message}"
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
