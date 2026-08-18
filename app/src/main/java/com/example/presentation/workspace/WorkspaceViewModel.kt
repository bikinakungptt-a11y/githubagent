package com.example.presentation.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.agent.AgentAttachment
import com.example.agent.AgentEngine
import com.example.agent.AgentResumeState
import com.example.agent.patch.FilePatch
import com.example.agent.tools.AgentTool
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

    private val _liveAgentStatus = MutableStateFlow("Thinking deeply")
    val liveAgentStatus: StateFlow<String> = _liveAgentStatus.asStateFlow()

    private val _canResume = MutableStateFlow(false)
    val canResume: StateFlow<Boolean> = _canResume.asStateFlow()

    private val _pendingPatches = MutableStateFlow<List<FilePatch>>(emptyList())
    val pendingPatches: StateFlow<List<FilePatch>> = _pendingPatches.asStateFlow()

    private val _branches = MutableStateFlow<List<String>>(listOf("main"))
    val branches: StateFlow<List<String>> = _branches.asStateFlow()

    private val _createAiBranch = MutableStateFlow(true)

    private val _selectedBranch = MutableStateFlow("main")
    val selectedBranch: StateFlow<String> = _selectedBranch.asStateFlow()

    private var savedResumeState: AgentResumeState? = null
    private var savedResumeAttachments: List<AgentAttachment> = emptyList()
    private var savedResumeBranch: String? = null

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
        if (branch !in _branches.value || branch == _selectedBranch.value) return
        _selectedBranch.value = branch
        if (savedResumeState != null) {
            clearResumeCheckpoint()
            _messages.value = _messages.value +
                "System: Saved agent resume checkpoint was cleared because the branch changed."
        }
    }

    fun submitRequest(
        request: String,
        mode: String = "Direct",
        attachments: List<AgentAttachment> = emptyList()
    ) {
        val config = _agentConfig.value ?: return
        if (request.isBlank() || _isAgentBusy.value) return

        clearResumeCheckpoint()

        val directInstruction = """
            DIRECT MAXIMUM AGENT MODE:
            Understand the user's instruction naturally without requiring Ask/Edit/Fix/Auto Fix selection.
            Work on any software-engineering task the user gives you, not only website tasks.
            Inspect the repository before answering whenever repository evidence is needed.
            If the user asks to create, edit, change, repair, remove, refactor, migrate, configure, optimize, test, or implement code, use the repository tools and stage every required file change.
            If the user only asks a question, answer it without editing files unless editing is clearly requested.
            For multi-file or multi-step work, continue until every requested requirement is completed or a real blocking error occurs.
            Do not stop after the first successful edit. Re-read staged files when useful and repair your own work before declaring completion.
            Existing files already in Changes are part of your working tree. Preserve them unless the user's new instruction requires changing them.
            When native repository tools are offered by the API, CALL them directly. Never merely say that you are going to read or edit a file.
        """.trimIndent()

        val textAttachments = attachments.mapNotNull { attachment ->
            attachment.textContent?.let { content ->
                "\n\nAttached file: ${attachment.name} (${attachment.mimeType})\n" + compactAttachment(content)
            }
        }.joinToString("")

        val agentRequest = "$directInstruction\n\nUser request: $request$textAttachments"
        _messages.value = _messages.value + "User: $request"

        savedResumeAttachments = attachments
        savedResumeBranch = _selectedBranch.value

        launchAgentRun(
            config = config,
            request = agentRequest,
            attachments = attachments,
            resumeState = null,
            branch = _selectedBranch.value,
            requireRepositoryTool = requestLikelyNeedsRepositoryTools(request)
        )
    }

    fun resumeLastRequest() {
        val config = _agentConfig.value ?: return
        val resume = savedResumeState ?: return
        if (_isAgentBusy.value) return

        val branch = savedResumeBranch ?: _selectedBranch.value
        _messages.value = _messages.value +
            "System: Retry / Continue requested. Resuming from iteration ${resume.iterations + 1}..."

        launchAgentRun(
            config = config,
            request = resume.originalRequest,
            attachments = if (resume.iterations == 0) savedResumeAttachments else emptyList(),
            resumeState = resume,
            branch = branch,
            requireRepositoryTool = resume.requireToolOnStart
        )
    }

    private fun launchAgentRun(
        config: AIProviderConfig,
        request: String,
        attachments: List<AgentAttachment>,
        resumeState: AgentResumeState?,
        branch: String,
        requireRepositoryTool: Boolean
    ) {
        _isAgentBusy.value = true
        _canResume.value = false
        _liveAgentStatus.value = if (resumeState == null) "Analyzing request" else "Resuming checkpoint"

        val token = secureCredentialManager.getGitHubToken() ?: ""
        val apiKey = secureCredentialManager.getApiKey() ?: ""
        val aiClient = com.example.agent.AIClient(config, apiKey)
        val runTools = buildRunTools(token, branch)
        val engine = AgentEngine(config, runTools.tools, aiClient)

        viewModelScope.launch {
            var completed = false
            try {
                var finalAgentResponse = ""
                engine.processRequest(
                    request = request,
                    repoContext = repositoryName,
                    attachments = attachments,
                    requireToolOnStart = requireRepositoryTool,
                    resumeState = resumeState,
                    onCheckpoint = { checkpoint ->
                        savedResumeState = checkpoint
                        savedResumeBranch = branch
                    }
                ).collect { status ->
                    when {
                        status.finalResponse != null -> {
                            completed = true
                            finalAgentResponse = status.finalResponse
                        }
                        status.transient -> {
                            _liveAgentStatus.value = status.message
                        }
                        else -> {
                            _liveAgentStatus.value = status.message
                            _messages.value = _messages.value + "System: ${status.message}"
                        }
                    }
                }

                if (completed) {
                    if (finalAgentResponse.isNotBlank()) {
                        _messages.value = _messages.value + "Agent:\n$finalAgentResponse"
                    }
                    clearResumeCheckpoint()
                } else if (savedResumeState != null) {
                    _canResume.value = true
                }
            } catch (e: Exception) {
                _messages.value = _messages.value + "Agent Error: ${e.message}"
                if (savedResumeState != null) {
                    _canResume.value = true
                    _messages.value = _messages.value +
                        "System: Session checkpoint preserved. Use Retry / Continue to resume without repeating completed work."
                }
            } finally {
                val changedPaths = runTools.updateTool.getChangedPaths()
                if (changedPaths.isNotEmpty()) {
                    val allPending = runTools.updateTool.getPendingPatches()
                    _pendingPatches.value = allPending
                    _messages.value = _messages.value +
                        "System: ${allPending.size} file(s) are ready in Changes for Commit / Push. " +
                        "This run changed ${changedPaths.size} file(s)."
                }
                _isAgentBusy.value = false
                _liveAgentStatus.value = "Thinking deeply"
            }
        }
    }

    private fun buildRunTools(token: String, branch: String): AgentRunTools {
        val updateTool = UpdateFileTool(initialPatches = _pendingPatches.value)
        val readTool = ReadFileTool(
            gitHubService = gitHubService,
            githubToken = token,
            repositoryName = repositoryName,
            branch = branch,
            stagedContentProvider = updateTool::getStagedContent
        )
        val tools: List<AgentTool> = listOf(
            ListFilesTool(gitHubService, token, repositoryName, branch),
            readTool,
            SearchCodeTool(gitHubService, token, repositoryName),
            updateTool
        )
        return AgentRunTools(tools, updateTool)
    }

    private fun requestLikelyNeedsRepositoryTools(request: String): Boolean {
        val normalized = request.lowercase()
        val keywords = listOf(
            "buat", "bikin", "tambah", "tambahkan", "ubah", "edit", "perbaiki", "hapus",
            "lanjut", "lanjutkan", "kerjakan", "cek", "periksa", "baca", "cari", "analisis",
            "implement", "refactor", "migrasi", "konfigur", "optim", "test", "uji", "build",
            "create", "add", "change", "modify", "fix", "repair", "remove", "continue",
            "inspect", "read", "search", "analyze", "update", "upgrade", "implement", "refactor",
            "migrate", "configure", "optimize", "test", "debug"
        )
        return keywords.any { normalized.contains(it) }
    }

    private fun compactAttachment(content: String): String {
        val limit = 70_000
        if (content.length <= limit) return content
        val marker = "\n\n[...attachment compacted for provider reliability...]\n\n"
        val available = limit - marker.length
        val head = (available * 3) / 4
        return content.take(head) + marker + content.takeLast(available - head)
    }

    private fun clearResumeCheckpoint() {
        savedResumeState = null
        savedResumeAttachments = emptyList()
        savedResumeBranch = null
        _canResume.value = false
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
                clearResumeCheckpoint()
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

    private data class AgentRunTools(
        val tools: List<AgentTool>,
        val updateTool: UpdateFileTool
    )
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
