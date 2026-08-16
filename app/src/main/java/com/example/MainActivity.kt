package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.presentation.aiprovider.AIProviderScreen
import com.example.presentation.github.GitHubAuthScreen
import com.example.presentation.home.HomeScreen
import com.example.presentation.navigation.*
import com.example.presentation.repositories.RepositoriesScreen
import com.example.presentation.workspace.WorkspaceScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    com.example.di.AppContainerProvider.init(this)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        val navController = rememberNavController()
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize() // handle padding in screens or here
          ) {
            composable(Routes.HOME) {
              HomeScreen(
                onNavigateToAIProvider = { navController.navigate(Routes.AI_PROVIDER) },
                onNavigateToGitHubAuth = { navController.navigate(Routes.GITHUB_AUTH) },
                onNavigateToRepositories = { navController.navigate(Routes.REPOSITORIES) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToWorkspace = { repoName -> navController.navigate(Routes.workspace(repoName)) }
              )
            }
            composable(Routes.AI_PROVIDER) {
              AIProviderScreen(
                onNavigateBack = { navController.popBackStack() }
              )
            }
            composable(Routes.GITHUB_AUTH) {
              GitHubAuthScreen(
                onNavigateBack = { navController.popBackStack() }
              )
            }
            composable(Routes.REPOSITORIES) {
              RepositoriesScreen(
                onNavigateToWorkspace = { repoName -> navController.navigate(Routes.workspace(repoName)) },
                onNavigateBack = { navController.popBackStack() }
              )
            }
            composable(Routes.WORKSPACE) { backStackEntry ->
              val rawRepoName = backStackEntry.arguments?.getString("repoName") ?: "unknown"
              val repoName = rawRepoName.replace("_-_", "/")
              com.example.presentation.workspace.MainIdeScreen(
                repositoryName = repoName, 
                onNavigateBack = { navController.popBackStack() }
              )
            }
            composable(Routes.SETTINGS) {
              com.example.presentation.settings.SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
              )
            }
          }
        }
      }
    }
  }
}

