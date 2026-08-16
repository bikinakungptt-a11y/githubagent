package com.example.presentation.repositories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.github.GitHubRepository
import com.example.domain.model.RepositoryModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RepositoriesViewModel(
    private val gitHubRepository: GitHubRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RepositoriesUiState(isLoading = true))
    val uiState: StateFlow<RepositoriesUiState> = _uiState.asStateFlow()

    init {
        loadRepositories()
    }

    fun loadRepositories() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val repos = gitHubRepository.getRepositories()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    repositories = repos
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "An unknown error occurred"
                )
            }
        }
    }
}

data class RepositoriesUiState(
    val isLoading: Boolean = false,
    val repositories: List<RepositoryModel> = emptyList(),
    val error: String? = null
)

class RepositoriesViewModelFactory(
    private val gitHubRepository: GitHubRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RepositoriesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RepositoriesViewModel(gitHubRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
