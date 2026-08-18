package com.example.presentation.aiprovider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.di.AppContainerProvider
import com.example.domain.model.ApiFormat
import com.example.domain.model.ReasoningLevel

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "API Configuration",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
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
            Text(
                "API Format",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Auto Detect is recommended. Use a manual format only when a provider requires it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ApiFormat.values().toList()) { format ->
                    FilterChip(
                        selected = uiState.apiFormat == format,
                        onClick = { viewModel.onApiFormatChanged(format) },
                        label = {
                            Text(
                                when (format) {
                                    ApiFormat.AUTO -> "Auto Detect"
                                    ApiFormat.OPENAI_COMPATIBLE -> "OpenAI"
                                    ApiFormat.ANTHROPIC -> "Anthropic"
                                    ApiFormat.LEGACY_TEXT -> "Legacy"
                                }
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Reasoning Effort",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ReasoningLevel.values().toList()) { level ->
                    FilterChip(
                        selected = uiState.reasoningLevel == level,
                        onClick = { viewModel.onReasoningLevelChanged(level) },
                        label = { Text(level.name) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { viewModel.testConnection() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Check Configuration")
            }
            if (uiState.connectionStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    uiState.connectionStatus,
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    viewModel.save()
                    onNavigateBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save & Close")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
