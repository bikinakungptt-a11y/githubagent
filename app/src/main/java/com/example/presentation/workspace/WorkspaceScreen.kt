package com.example.presentation.workspace

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalContext
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
    onOpenChanges: () -> Unit = {},
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
    val context = LocalContext.current
    var attachments by remember { mutableStateOf<List<com.example.agent.AgentAttachment>>(emptyList()) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var showUploadConfirmation by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val selected = mutableListOf<com.example.agent.AgentAttachment>()
        attachmentError = null
        uris.take(5).forEach { uri ->
            try {
                val resolver = context.contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                var name = "attachment"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                if (bytes.size > 8 * 1024 * 1024) {
                    attachmentError = "$name exceeds the 8 MB limit."
                } else if (mime.startsWith("image/")) {
                    val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    selected += com.example.agent.AgentAttachment(name, mime, dataUrl = "data:$mime;base64,$encoded")
                } else {
                    val textTypes = listOf("text/", "json", "xml", "javascript", "kotlin", "java", "yaml", "toml")
                    if (textTypes.any { mime.contains(it, ignoreCase = true) } ||
                        name.substringAfterLast('.', "") in listOf("kt", "java", "js", "ts", "py", "md", "txt", "json", "xml", "yml", "yaml", "toml", "gradle", "properties")
                    ) {
                        selected += com.example.agent.AgentAttachment(name, mime, textContent = bytes.toString(Charsets.UTF_8))
                    } else {
                        attachmentError = "$name is not a supported text/code or image file."
                    }
                }
            } catch (error: Exception) {
                attachmentError = error.message ?: "Unable to read selected file."
            }
        }
        attachments = (attachments + selected).distinctBy { it.name }.take(5)
    }

    val messages by viewModel.messages.collectAsState()
    val isBusy by viewModel.isAgentBusy.collectAsState()
    val liveStatus by viewModel.liveAgentStatus.collectAsState()
    val canResume by viewModel.canResume.collectAsState()
    val config by viewModel.agentConfig.collectAsState()
    val pendingPatches by viewModel.pendingPatches.collectAsState()
    val branches by viewModel.branches.collectAsState()
    val selectedBranch by viewModel.selectedBranch.collectAsState()

    var showRepoSheet by remember { mutableStateOf(false) }
    var showBranchSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showRepoSheet = true }) {
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = Color.Black, tonalElevation = 0.dp, shadowElevation = 8.dp) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    if (canResume && !isBusy) {
                        Button(
                            onClick = { viewModel.resumeLastRequest() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Retry / Continue from checkpoint")
                        }
                    }

                    Button(
                        onClick = onOpenChanges,
                        enabled = pendingPatches.isNotEmpty() && !isBusy,
                        modifier = Modifier.padding(bottom = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (pendingPatches.isEmpty()) "Commit / Push"
                            else "Commit / Push (${pendingPatches.size} file${if (pendingPatches.size == 1) "" else "s"})"
                        )
                    }

                    if (attachments.isNotEmpty()) {
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            items(attachments) { attachment ->
                                InputChip(
                                    selected = true,
                                    onClick = { attachments = attachments - attachment },
                                    label = { Text("× ${attachment.name}") }
                                )
                            }
                        }
                    }
                    attachmentError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { filePicker.launch(arrayOf("image/*", "text/*", "application/json", "application/xml")) }) {
                            Icon(Icons.Default.Add, contentDescription = "Upload photo or file", tint = Color.White)
                        }
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            placeholder = { Text("Tell the AI what to inspect, create, fix, or change...") },
                            modifier = Modifier.weight(1f),
                            maxLines = 4,
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color.Black,
                                unfocusedContainerColor = Color.Black,
                                focusedBorderColor = Color(0xFF1565C0),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.65f),
                                focusedPlaceholderColor = Color.White.copy(alpha = 0.65f),
                                unfocusedPlaceholderColor = Color.White.copy(alpha = 0.65f),
                                cursorColor = Color(0xFF42A5F5)
                            )
                        )
                        IconButton(
                            onClick = {
                                if (attachments.isEmpty()) {
                                    viewModel.submitRequest(prompt)
                                    prompt = ""
                                } else {
                                    showUploadConfirmation = true
                                }
                            },
                            enabled = !isBusy && prompt.isNotBlank()
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "Send",
                                tint = if (!isBusy && prompt.isNotBlank()) Color(0xFF42A5F5) else Color.White.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
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
            if (isBusy) item { AgentThinkingStatus(liveStatus) }
        }
    }

    if (showUploadConfirmation) {
        AlertDialog(
            onDismissRequest = { showUploadConfirmation = false },
            title = { Text("Send attachments to AI provider?") },
            text = {
                Text("The selected files will be sent to: ${config?.baseUrl ?: "configured Base URL"}. Do not send private files unless you trust this provider.")
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.submitRequest(prompt, attachments = attachments)
                    prompt = ""
                    attachments = emptyList()
                    showUploadConfirmation = false
                }) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { showUploadConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    if (showMoreMenu) {
        DropdownMenu(expanded = true, onDismissRequest = { showMoreMenu = false }) {
            DropdownMenuItem(text = { Text("Open files") }, onClick = { showMoreMenu = false; onOpenFiles() })
            DropdownMenuItem(text = { Text("Review changes") }, onClick = { showMoreMenu = false; onOpenChanges() })
            if (canResume) {
                DropdownMenuItem(
                    text = { Text("Retry / Continue") },
                    onClick = { showMoreMenu = false; viewModel.resumeLastRequest() }
                )
            }
            DropdownMenuItem(text = { Text("Settings") }, onClick = { showMoreMenu = false; onOpenSettings() })
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
                            if (branch == selectedBranch) Icon(Icons.Default.Check, contentDescription = null, tint = Color.Green)
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
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)) {
            Text(text = text, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AgentMessage(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(color = Color.Transparent) {
            Column {
                Text(
                    text = "AI",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(text = text, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

@Composable
fun SystemMessage(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AgentThinkingStatus(status: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2
                    )
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = "Expand")
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp, start = 24.dp)) {
                    Text("Streaming provider response when supported...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Keeping bounded repository context...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Saving resumable checkpoints...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
