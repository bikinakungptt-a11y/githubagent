package com.example.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.di.AppContainerProvider
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val credentials = AppContainerProvider.appContainer.secureCredentialManager
    val repository = AppContainerProvider.appContainer.settingsRepository
    val autoPush by repository.autoPushFlow.collectAsState(initial = false)
    val createBranch by repository.createAiBranchFlow.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
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
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (status.isNotBlank()) {
                item { Text(status, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp)) }
            }
            item {
                Text(
                    "Security",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                )
                ListItem(
                    headlineContent = { Text("Clear Credentials") },
                    supportingContent = { Text("Removes API keys and tokens from secure storage") },
                    trailingContent = {
                        Button(onClick = {
                            credentials.deleteApiKey()
                            credentials.deleteGitHubToken()
                            status = "API key and GitHub PAT removed."
                        }) { Text("Clear") }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
            item {
                Text(
                    "Agent Behavior",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
                )
                ListItem(
                    headlineContent = { Text("Auto Push") },
                    supportingContent = { Text("Disabled for safety; review is required before every push") },
                    trailingContent = {
                        Switch(checked = false, onCheckedChange = null, enabled = false)
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
                )
                ListItem(
                    headlineContent = { Text("Create AI Branch") },
                    supportingContent = { Text("Always create a new branch for AI changes") },
                    trailingContent = {
                        Switch(
                            checked = createBranch,
                            onCheckedChange = { enabled ->
                                scope.launch { repository.saveCreateAiBranch(enabled) }
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        }
    }
}