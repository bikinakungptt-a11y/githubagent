package com.example.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.ReasoningLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val BASE_URL_KEY = stringPreferencesKey("base_url")
    val baseUrlFlow: Flow<String> = context.dataStore.data.map {
        it[BASE_URL_KEY] ?: "https://api.openai.com/v1"
    }

    suspend fun saveBaseUrl(url: String) {
        context.dataStore.edit { it[BASE_URL_KEY] = url }
    }

    private val MODEL_NAME_KEY = stringPreferencesKey("model_name")
    val modelNameFlow: Flow<String> = context.dataStore.data.map {
        it[MODEL_NAME_KEY] ?: "gemini-3.1-pro-preview"
    }

    suspend fun saveModelName(name: String) {
        context.dataStore.edit { it[MODEL_NAME_KEY] = name }
    }

    private val REASONING_LEVEL_KEY = stringPreferencesKey("reasoning_level")
    val reasoningLevelFlow: Flow<ReasoningLevel> = context.dataStore.data.map {
        val name = it[REASONING_LEVEL_KEY] ?: ReasoningLevel.MAXIMUM.name
        runCatching { ReasoningLevel.valueOf(name) }.getOrDefault(ReasoningLevel.MAXIMUM)
    }

    suspend fun saveReasoningLevel(level: ReasoningLevel) {
        context.dataStore.edit { it[REASONING_LEVEL_KEY] = level.name }
    }

    private val AUTO_PUSH_KEY = booleanPreferencesKey("auto_push")
    val autoPushFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_PUSH_KEY] ?: false }

    suspend fun saveAutoPush(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_PUSH_KEY] = enabled }
    }

    private val CREATE_AI_BRANCH_KEY = booleanPreferencesKey("create_ai_branch")
    val createAiBranchFlow: Flow<Boolean> = context.dataStore.data.map {
        it[CREATE_AI_BRANCH_KEY] ?: true
    }

    suspend fun saveCreateAiBranch(enabled: Boolean) {
        context.dataStore.edit { it[CREATE_AI_BRANCH_KEY] = enabled }
    }

    private val LAST_REPO_KEY = stringPreferencesKey("last_repo")
    val lastSelectedRepoFlow: Flow<String?> = context.dataStore.data.map { it[LAST_REPO_KEY] }

    suspend fun saveLastSelectedRepo(repoName: String) {
        context.dataStore.edit { it[LAST_REPO_KEY] = repoName }
    }
}
