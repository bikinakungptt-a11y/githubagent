package com.example.data.github

import com.example.domain.model.RepositoryModel
import com.example.domain.model.RepoPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class GitHubRepository(
    private val gitHubService: GitHubService,
    private val tokenProvider: suspend () -> String?
) {
    suspend fun getRepositories(): List<RepositoryModel> = withContext(Dispatchers.IO) {
        val token = tokenProvider() ?: throw IllegalStateException("GitHub token not found")
        val authHeader = "Bearer $token"
        
        val reposDto = gitHubService.getUserRepositories(authHeader)
        reposDto.map { dto ->
            RepositoryModel(
                id = dto.id.toString(),
                name = dto.full_name,
                isPrivate = dto.private,
                defaultBranch = dto.default_branch ?: "main",
                permission = if (dto.permissions?.push == true) {
                    RepoPermission.READ_WRITE
                } else {
                    RepoPermission.READ_ONLY
                },
                lastUpdate = try { Instant.parse(dto.updated_at).toEpochMilli() } catch (e: Exception) { 0L }
            )
        }
    }
}
