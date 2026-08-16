package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureCredentialManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveApiKey(key: String) {
        sharedPreferences.edit().putString("api_key", key).apply()
    }

    fun getApiKey(): String? {
        return sharedPreferences.getString("api_key", null)
    }

    fun deleteApiKey() {
        sharedPreferences.edit().remove("api_key").apply()
    }

    fun saveGitHubToken(token: String) {
        sharedPreferences.edit().putString("github_token", token).apply()
    }

    fun getGitHubToken(): String? {
        return sharedPreferences.getString("github_token", null)
    }

    fun deleteGitHubToken() {
        sharedPreferences.edit().remove("github_token").apply()
    }
}
