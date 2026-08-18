package com.example.agent

import com.example.domain.model.AIProviderConfig
import com.example.domain.model.ApiFormat
import com.example.domain.model.ReasoningLevel
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
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

interface AnthropicCompatibleService {
    @POST
    suspend fun createMessage(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body request: AnthropicMessageRequest
    ): AnthropicMessageResponse
}

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatRequestMessage>,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val reasoning_effort: String? = null,
    val tools: List<OpenAIToolSpec>? = null,
    val tool_choice: Any? = null
)

data class ChatRequestMessage(
    val role: String,
    val content: Any
)

data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList()
)

data class Choice(
    val message: ChatMessage
)

data class ChatMessage(
    val role: String? = null,
    val content: Any? = null,
    val tool_calls: List<OpenAIToolCall>? = null
)

data class OpenAIToolSpec(
    val type: String = "function",
    val function: OpenAIFunctionDefinition
)

data class OpenAIFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class OpenAIToolCall(
    val id: String? = null,
    val type: String? = null,
    val function: OpenAIFunctionCall? = null
)

data class OpenAIFunctionCall(
    val name: String,
    val arguments: Any? = null
)

data class AnthropicMessageRequest(
    val model: String,
    val max_tokens: Int,
    val system: String,
    val messages: List<AnthropicRequestMessage>,
    val tools: List<AnthropicToolSpec>? = null,
    val tool_choice: Map<String, String>? = null
)

data class AnthropicRequestMessage(
    val role: String,
    val content: Any
)

data class AnthropicToolSpec(
    val name: String,
    val description: String,
    val input_schema: Map<String, Any>
)

data class AnthropicMessageResponse(
    val content: List<AnthropicContentBlock> = emptyList(),
    val stop_reason: String? = null
)

data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: Map<String, Any?>? = null
)

data class AgentFunctionDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>
)

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>
)

data class AgentModelResponse(
    val text: String,
    val toolCalls: List<AgentToolCall> = emptyList(),
    val apiFormat: ApiFormat,
    val nativeToolCallingUsed: Boolean
)

private class ProviderHttpException(
    val statusCode: Int,
    val details: String,
    cause: Throwable
) : IllegalStateException(
    "AI provider error HTTP $statusCode" + if (details.isBlank()) "." else ": $details",
    cause
)

