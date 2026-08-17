package com.example.presentation.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.data.github.GitHubContentDto
import com.example.di.AppContainerProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(repositoryName: String) {
    val service = AppContainerProvider.appContainer.gitHubService
    val token = AppContainerProvider.appContainer.secureCredentialManager.getGitHubToken().orEmpty()
    val parts = remember(repositoryName) { repositoryName.split("/", limit = 2) }
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<GitHubContentDto>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(currentPath, repositoryName) {
        if (parts.size != 2 || token.isBlank()) {
            error = "GitHub PAT or repository is invalid."
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            entries = service.getRepositoryDirectory(
                "Bearer $token", parts[0], parts[1], currentPath, "main"
            ).sortedWith(compareBy<GitHubContentDto> { it.type != "dir" }.thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            error = e.message ?: "Unable to load repository files."
        } finally {
            loading = false
        }
    }

    selectedFile?.let { (name, code) ->
        CodeViewerScreen(name, code) { selectedFile = null }
        return
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (currentPath.isBlank()) "Explorer" else currentPath) },
            navigationIcon = {
                if (currentPath.isNotBlank()) {
                    IconButton(onClick = { currentPath = currentPath.substringBeforeLast("/", "") }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Parent folder")
                    }
                }
            }
        )
    }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search files in this folder...") },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries.filter { query.isBlank() || it.name.contains(query, true) }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (entry.type == "dir") {
                                currentPath = entry.path
                            } else {
                                loading = true
                                scope.launch {
                                    try {
                                        val file = service.getRepositoryContent(
                                            "Bearer $token", parts[0], parts[1], entry.path, "main"
                                        )
                                        val encoded = file.content?.replace("\n", "").orEmpty()
                                        val code = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                                            .toString(Charsets.UTF_8)
                                        selectedFile = entry.name to code
                                    } catch (e: Exception) {
                                        error = e.message ?: "Unable to read file."
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (entry.type == "dir") Icons.Default.Folder else Icons.Default.Description, null)
                        Spacer(Modifier.width(16.dp))
                        Text(entry.name)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeViewerScreen(fileName: String, code: String, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(fileName) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            }
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().background(Color(0xFF1E1E1E))
                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp)
        ) {
            code.split("\n").forEachIndexed { index, line ->
                Row {
                    Text("${index + 1}".padStart(4, ' '), color = Color.Gray, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(16.dp))
                    Text(line, color = Color(0xFFD4D4D4), fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
