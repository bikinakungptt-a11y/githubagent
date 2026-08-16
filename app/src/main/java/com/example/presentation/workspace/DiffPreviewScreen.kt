package com.example.presentation.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.agent.patch.FilePatch
import com.example.ui.theme.IdeDiffAdded
import com.example.ui.theme.IdeDiffRemoved

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffPreviewScreen(
    patches: List<FilePatch>,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var commitMessage by remember { mutableStateOf("AI: Update files") }
    var showCommitSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Changes · ${patches.size} files", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Discard")
                    }
                },
                actions = {
                    Button(
                        onClick = { showCommitSheet = true },
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Text("Review & Commit")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(patches) { patch ->
                DiffFileCard(patch)
            }
        }
    }

    if (showCommitSheet) {
        ModalBottomSheet(onDismissRequest = { showCommitSheet = false }) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Commit changes", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Branch: ai/fix-flow", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = { commitMessage = it },
                    label = { Text("Commit message") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showCommitSheet = false }) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = { 
                        showCommitSheet = false
                        onCommit(commitMessage) 
                    }) {
                        Icon(Icons.Default.Commit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Commit & Push")
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun DiffFileCard(patch: FilePatch) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("M", color = Color(0xFFFFB000), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                Text(patch.path, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            
            // Simple mockup of diff rendering. 
            // In a real app we would parse the diff string.
            val lines = patch.modifiedContent.split("\n").take(20)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                lines.forEach { line ->
                    val color = if (line.startsWith("+")) IdeDiffAdded else if (line.startsWith("-")) IdeDiffRemoved else Color.Transparent
                    Row(modifier = Modifier.fillMaxWidth().background(color)) {
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}