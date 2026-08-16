package com.example.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.di.AppContainerProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAIProvider: () -> Unit,
    onNavigateToGitHubAuth: () -> Unit,
    onNavigateToRepositories: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToWorkspace: (String) -> Unit,
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(AppContainerProvider.appContainer.settingsRepository)
    )
) {
    val lastSelectedRepo by viewModel.lastSelectedRepo.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Welcome to GitAgent", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Setup Workspace",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Configure your IDE to start using the autonomous coding agent.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))

            SetupCard(
                title = "Connect AI Provider",
                subtitle = "Configure your API key and preferred model",
                icon = Icons.Default.Key,
                onClick = onNavigateToAIProvider
            )
            Spacer(Modifier.height(16.dp))
            SetupCard(
                title = "Connect GitHub",
                subtitle = "Authorize access to your repositories",
                icon = Icons.Default.Cloud,
                onClick = onNavigateToGitHubAuth
            )
            Spacer(Modifier.height(16.dp))
            SetupCard(
                title = "Select Repository",
                subtitle = "Choose a repository to work on",
                icon = Icons.Default.Code,
                onClick = onNavigateToRepositories
            )
            
            if (lastSelectedRepo != null) {
                Spacer(Modifier.height(16.dp))
                SetupCard(
                    title = "AI Agent / Start Coding",
                    subtitle = "Resume working on $lastSelectedRepo",
                    icon = Icons.Default.PlayArrow,
                    onClick = { 
                        val safeName = lastSelectedRepo!!.replace("/", "_-_")
                        onNavigateToWorkspace(safeName) 
                    }
                )
            }
        }
    }
}

@Composable
fun SetupCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}