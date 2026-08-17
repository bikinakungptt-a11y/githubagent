package com.example.agent

data class AgentAttachment(
    val name: String,
    val mimeType: String,
    val textContent: String? = null,
    val dataUrl: String? = null
)
