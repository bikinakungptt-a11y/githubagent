package com.example.presentation.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.security.SecureCredentialManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GitHubAuthViewModel(
    private val secureCredentialManager: SecureCredentialManager
) : ViewModel() {

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()
    
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    init {
        viewModelScope.launch {
            _token.value = secureCredentialManager.getGitHubToken() ?: ""
        }
    }

    fun onTokenChanged(newToken: String) {
        _token.value = newToken
    }

    fun save() {
        viewModelScope.launch {
            secureCredentialManager.saveGitHubToken(_token.value)
            _status.value = "Saved successfully"
        }
    }
}

class GitHubAuthViewModelFactory(
    private val secureCredentialManager: SecureCredentialManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GitHubAuthViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GitHubAuthViewModel(secureCredentialManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