class AIClient(
    private val config: AIProviderConfig,
    private val apiKey: String
) {
    private val openAIService: OpenAICompatibleService
    private val anthropicService: AnthropicCompatibleService
    private val moshi: Moshi
    private val anyMapAdapter: com.squareup.moshi.JsonAdapter<Map<String, Any?>>

    init {
        moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        @Suppress("UNCHECKED_CAST")
        anyMapAdapter = moshi.adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )

        val logging = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            redactHeader("x-api-key")
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

        openAIService = retrofit.create(OpenAICompatibleService::class.java)
        anthropicService = retrofit.create(AnthropicCompatibleService::class.java)
    }

    suspend fun analyze(
        prompt: String,
        attachments: List<AgentAttachment> = emptyList(),
        toolDefinitions: List<AgentFunctionDefinition> = emptyList(),
        requireTool: Boolean = false
    ): AgentModelResponse {
        val cleanBaseUrl = config.baseUrl.trim().trimEnd('/')
        val cleanModel = config.modelName.trim()
        val cleanApiKey = apiKey.trim()

        require(cleanBaseUrl.isNotBlank()) { "AI Base URL is missing." }
        require(cleanModel.isNotBlank()) { "AI model name is missing." }
        require(cleanApiKey.isNotBlank()) { "AI API key is missing." }

        return when (resolveApiFormat(cleanBaseUrl)) {
            ApiFormat.ANTHROPIC -> analyzeAnthropic(
                baseUrl = cleanBaseUrl,
                model = cleanModel,
                key = cleanApiKey,
                prompt = prompt,
                attachments = attachments,
                toolDefinitions = toolDefinitions,
                requireTool = requireTool
            )

            ApiFormat.LEGACY_TEXT -> analyzeOpenAICompatible(
                baseUrl = cleanBaseUrl,
                model = cleanModel,
                key = cleanApiKey,
                prompt = prompt,
                attachments = attachments,
                toolDefinitions = emptyList(),
                requireTool = false,
                responseFormat = ApiFormat.LEGACY_TEXT
            )

            ApiFormat.OPENAI_COMPATIBLE,
            ApiFormat.AUTO -> {
                try {
                    analyzeOpenAICompatible(
                        baseUrl = cleanBaseUrl,
                        model = cleanModel,
                        key = cleanApiKey,
                        prompt = prompt,
                        attachments = attachments,
                        toolDefinitions = toolDefinitions,
                        requireTool = requireTool,
                        responseFormat = ApiFormat.OPENAI_COMPATIBLE
                    )
                } catch (error: ProviderHttpException) {
                    val canFallback = config.apiFormat == ApiFormat.AUTO &&
                        toolDefinitions.isNotEmpty() &&
                        looksLikeNativeToolUnsupported(error)
                    if (!canFallback) throw error

                    analyzeOpenAICompatible(
                        baseUrl = cleanBaseUrl,
                        model = cleanModel,
                        key = cleanApiKey,
                        prompt = prompt,
                        attachments = attachments,
                        toolDefinitions = emptyList(),
                        requireTool = false,
                        responseFormat = ApiFormat.LEGACY_TEXT
                    )
                }
            }
        }
    }

    private suspend fun analyzeOpenAICompatible(
        baseUrl: String,
        model: String,
        key: String,
        prompt: String,
        attachments: List<AgentAttachment>,
        toolDefinitions: List<AgentFunctionDefinition>,
        requireTool: Boolean,
        responseFormat: ApiFormat
    ): AgentModelResponse {
        val url = openAIChatUrl(baseUrl)
        val headers = mutableMapOf(
            "Authorization" to "Bearer $key",
            "Content-Type" to "application/json"
        )
        headers.putAll(config.customHeaders)

        val nativeTools = toolDefinitions.isNotEmpty()
        val tools = if (nativeTools) {
            toolDefinitions.map { definition ->
                OpenAIToolSpec(
                    function = OpenAIFunctionDefinition(
                        name = definition.name,
                        description = definition.description,
                        parameters = definition.inputSchema
                    )
                )
            }
        } else {
            null
        }

        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatRequestMessage(
                    role = "system",
                    content = "You are a senior autonomous software engineering AI agent. When repository tools are available, call them instead of merely saying that you will use them."
                ),
                ChatRequestMessage(
                    role = "user",
                    content = buildOpenAIUserContent(prompt, attachments)
                )
            ),
            max_tokens = if (config.reasoningModeEnabled) null else config.maxOutputTokens,
            temperature = if (config.reasoningModeEnabled) null else config.temperature,
            reasoning_effort = reasoningEffortForOpenAICompatible(baseUrl),
            tools = tools,
            tool_choice = if (nativeTools) {
                if (requireTool) "required" else "auto"
            } else {
                null
            }
        )

        val response = requestWithRetry {
            openAIService.createCompletion(url, headers, request)
        }
        val message = response.choices.firstOrNull()?.message
            ?: throw IllegalStateException("AI provider returned no response choice.")

        val text = extractText(message.content)
        val calls = message.tool_calls.orEmpty().mapNotNull { call ->
            val function = call.function ?: return@mapNotNull null
            val name = function.name.trim()
            if (name.isBlank()) return@mapNotNull null
            AgentToolCall(
                id = call.id?.ifBlank { null } ?: "openai-${System.nanoTime()}",
                name = name,
                arguments = parseArguments(function.arguments)
            )
        }

        if (text.isBlank() && calls.isEmpty()) {
            throw IllegalStateException("AI provider returned an empty response.")
        }

        return AgentModelResponse(
            text = text,
            toolCalls = calls,
            apiFormat = responseFormat,
            nativeToolCallingUsed = nativeTools && calls.isNotEmpty()
        )
    }

    private suspend fun analyzeAnthropic(
        baseUrl: String,
        model: String,
        key: String,
        prompt: String,
        attachments: List<AgentAttachment>,
        toolDefinitions: List<AgentFunctionDefinition>,
        requireTool: Boolean
    ): AgentModelResponse {
        val url = anthropicMessagesUrl(baseUrl)
        val headers = mutableMapOf(
            "x-api-key" to key,
            "anthropic-version" to "2023-06-01",
            "Content-Type" to "application/json"
        )
        headers.putAll(config.customHeaders)

        val nativeTools = toolDefinitions.isNotEmpty()
        val request = AnthropicMessageRequest(
            model = model,
            max_tokens = config.maxOutputTokens.coerceAtLeast(1024),
            system = "You are a senior autonomous software engineering AI agent. When repository tools are available, use tool calls directly instead of narrating that you will use them.",
            messages = listOf(
                AnthropicRequestMessage(
                    role = "user",
                    content = buildAnthropicUserContent(prompt, attachments)
                )
            ),
            tools = if (nativeTools) {
                toolDefinitions.map { definition ->
                    AnthropicToolSpec(
                        name = definition.name,
                        description = definition.description,
                        input_schema = definition.inputSchema
                    )
                }
            } else {
                null
            },
            tool_choice = if (nativeTools) {
                mapOf("type" to if (requireTool) "any" else "auto")
            } else {
                null
            }
        )

        val response = requestWithRetry {
            anthropicService.createMessage(url, headers, request)
        }

        val text = response.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")
            .trim()

        val calls = response.content
            .filter { it.type == "tool_use" }
            .mapNotNull { block ->
                val name = block.name?.trim().orEmpty()
                if (name.isBlank()) return@mapNotNull null
                AgentToolCall(
                    id = block.id?.ifBlank { null } ?: "anthropic-${System.nanoTime()}",
                    name = name,
                    arguments = block.input.orEmpty()
                )
            }

        if (text.isBlank() && calls.isEmpty()) {
            throw IllegalStateException("Anthropic provider returned an empty response.")
        }

        return AgentModelResponse(
            text = text,
            toolCalls = calls,
            apiFormat = ApiFormat.ANTHROPIC,
            nativeToolCallingUsed = calls.isNotEmpty()
        )
    }

    private fun resolveApiFormat(baseUrl: String): ApiFormat {
        if (config.apiFormat != ApiFormat.AUTO) return config.apiFormat
        val normalized = baseUrl.lowercase()
        return if (normalized.contains("anthropic.com") || normalized.endsWith("/messages")) {
            ApiFormat.ANTHROPIC
        } else {
            ApiFormat.OPENAI_COMPATIBLE
        }
    }

    private fun openAIChatUrl(baseUrl: String): String {
        val normalized = baseUrl.trimEnd('/')
        return when {
            normalized.endsWith("/chat/completions") -> normalized
            else -> "$normalized/chat/completions"
        }
    }

    private fun anthropicMessagesUrl(baseUrl: String): String {
        val normalized = baseUrl.trimEnd('/')
        return when {
            normalized.endsWith("/messages") -> normalized
            normalized.endsWith("/v1") -> "$normalized/messages"
            else -> "$normalized/v1/messages"
        }
    }

    private fun reasoningEffortForOpenAICompatible(baseUrl: String): String? {
        if (!config.reasoningModeEnabled) return null
        val isXai = baseUrl.lowercase().contains("api.x.ai")
        if (!isXai) return null

        return when (config.reasoningLevel) {
            ReasoningLevel.LOW -> "low"
            ReasoningLevel.MEDIUM -> "medium"
            ReasoningLevel.HIGH,
            ReasoningLevel.MAXIMUM -> "high"
            ReasoningLevel.AUTO -> null
        }
    }

    private fun buildOpenAIUserContent(
        prompt: String,
        attachments: List<AgentAttachment>
    ): Any {
        val imageParts = attachments.mapNotNull { attachment ->
            attachment.dataUrl?.let { dataUrl ->
                mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf("url" to dataUrl)
                )
            }
        }

        return if (imageParts.isEmpty()) {
            prompt
        } else {
            listOf(mapOf("type" to "text", "text" to prompt)) + imageParts
        }
    }

    private fun buildAnthropicUserContent(
        prompt: String,
        attachments: List<AgentAttachment>
    ): Any {
        val blocks = mutableListOf<Map<String, Any>>()
        blocks += mapOf("type" to "text", "text" to prompt)

        attachments.forEach { attachment ->
            val dataUrl = attachment.dataUrl ?: return@forEach
            val commaIndex = dataUrl.indexOf(',')
            if (commaIndex <= 0) return@forEach
            val metadata = dataUrl.substring(0, commaIndex)
            val data = dataUrl.substring(commaIndex + 1)
            val mediaType = metadata
                .substringAfter("data:", attachment.mimeType)
                .substringBefore(';')
                .ifBlank { attachment.mimeType }

            blocks += mapOf(
                "type" to "image",
                "source" to mapOf(
                    "type" to "base64",
                    "media_type" to mediaType,
                    "data" to data
                )
            )
        }

        return if (blocks.size == 1) prompt else blocks
    }

    private fun extractText(content: Any?): String {
        return when (content) {
            null -> ""
            is String -> content.trim()
            is List<*> -> content.mapNotNull { part ->
                when (part) {
                    is String -> part
                    is Map<*, *> -> {
                        part["text"]?.toString()
                            ?: part["content"]?.toString()
                    }
                    else -> null
                }
            }.joinToString("\n").trim()
            is Map<*, *> -> content["text"]?.toString()?.trim().orEmpty()
            else -> content.toString().trim()
        }
    }

    private fun parseArguments(raw: Any?): Map<String, Any?> {
        return when (raw) {
            null -> emptyMap()
            is Map<*, *> -> raw.entries.associate { entry ->
                entry.key.toString() to entry.value
            }
            is String -> {
                if (raw.isBlank()) emptyMap()
                else runCatching { anyMapAdapter.fromJson(raw) }
                    .getOrNull()
                    .orEmpty()
            }
            else -> emptyMap()
        }
    }

    private fun looksLikeNativeToolUnsupported(error: ProviderHttpException): Boolean {
        if (error.statusCode !in setOf(400, 404, 405, 422)) return false
        val text = error.details.lowercase()
        return listOf(
            "tool",
            "function",
            "tool_choice",
            "tool_calls",
            "unknown field",
            "unsupported parameter",
            "additional properties"
        ).any { text.contains(it) }
    }

    private suspend fun <T> requestWithRetry(block: suspend () -> T): T {
        val maxAttempts = 5
        var lastError: Exception? = null

        repeat(maxAttempts) { attemptIndex ->
            try {
                return block()
            } catch (error: HttpException) {
                lastError = error
                val code = error.code()
                val retryable = code == 408 || code == 425 || code == 429 || code in 500..599
                val details = runCatching { error.response()?.errorBody()?.string() }
                    .getOrNull()
                    ?.take(1_000)
                    .orEmpty()

                if (!retryable || attemptIndex == maxAttempts - 1) {
                    throw ProviderHttpException(code, details, error)
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
