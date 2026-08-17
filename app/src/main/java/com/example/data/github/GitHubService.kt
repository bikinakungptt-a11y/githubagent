package com.example.data.github

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
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

    @GET("repos/{owner}/{repo}/branches")
    suspend fun getBranches(
        @Header("Authorization") authHeader: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 100
    ): List<GitHubBranchDto>

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
    val updated_at: String
)

data class GitHubBranchDto(
    val name: String,
    val commit: CommitDto
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
