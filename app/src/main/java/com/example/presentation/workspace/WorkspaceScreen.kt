package com.example.presentation.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.di.AppContainerProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    repositoryName: String, 
    onNavigateBack: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: WorkspaceViewModel = viewModel(
        factory = WorkspaceViewModelFactory(
            AppContainerProvider.appContainer.secureCredentialManager,
            AppContainerProvider.appContainer.settingsRepository,
            AppContainerProvider.appContainer.commitManager,
            AppContainerProvider.appContainer.gitHubService,
            repositoryName
        )
    )
) {
    var prompt by remember { mutableStateOf("") }
    val messages by viewModel.messages.collectAsState()
    val isBusy by viewModel.isAgentBusy.collectAsState()
    val config by viewModel.agentConfig.collectAsState()
    val pendingPatches by viewModel.pendingPatches.collectAsState()
    val branches by viewModel.branches.collectAsState()
    val selectedBranch by viewModel.selectedBranch.collectAsState()
    
    var selectedMode by remember { mutableStateOf("Ask") }
    val modes = listOf("Ask", "Edit", "Fix", "Auto Fix")

    var showRepoSheet by remember { mutableStateOf(false) }
    var showBranchSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    // Ideally, pendingPatches would move to Changes tab, 
    // but for now we display it as a banner or handle it here if it exists.
    if (pendingPatches.isNotEmpty()) {
        DiffPreviewScreen(
            patches = pendingPatches,
            onCommit = { message -> viewModel.confirmCommit(message) },
            onCancel = { viewModel.cancelCommit() }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.coding_pattern),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.18f
        )
        Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.clickable { showRepoSheet = true }
                    ) {
                        Text("AI Coding Agent", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Circle, contentDescription = "Online", tint = Color.Green, modifier = Modifier.size(8.dp))
                            Spacer(Modifier.width(6.dp))
                            val reasoning = config?.reasoningLevel?.name ?: "MAX"
                            val model = config?.modelName ?: "Loading..."
                            Text(
                                text = "$repositoryName • $selectedBranch • $model • $reasoning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showBranchSheet = true }) {
                        Icon(Icons.Default.AccountTree, contentDescription = "Branch")
                    }
                    IconButton(onClick = onOpenFiles) {
                        Icon(Icons.Default.Folder, contentDescription = "Files")
                    }
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 8.dp
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        modes.forEach { mode ->
                            FilterChip(
                                selected = selectedMode == mode,
                                onClick = { selectedMode = mode },
                                label = { Text(mode, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onOpenFiles) {
                            Icon(Icons.Default.Add, contentDescription = "Choose context file")
                        }
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            placeholder = { Text("Ask AI to inspect, fix, or modify this repository...") },
                            modifier = Modifier.weight(1f),
                            maxLines = 4,
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                        IconButton(
                            onClick = { 
                                viewModel.submitRequest(prompt, selectedMode)
                                prompt = ""
                            },
                            enabled = !isBusy && prompt.isNotBlank()
                        ) {
                            Icon(
                                Icons.Default.Send, 
                                contentDescription = "Send",
                                tint = if (!isBusy && prompt.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(messages) { msg ->
                if (msg.startsWith("User:")) {
                    UserMessage(msg.removePrefix("User:").trim())
                } else if (msg.startsWith("Agent:")) {
                    AgentMessage(msg.removePrefix("Agent:").trim())
                } else {
                    SystemMessage(msg)
                }
            }
            if (isBusy) {
                item {
                    AgentThinkingStatus()
                }
            }
        }
    }
    
    }

    if (showMoreMenu) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = { showMoreMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Open files") },
                onClick = { showMoreMenu = false; onOpenFiles() }
            )
            DropdownMenuItem(
                text = { Text("Settings") },
                onClick = { showMoreMenu = false; onOpenSettings() }
            )
        }
    }

    if (showRepoSheet) {
        ModalBottomSheet(onDismissRequest = { showRepoSheet = false }) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text("Select Repository", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(16.dp))
                ListItem(
                    headlineContent = { Text(repositoryName) },
                    leadingContent = { Icon(Icons.Default.Check, contentDescription = null, tint = Color.Green) },
                    modifier = Modifier.clickable { showRepoSheet = false }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showBranchSheet) {
        ModalBottomSheet(onDismissRequest = { showBranchSheet = false }) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text("Select Branch", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(16.dp))
                branches.forEach { branch ->
                    ListItem(
                        headlineContent = { Text(branch) },
                        leadingContent = {
                            if (branch == selectedBranch) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Green)
                            }
                        },
                        modifier = Modifier.clickable {
                            viewModel.selectBranch(branch)
                            showBranchSheet = false
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun UserMessage(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AgentMessage(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = Color.Transparent,
        ) {
            Column {
                Text(
                    text = "AI",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@Composable
fun SystemMessage(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun AgentThinkingStatus() {
    var expanded by remember { mutableStateOf(false) }
    
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Thinking deeply", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = "Expand")
                }
            }
            
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp, start = 24.dp)) {
                    Text("Searching repository...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Reading MainActivity.kt...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Preparing fix...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}