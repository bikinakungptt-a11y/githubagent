package com.example.agent

import com.example.domain.model.AIProviderConfig
import com.example.domain.model.ReasoningLevel
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

interface OpenAICompatibleService {
    @POST
    suspend fun createCompletion(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val thinking: ThinkingConfig? = null // For Gemini-style thinking config if supported via proxy
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class ThinkingConfig(
    val level: String
)

data class ChatCompletionResponse(
    val id: String,
    val choices: List<Choice>
)

data class Choice(
    val message: ChatMessage
)

class AIClient(
    private val config: AIProviderConfig,
    private val apiKey: String
) {
    private val service: OpenAICompatibleService

    init {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val logging = HttpLoggingInterceptor().apply { 
            level = HttpLoggingInterceptor.Level.BODY 
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(5, TimeUnit.MINUTES)
            .addInterceptor(logging)
            .build()

        // Dummy base url since we pass absolute url via @Url
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.openai.com/v1/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        service = retrofit.create(OpenAICompatibleService::class.java)
    }

    suspend fun analyze(prompt: String): String {
        val url = if (config.baseUrl.endsWith("/")) {
            "${config.baseUrl}chat/completions"
        } else {
            "${config.baseUrl}/chat/completions"
        }

        val headers = mutableMapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        )
        headers.putAll(config.customHeaders)

        val thinkingLevel = if (config.reasoningModeEnabled) {
            when (config.reasoningLevel) {
                ReasoningLevel.LOW -> "LOW"
                ReasoningLevel.MEDIUM -> "MEDIUM"
                ReasoningLevel.HIGH -> "HIGH"
                ReasoningLevel.MAXIMUM -> "HIGH" // Assume high is max for standard gemini 3.1 APIs if thinkingLevel used
                else -> "AUTO"
            }
        } else null

        val request = ChatCompletionRequest(
            model = config.modelName,
            messages = listOf(
                ChatMessage(role = "system", content = "You are a senior software engineering AI agent."),
                ChatMessage(role = "user", content = prompt)
            ),
            max_tokens = if (config.reasoningModeEnabled) null else config.maxOutputTokens, // per user instruction: Do not set maxOutputTokens when using thinking
            thinking = thinkingLevel?.let { ThinkingConfig(it) }
        )

        val response = try {
            service.createCompletion(url, headers, request)
        } catch (error: SocketTimeoutException) {
            throw IllegalStateException(
                "AI provider did not respond within 5 minutes. Check the Base URL, model name, or provider status.",
                error
            )
        }
        return response.choices.firstOrNull()?.message?.content ?: "No response from AI."
    }
}
