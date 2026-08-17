package com.example.di

import android.content.Context
import com.example.data.security.SecureCredentialManager

import com.example.data.settings.SettingsRepository

import com.example.agent.CommitManager
import com.example.data.github.GitHubRepository
import com.example.data.github.GitHubService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class AppContainer(private val context: Context) {
    val secureCredentialManager by lazy {
        SecureCredentialManager(context)
    }
    
    val settingsRepository by lazy {
        SettingsRepository(context)
    }

    private val moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private val okHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply { 
            level = HttpLoggingInterceptor.Level.BASIC 
        }
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()
    }

    private val gitHubRetrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val gitHubService: GitHubService by lazy {
        gitHubRetrofit.create(GitHubService::class.java)
    }

    val gitHubRepository: GitHubRepository by lazy {
        GitHubRepository(gitHubService) { secureCredentialManager.getGitHubToken() }
    }
    
    val commitManager: CommitManager by lazy {
        CommitManager(gitHubService) { secureCredentialManager.getGitHubToken() }
    }
}

object AppContainerProvider {
    lateinit var appContainer: AppContainer
        private set

    fun init(context: Context) {
        if (!this::appContainer.isInitialized) {
            appContainer = AppContainer(context.applicationContext)
        }
    }
}
