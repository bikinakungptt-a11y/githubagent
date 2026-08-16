package com.example.presentation.github

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.di.AppContainerProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubAuthScreen(
    onNavigateBack: () -> Unit,
    viewModel: GitHubAuthViewModel = viewModel(
        factory = GitHubAuthViewModelFactory(AppContainerProvider.appContainer.secureCredentialManager)
    )
) {
    val token by viewModel.token.collectAsState()
    val status by viewModel.status.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub Account", style = MaterialTheme.typography.titleMedium) },
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
            Text("Authorization", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { viewModel.onTokenChanged(it) },
                label = { Text("Personal Access Token (PAT)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "GitAgent uses your PAT to clone, read, and create branches on your behalf.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            if (status.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
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