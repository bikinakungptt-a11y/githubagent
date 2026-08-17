package com.example.agent

import android.util.Base64
import com.example.agent.patch.FilePatch
import com.example.data.github.CreateBlobRequest
import com.example.data.github.CreateGitCommitRequest
import com.example.data.github.CreateRefRequest
import com.example.data.github.CreateTreeEntry
import com.example.data.github.CreateTreeRequest
import com.example.data.github.GitHubService
import com.example.data.github.UpdateRefRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

class CommitManager(
    private val gitHubService: GitHubService,
    private val tokenProvider: () -> String?
) {
    suspend fun commitAndPush(
        owner: String,
        repo: String,
        baseBranch: String,
        patches: List<FilePatch>,
        commitMessage: String,
        createAiBranch: Boolean
    ): String = withContext(Dispatchers.IO) {
        val token = tokenProvider()?.trim().orEmpty()
        if (token.isBlank()) throw IllegalStateException("GitHub PAT is missing.")
        if (patches.isEmpty()) throw IllegalStateException("There are no files to commit.")

        val authHeader = "Bearer $token"
        var stage = "reading base branch"

        try {
            val baseRef = gitHubService.getBranchReference(authHeader, owner, repo, baseBranch)
            val baseCommitSha = baseRef.`object`.sha

            stage = "reading base tree"
            val baseCommit = gitHubService.getGitCommit(authHeader, owner, repo, baseCommitSha)
            val baseTreeSha = baseCommit.tree.sha

            stage = "uploading file contents"
            val treeEntries = patches.map { patch ->
                val encoded = Base64.encodeToString(
                    patch.modifiedContent.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                val blob = gitHubService.createBlob(
                    authHeader,
                    owner,
                    repo,
                    CreateBlobRequest(content = encoded)
                )
                CreateTreeEntry(path = patch.path, sha = blob.sha)
            }

            stage = "creating repository tree"
            val tree = gitHubService.createTree(
                authHeader,
                owner,
                repo,
                CreateTreeRequest(base_tree = baseTreeSha, tree = treeEntries)
            )

            stage = "creating commit"
            val commit = gitHubService.createGitCommit(
                authHeader,
                owner,
                repo,
                CreateGitCommitRequest(
                    message = commitMessage.ifBlank { "Update files with AI Agent" },
                    tree = tree.sha,
                    parents = listOf(baseCommitSha)
                )
            )

            if (createAiBranch) {
                stage = "creating AI branch"
                val dateStr = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
                val targetBranch = "ai/fix-$dateStr"
                gitHubService.createBranch(
                    authHeader,
                    owner,
                    repo,
                    CreateRefRequest(
                        ref = "refs/heads/$targetBranch",
                        sha = commit.sha
                    )
                )
                targetBranch
            } else {
                stage = "updating branch $baseBranch"
                gitHubService.updateBranchReference(
                    authHeader,
                    owner,
                    repo,
                    baseBranch,
                    UpdateRefRequest(sha = commit.sha, force = false)
                )
                baseBranch
            }
        } catch (error: HttpException) {
            val details = runCatching { error.response()?.errorBody()?.string() }
                .getOrNull()
                ?.take(600)
                .orEmpty()
            throw IllegalStateException(
                "GitHub failed while $stage (HTTP ${error.code()})" +
                    if (details.isBlank()) "." else ": $details",
                error
            )
        } catch (error: Exception) {
            throw IllegalStateException(
                "Commit failed while $stage: ${error.message ?: error.javaClass.simpleName}",
                error
            )
        }
    }
}
