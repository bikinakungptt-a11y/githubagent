package com.example.agent

import com.example.domain.model.AIProviderConfig
import com.example.domain.model.ApiFormat
import com.example.domain.model.ReasoningLevel
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatRequestMessage>,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val reasoning_effort: String? = null,
    val tools: List<OpenAIToolSpec>? = null,
    val tool_choice: Any? = null,
    val stream: Boolean? = null
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
    val tool_choice: Map<String, String>? = null,
    val stream: Boolean? = null
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
    val nativeToolCallingUsed: Boolean,
    val streamed: Boolean = false,
    val streamChunks: Int = 0
)

data class StreamProgress(
    val chunks: Int,
    val receivedCharacters: Int
)

class AIProviderException(
    val statusCode: Int? = null,
    val retryable: Boolean,
    details: String,
    cause: Throwable? = null
) : IllegalStateException(
    buildString {
        append("AI provider error")
        statusCode?.let { append(" HTTP $it") }
        if (details.isNotBlank()) append(": ${details.take(1_000)}")
    },
    cause
)

class AIClient(
    private val config: AIProviderConfig,
    private val apiKey: String
) {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val anyMapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val chatRequestAdapter = moshi.adapter(ChatCompletionRequest::class.java)
    private val chatResponseAdapter = moshi.adapter(ChatCompletionResponse::class.java)
    private val anthropicRequestAdapter = moshi.adapter(AnthropicMessageRequest::class.java)
    private val anthropicResponseAdapter = moshi.adapter(AnthropicMessageResponse::class.java)

    private val okHttpClient: OkHttpClient

    init {
        val logging = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            redactHeader("x-api-key")
            level = HttpLoggingInterceptor.Level.BASIC
        }

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            // Streaming responses are allowed to run for a long time, but a completely idle
            // socket is still bounded. Server-side 524/5xx responses are retried separately.
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(logging)
            .build()
    }

    suspend fun analyze(
        prompt: String,
        attachments: List<AgentAttachment> = emptyList(),
        toolDefinitions: List<AgentFunctionDefinition> = emptyList(),
        requireTool: Boolean = false,
        onStreamProgress: (StreamProgress) -> Unit = {}
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
                requireTool = requireTool,
                onStreamProgress = onStreamProgress
            )

            ApiFormat.OPENAI_COMPATIBLE,
            ApiFormat.LEGACY_TEXT -> {
                try {
                    analyzeOpenAICompatible(
                        baseUrl = cleanBaseUrl,
                        model = cleanModel,
                        key = cleanApiKey,
                        prompt = prompt,
                        attachments = attachments,
                        toolDefinitions = toolDefinitions,
                        requireTool = requireTool,
                        responseFormat = ApiFormat.OPENAI_COMPATIBLE,
                        onStreamProgress = onStreamProgress
                    )
                } catch (error: AIProviderException) {
                    val canFallbackToLegacy = toolDefinitions.isNotEmpty() &&
                        looksLikeNativeToolUnsupported(error)
                    if (!canFallbackToLegacy) throw error

                    analyzeOpenAICompatible(
                        baseUrl = cleanBaseUrl,
                        model = cleanModel,
                        key = cleanApiKey,
                        prompt = prompt,
                        attachments = attachments,
                        toolDefinitions = emptyList(),
                        requireTool = false,
                        responseFormat = ApiFormat.LEGACY_TEXT,
                        onStreamProgress = onStreamProgress
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
        responseFormat: ApiFormat,
        onStreamProgress: (StreamProgress) -> Unit
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

        val baseRequest = ChatCompletionRequest(
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

        return try {
            requestWithRetry {
                streamOpenAICompatible(
                    url = url,
                    headers = headers,
                    request = baseRequest.copy(stream = true),
                    responseFormat = responseFormat,
                    nativeTools = nativeTools,
                    onStreamProgress = onStreamProgress
                )
            }
        } catch (error: AIProviderException) {
            if (!looksLikeStreamingUnsupported(error)) throw error
            requestWithRetry {
                nonStreamingOpenAICompatible(
                    url = url,
                    headers = headers,
                    request = baseRequest.copy(stream = false),
                    responseFormat = responseFormat,
                    nativeTools = nativeTools
                )
            }
        }
    }

    private suspend fun streamOpenAICompatible(
        url: String,
        headers: Map<String, String>,
        request: ChatCompletionRequest,
        responseFormat: ApiFormat,
        nativeTools: Boolean,
        onStreamProgress: (StreamProgress) -> Unit
    ): AgentModelResponse = withContext(Dispatchers.IO) {
        val httpRequest = buildJsonRequest(
            url = url,
            headers = headers + ("Accept" to "text/event-stream"),
            json = chatRequestAdapter.toJson(request)
        )

        okHttpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val details = response.body?.string().orEmpty()
                throw providerStatusException(response.code, details)
            }

            val body = response.body
                ?: throw AIProviderException(null, true, "Provider returned an empty streaming body.")
            val source = body.source()
            val text = StringBuilder()
            val toolBuffers = linkedMapOf<Int, StreamingToolBuffer>()
            var chunks = 0
            var receivedCharacters = 0

            while (!source.exhausted()) {
                val rawLine = source.readUtf8Line() ?: break
                val line = rawLine.trim()
                if (line.isBlank() || line.startsWith("event:")) continue

                val data = when {
                    line.startsWith("data:") -> line.removePrefix("data:").trim()
                    line.startsWith("{") -> line
                    else -> continue
                }
                if (data == "[DONE]") break

                val event = runCatching { anyMapAdapter.fromJson(data) }.getOrNull() ?: continue
                val added = consumeOpenAIEvent(event, text, toolBuffers)
                chunks++
                receivedCharacters += added

                if (chunks == 1 || chunks % 20 == 0) {
                    onStreamProgress(StreamProgress(chunks, receivedCharacters))
                }
            }

            val calls = toolBuffers.entries
                .sortedBy { it.key }
                .mapNotNull { (_, buffer) -> buffer.toToolCall() }

            val finalText = text.toString().trim()
            if (finalText.isBlank() && calls.isEmpty()) {
                throw AIProviderException(
                    statusCode = null,
                    retryable = true,
                    details = "Streaming response ended without text or tool calls."
                )
            }

            AgentModelResponse(
                text = finalText,
                toolCalls = calls,
                apiFormat = responseFormat,
                nativeToolCallingUsed = nativeTools && calls.isNotEmpty(),
                streamed = true,
                streamChunks = chunks
            )
        }
    }

    private suspend fun nonStreamingOpenAICompatible(
        url: String,
        headers: Map<String, String>,
        request: ChatCompletionRequest,
        responseFormat: ApiFormat,
        nativeTools: Boolean
    ): AgentModelResponse = withContext(Dispatchers.IO) {
        val httpRequest = buildJsonRequest(
            url = url,
            headers = headers,
            json = chatRequestAdapter.toJson(request)
        )

        okHttpClient.newCall(httpRequest).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw providerStatusException(response.code, bodyText)
            }

            val decoded = chatResponseAdapter.fromJson(bodyText)
                ?: throw AIProviderException(null, false, "AI provider returned invalid JSON.")
            val message = decoded.choices.firstOrNull()?.message
                ?: throw AIProviderException(null, false, "AI provider returned no response choice.")

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
                throw AIProviderException(null, false, "AI provider returned an empty response.")
            }

            AgentModelResponse(
                text = text,
                toolCalls = calls,
                apiFormat = responseFormat,
                nativeToolCallingUsed = nativeTools && calls.isNotEmpty(),
                streamed = false
            )
        }
    }

    private suspend fun analyzeAnthropic(
        baseUrl: String,
        model: String,
        key: String,
        prompt: String,
        attachments: List<AgentAttachment>,
        toolDefinitions: List<AgentFunctionDefinition>,
        requireTool: Boolean,
        onStreamProgress: (StreamProgress) -> Unit
    ): AgentModelResponse {
        val url = anthropicMessagesUrl(baseUrl)
        val headers = mutableMapOf(
            "x-api-key" to key,
            "anthropic-version" to "2023-06-01",
            "Content-Type" to "application/json"
        )
        headers.putAll(config.customHeaders)

        val nativeTools = toolDefinitions.isNotEmpty()
        val baseRequest = AnthropicMessageRequest(
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

        return try {
            requestWithRetry {
                streamAnthropic(
                    url = url,
                    headers = headers + ("Accept" to "text/event-stream"),
                    request = baseRequest.copy(stream = true),
                    nativeTools = nativeTools,
                    onStreamProgress = onStreamProgress
                )
            }
        } catch (error: AIProviderException) {
            if (!looksLikeStreamingUnsupported(error)) throw error
            requestWithRetry {
                nonStreamingAnthropic(
                    url = url,
                    headers = headers,
                    request = baseRequest.copy(stream = false),
                    nativeTools = nativeTools
                )
            }
        }
    }

    private suspend fun streamAnthropic(
        url: String,
        headers: Map<String, String>,
        request: AnthropicMessageRequest,
        nativeTools: Boolean,
        onStreamProgress: (StreamProgress) -> Unit
    ): AgentModelResponse = withContext(Dispatchers.IO) {
        val httpRequest = buildJsonRequest(
            url = url,
            headers = headers,
            json = anthropicRequestAdapter.toJson(request)
        )

        okHttpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw providerStatusException(response.code, response.body?.string().orEmpty())
            }

            val body = response.body
                ?: throw AIProviderException(null, true, "Anthropic returned an empty streaming body.")
            val source = body.source()
            val text = StringBuilder()
            val toolBuffers = linkedMapOf<Int, StreamingToolBuffer>()
            var chunks = 0
            var receivedCharacters = 0

            while (!source.exhausted()) {
                val rawLine = source.readUtf8Line() ?: break
                val line = rawLine.trim()
                if (line.isBlank() || line.startsWith("event:")) continue
                if (!line.startsWith("data:")) continue

                val data = line.removePrefix("data:").trim()
                val event = runCatching { anyMapAdapter.fromJson(data) }.getOrNull() ?: continue
                val added = consumeAnthropicEvent(event, text, toolBuffers)
                chunks++
                receivedCharacters += added

                if (chunks == 1 || chunks % 20 == 0) {
                    onStreamProgress(StreamProgress(chunks, receivedCharacters))
                }
            }

            val calls = toolBuffers.entries
                .sortedBy { it.key }
                .mapNotNull { (_, buffer) -> buffer.toToolCall() }
            val finalText = text.toString().trim()

            if (finalText.isBlank() && calls.isEmpty()) {
                throw AIProviderException(null, true, "Anthropic stream ended without text or tool calls.")
            }

            AgentModelResponse(
                text = finalText,
                toolCalls = calls,
                apiFormat = ApiFormat.ANTHROPIC,
                nativeToolCallingUsed = nativeTools && calls.isNotEmpty(),
                streamed = true,
                streamChunks = chunks
            )
        }
    }

    private suspend fun nonStreamingAnthropic(
        url: String,
        headers: Map<String, String>,
        request: AnthropicMessageRequest,
        nativeTools: Boolean
    ): AgentModelResponse = withContext(Dispatchers.IO) {
        val httpRequest = buildJsonRequest(
            url = url,
            headers = headers,
            json = anthropicRequestAdapter.toJson(request)
        )

        okHttpClient.newCall(httpRequest).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw providerStatusException(response.code, bodyText)
            }

            val decoded = anthropicResponseAdapter.fromJson(bodyText)
                ?: throw AIProviderException(null, false, "Anthropic returned invalid JSON.")
            val text = decoded.content
                .filter { it.type == "text" }
                .mapNotNull { it.text }
                .joinToString("\n")
                .trim()
            val calls = decoded.content
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
                throw AIProviderException(null, false, "Anthropic provider returned an empty response.")
            }

            AgentModelResponse(
                text = text,
                toolCalls = calls,
                apiFormat = ApiFormat.ANTHROPIC,
                nativeToolCallingUsed = nativeTools && calls.isNotEmpty(),
                streamed = false
            )
        }
    }

    private fun consumeOpenAIEvent(
        event: Map<String, Any?>,
        text: StringBuilder,
        toolBuffers: MutableMap<Int, StreamingToolBuffer>
    ): Int {
        var added = 0
        val choices = event["choices"] as? List<*> ?: return 0

        choices.forEach { rawChoice ->
            val choice = rawChoice.asStringMap() ?: return@forEach
            val payload = choice["delta"].asStringMap()
                ?: choice["message"].asStringMap()
                ?: return@forEach

            val content = payload["content"]
            val contentText = when (content) {
                is String -> content
                else -> extractText(content)
            }
            if (contentText.isNotBlank()) {
                text.append(contentText)
                added += contentText.length
            }

            // Count reasoning deltas only as liveness. They are intentionally not surfaced or stored.
            val reasoning = payload["reasoning_content"]?.toString().orEmpty()
            added += reasoning.length

            val calls = payload["tool_calls"] as? List<*> ?: emptyList<Any?>()
            calls.forEachIndexed { fallbackIndex, rawCall ->
                val call = rawCall.asStringMap() ?: return@forEachIndexed
                val index = (call["index"] as? Number)?.toInt() ?: fallbackIndex
                val buffer = toolBuffers.getOrPut(index) { StreamingToolBuffer() }
                call["id"]?.toString()?.takeIf { it.isNotBlank() }?.let { buffer.id = it }

                val function = call["function"].asStringMap()
                val namePart = function?.get("name")?.toString().orEmpty()
                if (namePart.isNotBlank()) {
                    if (buffer.name.isBlank()) buffer.name = namePart
                    else if (!buffer.name.endsWith(namePart)) buffer.name += namePart
                    added += namePart.length
                }
                val argsPart = function?.get("arguments")?.toString().orEmpty()
                if (argsPart.isNotEmpty()) {
                    buffer.arguments.append(argsPart)
                    added += argsPart.length
                }
            }
        }
        return added
    }

    private fun consumeAnthropicEvent(
        event: Map<String, Any?>,
        text: StringBuilder,
        toolBuffers: MutableMap<Int, StreamingToolBuffer>
    ): Int {
        var added = 0
        val eventType = event["type"]?.toString().orEmpty()
        val index = (event["index"] as? Number)?.toInt() ?: 0

        if (eventType == "error") {
            val error = event["error"].asStringMap()
            val message = error?.get("message")?.toString().orEmpty()
            throw AIProviderException(null, true, message.ifBlank { "Anthropic streaming error." })
        }

        if (eventType == "content_block_start") {
            val block = event["content_block"].asStringMap()
            if (block?.get("type")?.toString() == "tool_use") {
                val buffer = toolBuffers.getOrPut(index) { StreamingToolBuffer() }
                buffer.id = block["id"]?.toString().orEmpty()
                buffer.name = block["name"]?.toString().orEmpty()
                val initialInput = block["input"].asStringMap()
                if (!initialInput.isNullOrEmpty()) {
                    val json = anyMapAdapter.toJson(initialInput)
                    buffer.arguments.append(json)
                    added += json.length
                }
            }
        }

        if (eventType == "content_block_delta") {
            val delta = event["delta"].asStringMap() ?: return added
            when (delta["type"]?.toString()) {
                "text_delta" -> {
                    val part = delta["text"]?.toString().orEmpty()
                    text.append(part)
                    added += part.length
                }

                "input_json_delta" -> {
                    val part = delta["partial_json"]?.toString().orEmpty()
                    toolBuffers.getOrPut(index) { StreamingToolBuffer() }.arguments.append(part)
                    added += part.length
                }
            }
        }

        return added
    }

    private fun resolveApiFormat(baseUrl: String): ApiFormat {
        val normalized = baseUrl.lowercase().trimEnd('/')
        return if (normalized.contains("anthropic.com") || normalized.endsWith("/messages")) {
            ApiFormat.ANTHROPIC
        } else {
            // Gateways such as SeekAI, xAI, TokenHarbor and most proxy APIs expose
            // OpenAI-compatible /chat/completions even when the selected model is Claude.
            ApiFormat.OPENAI_COMPATIBLE
        }
    }

    private fun openAIChatUrl(baseUrl: String): String {
        val normalized = baseUrl.trimEnd('/')
        return if (normalized.endsWith("/chat/completions")) {
            normalized
        } else {
            "$normalized/chat/completions"
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
                    is Map<*, *> -> part["text"]?.toString() ?: part["content"]?.toString()
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

    private fun buildJsonRequest(
        url: String,
        headers: Map<String, String>,
        json: String
    ): Request {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url(url)
            .post(body)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun looksLikeNativeToolUnsupported(error: AIProviderException): Boolean {
        if (error.statusCode !in setOf(400, 404, 405, 422)) return false
        val text = error.message.orEmpty().lowercase()
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

    private fun looksLikeStreamingUnsupported(error: AIProviderException): Boolean {
        if (error.statusCode !in setOf(400, 404, 405, 415, 422)) return false
        val text = error.message.orEmpty().lowercase()
        return listOf(
            "stream",
            "streaming",
            "sse",
            "text/event-stream",
            "unsupported parameter"
        ).any { text.contains(it) }
    }

    private fun providerStatusException(code: Int, details: String): AIProviderException {
        val retryable = code == 408 || code == 425 || code == 429 || code in 500..599
        return AIProviderException(
            statusCode = code,
            retryable = retryable,
            details = details.ifBlank { "error code: $code" }
        )
    }

    private suspend fun <T> requestWithRetry(block: suspend () -> T): T {
        val maxAttempts = 3
        var lastError: Throwable? = null

        repeat(maxAttempts) { attemptIndex ->
            try {
                return block()
            } catch (error: AIProviderException) {
                lastError = error
                if (!error.retryable || attemptIndex == maxAttempts - 1) throw error
                delay(exponentialBackoffMillis(attemptIndex))
            } catch (error: IOException) {
                lastError = error
                if (attemptIndex == maxAttempts - 1) {
                    throw AIProviderException(
                        statusCode = null,
                        retryable = true,
                        details = "connection failed after $maxAttempts attempts: " +
                            (error.message ?: error.javaClass.simpleName),
                        cause = error
                    )
                }
                delay(exponentialBackoffMillis(attemptIndex))
            }
        }

        throw AIProviderException(
            statusCode = null,
            retryable = true,
            details = "request failed after $maxAttempts attempts: ${lastError?.message ?: "unknown error"}",
            cause = lastError
        )
    }

    private fun exponentialBackoffMillis(attemptIndex: Int): Long {
        val multiplier = 1L shl attemptIndex.coerceIn(0, 4)
        return (2_000L * multiplier).coerceAtMost(30_000L)
    }

    private fun Any?.asStringMap(): Map<String, Any?>? {
        val source = this as? Map<*, *> ?: return null
        return source.entries.associate { it.key.toString() to it.value }
    }

    private inner class StreamingToolBuffer {
        var id: String = ""
        var name: String = ""
        val arguments: StringBuilder = StringBuilder()

        fun toToolCall(): AgentToolCall? {
            val cleanName = name.trim()
            if (cleanName.isBlank()) return null
            return AgentToolCall(
                id = id.ifBlank { "tool-${System.nanoTime()}" },
                name = cleanName,
                arguments = parseArguments(arguments.toString())
            )
        }
    }
}
