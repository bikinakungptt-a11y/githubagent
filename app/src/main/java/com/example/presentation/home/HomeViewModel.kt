package com.example.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {
    private val _lastSelectedRepo = MutableStateFlow<String?>(null)
    val lastSelectedRepo: StateFlow<String?> = _lastSelectedRepo.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.lastSelectedRepoFlow.collect {
                _lastSelectedRepo.value = it
            }
        }
    }
}

class HomeViewModelFactory(private val settingsRepository: SettingsRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(settingsRepository) as T
    }
}
