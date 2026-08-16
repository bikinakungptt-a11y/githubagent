package com.example.presentation.navigation

object Routes {
    const val HOME = "home"
    const val AI_PROVIDER = "aiprovider"
    const val GITHUB_AUTH = "githubauth"
    const val REPOSITORIES = "repositories"
    const val WORKSPACE = "workspace/{repoName}"
    const val SETTINGS = "settings"
    
    fun workspace(repoName: String) = "workspace/$repoName"
}

