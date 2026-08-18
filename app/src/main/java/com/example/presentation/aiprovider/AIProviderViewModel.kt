package com.example.presentation.aiprovider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.security.SecureCredentialManager
import com.example.data.settings.SettingsRepository
import com.example.domain.model.ReasoningLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AIProviderViewModel(
    private val secureCredentialManager: SecureCredentialManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AIProviderUiState())
    val uiState: StateFlow<AIProviderUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val apiKey = secureCredentialManager.getApiKey() ?: ""
            val baseUrl = settingsRepository.baseUrlFlow.first()
            val modelName = settingsRepository.modelNameFlow.first()
            val reasoningLevel = settingsRepository.reasoningLevelFlow.first()
            _uiState.value = _uiState.value.copy(
                apiKey = apiKey,
                baseUrl = baseUrl,
                modelName = modelName,
                reasoningLevel = reasoningLevel
            )
        }
    }

    fun onApiKeyChanged(newKey: String) {
        _uiState.value = _uiState.value.copy(apiKey = newKey)
    }

    fun onBaseUrlChanged(newUrl: String) {
        _uiState.value = _uiState.value.copy(baseUrl = newUrl)
    }

    fun onModelNameChanged(newName: String) {
        _uiState.value = _uiState.value.copy(modelName = newName)
    }

    fun onReasoningLevelChanged(level: ReasoningLevel) {
        _uiState.value = _uiState.value.copy(reasoningLevel = level)
    }

    fun save() {
        viewModelScope.launch {
            secureCredentialManager.saveApiKey(_uiState.value.apiKey)
            settingsRepository.saveBaseUrl(_uiState.value.baseUrl)
            settingsRepository.saveModelName(_uiState.value.modelName)
            settingsRepository.saveReasoningLevel(_uiState.value.reasoningLevel)
        }
    }

    fun testConnection() {
        val baseUrl = _uiState.value.baseUrl.trim().lowercase()
        val detected = if (baseUrl.contains("anthropic.com") || baseUrl.endsWith("/messages")) {
            "Anthropic Messages"
        } else {
            "OpenAI Compatible"
        }
        _uiState.value = _uiState.value.copy(
            connectionStatus = "Auto detected: $detected"
        )
    }
}

data class AIProviderUiState(
    val apiKey: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val reasoningLevel: ReasoningLevel = ReasoningLevel.MAXIMUM,
    val connectionStatus: String = ""
)

class AIProviderViewModelFactory(
    private val secureCredentialManager: SecureCredentialManager,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AIProviderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AIProviderViewModel(secureCredentialManager, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
