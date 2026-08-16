package com.example.domain.model

data class GitHubConfig(
    val username: String = "",
    val isAppAuth: Boolean = false,
    val selectedRepositories: List<String> = emptyList() // "owner/repo"
)

data class RepositoryModel(
    val id: String,
    val name: String, // "owner/repo"
    val isPrivate: Boolean,
    val defaultBranch: String,
    val permission: RepoPermission,
    val lastUpdate: Long
)

enum class RepoPermission {
    READ_ONLY,
    READ_WRITE
}
