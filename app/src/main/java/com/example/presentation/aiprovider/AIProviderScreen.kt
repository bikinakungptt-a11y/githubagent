package com.example.presentation.aiprovider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.di.AppContainerProvider
import com.example.domain.model.ReasoningLevel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIProviderScreen(
    onNavigateBack: () -> Unit,
    viewModel: AIProviderViewModel = viewModel(
        factory = AIProviderViewModelFactory(
            AppContainerProvider.appContainer.secureCredentialManager,
            AppContainerProvider.appContainer.settingsRepository
        )
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Provider", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("API Configuration", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = { viewModel.onApiKeyChanged(it) },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = { viewModel.onBaseUrlChanged(it) },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = uiState.modelName,
                onValueChange = { viewModel.onModelNameChanged(it) },
                label = { Text("Model Name") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text("Reasoning Effort", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ReasoningLevel.values().toList()) { level ->
                    FilterChip(
                        selected = uiState.reasoningLevel == level,
                        onClick = { viewModel.onReasoningLevelChanged(level) },
                        label = { Text(level.name) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { viewModel.testConnection() }, modifier = Modifier.fillMaxWidth()) {
                Text("Test Connection")
            }
            if (uiState.connectionStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(uiState.connectionStatus, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { 
                viewModel.save()
                onNavigateBack()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Save & Close")
            }
        }
    }
}