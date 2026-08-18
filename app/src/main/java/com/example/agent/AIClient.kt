package com.example.agent

import com.example.domain.model.AIProviderConfig
import com.example.domain.model.ReasoningLevel
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url

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
    val messages: List<ChatRequestMessage>,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val thinking: ThinkingConfig? = null
)

data class ChatRequestMessage(
    val role: String,
    val content: Any
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatContentPart(
    val type: String,
    val text: String? = null,
    val image_url: ChatImageUrl? = null
)

data class ChatImageUrl(
    val url: String
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
            redactHeader("Authorization")
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(10, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.openai.com/v1/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        service = retrofit.create(OpenAICompatibleService::class.java)
    }

    suspend fun analyze(prompt: String, attachments: List<AgentAttachment> = emptyList()): String {
        val cleanBaseUrl = config.baseUrl.trim()
        val cleanModel = config.modelName.trim()
        val cleanApiKey = apiKey.trim()

        require(cleanBaseUrl.isNotBlank()) { "AI Base URL is missing." }
        require(cleanModel.isNotBlank()) { "AI model name is missing." }
        require(cleanApiKey.isNotBlank()) { "AI API key is missing." }

        val url = if (cleanBaseUrl.endsWith("/")) {
            "${cleanBaseUrl}chat/completions"
        } else {
            "$cleanBaseUrl/chat/completions"
        }

        val headers = mutableMapOf(
            "Authorization" to "Bearer $cleanApiKey",
            "Content-Type" to "application/json"
        )
        headers.putAll(config.customHeaders)

        val thinkingLevel = if (config.reasoningModeEnabled) {
            when (config.reasoningLevel) {
                ReasoningLevel.LOW -> "LOW"
                ReasoningLevel.MEDIUM -> "MEDIUM"
                ReasoningLevel.HIGH -> "HIGH"
                ReasoningLevel.MAXIMUM -> "HIGH"
                else -> "AUTO"
            }
        } else {
            null
        }

        val imageParts = attachments.mapNotNull { attachment ->
            attachment.dataUrl?.let {
                ChatContentPart(type = "image_url", image_url = ChatImageUrl(it))
            }
        }
        val userContent: Any = if (imageParts.isEmpty()) {
            prompt
        } else {
            listOf(ChatContentPart(type = "text", text = prompt)) + imageParts
        }

        val request = ChatCompletionRequest(
            model = cleanModel,
            messages = listOf(
                ChatRequestMessage(
                    role = "system",
                    content = "You are a senior autonomous software engineering AI agent. Work carefully, use repository evidence, and continue until the user's requested task is complete."
                ),
                ChatRequestMessage(role = "user", content = userContent)
            ),
            max_tokens = if (config.reasoningModeEnabled) null else config.maxOutputTokens,
            thinking = thinkingLevel?.let { ThinkingConfig(it) }
        )

        val maxAttempts = 5
        var lastError: Exception? = null

        repeat(maxAttempts) { attemptIndex ->
            try {
                val response = service.createCompletion(url, headers, request)
                return response.choices.firstOrNull()?.message?.content
                    ?: throw IllegalStateException("AI provider returned no response choice.")
            } catch (error: HttpException) {
                lastError = error
                val code = error.code()
                val retryable = code == 408 || code == 425 || code == 429 || code in 500..599
                val details = runCatching { error.response()?.errorBody()?.string() }
                    .getOrNull()
                    ?.take(600)
                    .orEmpty()

                if (!retryable || attemptIndex == maxAttempts - 1) {
                    throw IllegalStateException(
                        "AI provider error HTTP $code" +
                            if (details.isBlank()) "." else ": $details",
                        error
                    )
                }

                delay(retryDelayMillis(error, attemptIndex))
            } catch (error: IOException) {
                lastError = error
                if (attemptIndex == maxAttempts - 1) {
                    throw IllegalStateException(
                        "AI provider connection failed after $maxAttempts attempts: " +
                            (error.message ?: error.javaClass.simpleName),
                        error
                    )
                }
                delay(exponentialBackoffMillis(attemptIndex))
            }
        }

        throw IllegalStateException(
            "AI provider failed after $maxAttempts attempts: ${lastError?.message ?: "unknown error"}",
            lastError
        )
    }

    private fun retryDelayMillis(error: HttpException, attemptIndex: Int): Long {
        val retryAfterSeconds = error.response()
            ?.headers()
            ?.get("Retry-After")
            ?.trim()
            ?.toLongOrNull()

        return if (retryAfterSeconds != null) {
            (retryAfterSeconds * 1_000L).coerceIn(1_000L, 60_000L)
        } else {
            exponentialBackoffMillis(attemptIndex)
        }
    }

    private fun exponentialBackoffMillis(attemptIndex: Int): Long {
        val multiplier = 1L shl attemptIndex.coerceIn(0, 4)
        return (2_000L * multiplier).coerceAtMost(30_000L)
    }
}
