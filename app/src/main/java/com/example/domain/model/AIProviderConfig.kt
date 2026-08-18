package com.example.domain.model

enum class ReasoningLevel {
    AUTO,
    LOW,
    MEDIUM,
    HIGH,
    MAXIMUM
}

enum class ApiFormat {
    AUTO,
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    LEGACY_TEXT
}

data class ModelCapabilities(
    val supportsReasoning: Boolean,
    val supportedReasoningLevels: List<ReasoningLevel>,
    val supportsToolCalling: Boolean,
    val supportsStreaming: Boolean,
    val contextWindow: Int,
    val maxOutputTokens: Int
)

data class AIProviderConfig(
    val baseUrl: String = "",
    val modelName: String = "",
    val apiFormat: ApiFormat = ApiFormat.AUTO,
    val maxOutputTokens: Int = 4096,
    val temperature: Float = 0.7f,
    val reasoningModeEnabled: Boolean = true,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.HIGH,
    val customHeaders: Map<String, String> = emptyMap()
)
