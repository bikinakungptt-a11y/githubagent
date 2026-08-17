package com.example.agent

import com.example.agent.patch.FilePatch
import com.example.data.github.CreateRefRequest
import com.example.data.github.GitHubService
import com.example.data.github.UpdateFileRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val token = tokenProvider() ?: throw Exception("Not authenticated")
        val authHeader = "Bearer $token"

        // 1. Get base branch SHA
        val baseRef = gitHubService.getBranchReference(authHeader, owner, repo, baseBranch)
        val baseSha = baseRef.`object`.sha

        var targetBranch = baseBranch

        // 2. Create AI branch if requested
        if (createAiBranch) {
            val dateStr = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
            targetBranch = "ai/fix-$dateStr"
            
            gitHubService.createBranch(
                authHeader, 
                owner, 
                repo, 
                CreateRefRequest(
                    ref = "refs/heads/$targetBranch",
                    sha = baseSha
                )
            )
        }

        // 3. Update files one by one (For a real production app, we would use the Git Trees API to create a single commit with multiple files, but for simplicity here we update individually)
        var lastCommitSha = baseSha
        for (patch in patches) {
            // Get current file sha
            val currentFile = try {
                gitHubService.getRepositoryContent(authHeader, owner, repo, patch.path, targetBranch)
            } catch (e: Exception) {
                null // File might not exist
            }

            val encodedContent = Base64.encodeToString(patch.modifiedContent.toByteArray(), Base64.NO_WRAP)
            
            val updateResp = gitHubService.updateFile(
                authHeader,
                owner,
                repo,
                patch.path,
                UpdateFileRequest(
                    message = commitMessage,
                    content = encodedContent,
                    sha = currentFile?.sha,
                    branch = targetBranch
                )
            )
            lastCommitSha = updateResp.commit.sha
        }

        return@withContext targetBranch
    }
}
