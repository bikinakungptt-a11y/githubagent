package com.example.data.github

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface GitHubService {
    @GET("user/repos")
    suspend fun getUserRepositories(
        @Header("Authorization") authHeader: String,
        @Query("visibility") visibility: String = "all",
        @Query("affiliation") affiliation: String = "owner,collaborator",
        @Query("sort") sort: String = "updated"
    ): List<GitHubRepoDto>

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getRepositoryContent(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Query("ref") ref: String
    ): GitHubContentDto

    @GET("repos/{owner}/{repo}/git/trees/{treeSha}")
    suspend fun getRepositoryTree(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("treeSha") treeSha: String,
        @Query("recursive") recursive: Int = 1
    ): GitHubTreeResponse

    @GET("repos/{owner}/{repo}/branches")
    suspend fun getBranches(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 100
    ): List<GitHubBranchDto>

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getRepositoryDirectory(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Query("ref") ref: String
    ): List<GitHubContentDto>

    @GET("search/code")
    suspend fun searchCode(
        @Header("Authorization") authHeader: String,
        @Query("q") query: String,
        @Query("per_page") perPage: Int = 20
    ): GitHubCodeSearchResponse

    @GET("repos/{owner}/{repo}/git/ref/heads/{branch}")
    suspend fun getBranchReference(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String
    ): GitHubRefDto

    @GET("repos/{owner}/{repo}/git/commits/{commitSha}")
    suspend fun getGitCommit(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("commitSha") commitSha: String
    ): GitCommitDto

    @POST("repos/{owner}/{repo}/git/blobs")
    suspend fun createBlob(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: CreateBlobRequest
    ): GitObjectDto

    @POST("repos/{owner}/{repo}/git/trees")
    suspend fun createTree(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: CreateTreeRequest
    ): GitTreeDto

    @POST("repos/{owner}/{repo}/git/commits")
    suspend fun createGitCommit(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: CreateGitCommitRequest
    ): GitCommitDto

    @PATCH("repos/{owner}/{repo}/git/refs/heads/{branch}")
    suspend fun updateBranchReference(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("branch") branch: String,
        @Body request: UpdateRefRequest
    ): GitHubRefDto

    @POST("repos/{owner}/{repo}/git/refs")
    suspend fun createBranch(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: CreateRefRequest
    ): GitHubRefDto

    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun updateFile(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Body request: UpdateFileRequest
    ): UpdateFileResponse

    @POST("repos/{owner}/{repo}/pulls")
    suspend fun createPullRequest(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body request: CreatePullRequest
    ): PullRequestDto
}

data class CreateRefRequest(
    val ref: String,
    val sha: String
)

data class CreateBlobRequest(
    val content: String,
    val encoding: String = "base64"
)

data class GitObjectDto(
    val sha: String,
    val url: String
)

data class CreateTreeEntry(
    val path: String,
    val mode: String = "100644",
    val type: String = "blob",
    val sha: String
)

data class CreateTreeRequest(
    val base_tree: String,
    val tree: List<CreateTreeEntry>
)

data class GitTreeDto(
    val sha: String,
    val url: String
)

data class CreateGitCommitRequest(
    val message: String,
    val tree: String,
    val parents: List<String>
)

data class GitCommitDto(
    val sha: String,
    val url: String,
    val tree: GitObjectDto
)

data class UpdateRefRequest(
    val sha: String,
    val force: Boolean = false
)

data class GitHubRefDto(
    val ref: String,
    val `object`: RefObjectDto
)

data class RefObjectDto(
    val sha: String,
    val type: String,
    val url: String
)

data class UpdateFileRequest(
    val message: String,
    val content: String,
    val sha: String? = null,
    val branch: String
)

data class UpdateFileResponse(
    val content: GitHubContentDto?,
    val commit: CommitDto
)

data class CommitDto(
    val sha: String,
    val url: String
)

data class CreatePullRequest(
    val title: String,
    val body: String,
    val head: String,
    val base: String
)

data class PullRequestDto(
    val id: Long,
    val number: Int,
    val state: String,
    val title: String,
    val html_url: String
)

data class GitHubRepoDto(
    val id: Long,
    val name: String,
    val full_name: String,
    val private: Boolean,
    val default_branch: String,
    val updated_at: String,
    val permissions: GitHubRepoPermissionsDto? = null
)

data class GitHubRepoPermissionsDto(
    val admin: Boolean = false,
    val maintain: Boolean = false,
    val push: Boolean = false,
    val triage: Boolean = false,
    val pull: Boolean = true
)

data class GitHubBranchDto(
    val name: String,
    val commit: CommitDto
)

data class GitHubTreeResponse(
    val sha: String,
    val url: String,
    val tree: List<GitHubTreeEntry>,
    val truncated: Boolean
)

data class GitHubTreeEntry(
    val path: String,
    val mode: String,
    val type: String,
    val sha: String,
    val size: Long? = null,
    val url: String
)

data class GitHubCodeSearchResponse(
    val total_count: Int,
    val incomplete_results: Boolean,
    val items: List<GitHubCodeSearchItem>
)

data class GitHubCodeSearchItem(
    val name: String,
    val path: String,
    val sha: String,
    val html_url: String
)

data class GitHubContentDto(
    val type: String,
    val encoding: String?,
    val size: Int,
    val name: String,
    val path: String,
    val content: String?,
    val sha: String,
    val url: String,
    val git_url: String,
    val html_url: String,
    val download_url: String?
)
