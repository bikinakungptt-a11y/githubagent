package com.example.presentation.workspace

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun MainIdeScreen(
    repositoryName: String,
    onNavigateBack: () -> Unit
) {
    val navController = rememberNavController()
    var selectedTab by remember { mutableStateOf("agent") }
    val workspaceViewModel: WorkspaceViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = WorkspaceViewModelFactory(
            com.example.di.AppContainerProvider.appContainer.secureCredentialManager,
            com.example.di.AppContainerProvider.appContainer.settingsRepository,
            com.example.di.AppContainerProvider.appContainer.commitManager,
            com.example.di.AppContainerProvider.appContainer.gitHubService,
            repositoryName
        )
    )
    val pendingPatches by workspaceViewModel.pendingPatches.collectAsState()
    val historyMessages by workspaceViewModel.messages.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.Black) {
                NavigationBarItem(
                    selected = selectedTab == "agent",
                    onClick = { selectedTab = "agent"; navController.navigate("agent") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Chat, contentDescription = "Agent") }, // Replace with real icons later
                    label = { Text("Agent") },
                    colors = blueBlackNavigationColors()
                )
                NavigationBarItem(
                    selected = selectedTab == "files",
                    onClick = { selectedTab = "files"; navController.navigate("files") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Files") },
                    label = { Text("Files") },
                    colors = blueBlackNavigationColors()
                )
                NavigationBarItem(
                    selected = selectedTab == "changes",
                    onClick = { selectedTab = "changes"; navController.navigate("changes") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Edit, contentDescription = "Changes") },
                    label = { Text("Changes") },
                    colors = blueBlackNavigationColors()
                )
                NavigationBarItem(
                    selected = selectedTab == "history",
                    onClick = { selectedTab = "history"; navController.navigate("history") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("History") },
                    colors = blueBlackNavigationColors()
                )
                NavigationBarItem(
                    selected = selectedTab == "settings",
                    onClick = { selectedTab = "settings"; navController.navigate("settings") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = blueBlackNavigationColors()
                )
            }
        },
        containerColor = Color.Black
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "agent",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("agent") {
                WorkspaceScreen(
                    repositoryName = repositoryName,
                    onNavigateBack = onNavigateBack,
                    onOpenFiles = {
                        selectedTab = "files"
                        navController.navigate("files") { launchSingleTop = true }
                    },
                    onOpenSettings = {
                        selectedTab = "settings"
                        navController.navigate("settings") { launchSingleTop = true }
                    },
                    viewModel = workspaceViewModel
                )
            }
            composable("files") {
                FileExplorerScreen(repositoryName = repositoryName)
            }
            composable("changes") {
                if (pendingPatches.isEmpty()) {
                    Text("No pending changes", modifier = Modifier.padding(16.dp))
                } else {
                    DiffPreviewScreen(
                        patches = pendingPatches,
                        onCommit = workspaceViewModel::confirmCommit,
                        onCancel = workspaceViewModel::cancelCommit
                    )
                }
            }
            composable("history") {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.padding(16.dp)
                ) {
                    if (historyMessages.isEmpty()) {
                        item { Text("No agent history yet") }
                    } else {
                        items(historyMessages) { message ->
                            Text(message, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
            }
            composable("settings") {
                com.example.presentation.settings.SettingsScreen(
                    onNavigateBack = {
                        selectedTab = "agent"
                        navController.navigate("agent") { launchSingleTop = true }
                    }
                )
            }
        }
    }
}

@Composable
private fun blueBlackNavigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Color.White,
    selectedTextColor = Color.White,
    indicatorColor = Color(0xFF1565C0),
    unselectedIconColor = Color.White.copy(alpha = 0.72f),
    unselectedTextColor = Color.White.copy(alpha = 0.72f)
)
