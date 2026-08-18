package com.example.agent

import java.util.ArrayDeque

/**
 * Keeps the agent prompt bounded while preserving the most useful repository evidence.
 * The model is intentionally asked to re-read a file when older details were compacted.
 */
class AgentContextManager(
    private val basePrompt: String,
    snapshot: AgentContextSnapshot? = null,
    private val maxContextChars: Int = 180_000,
    private val maxMemoryEntryChars: Int = 55_000,
    private val maxRecentNotes: Int = 8
) {
    private val memory = linkedMapOf<String, String>()
    private val recentNotes = ArrayDeque<String>()

    init {
        snapshot?.memoryEntries?.forEach { entry ->
            memory[entry.key] = entry.content
        }
        snapshot?.recentNotes?.forEach { note ->
            recentNotes.addLast(note)
        }
        trimRecentNotes()
    }

    fun rememberToolResult(
        toolName: String,
        arguments: Map<String, Any?>,
        result: String
    ) {
        val path = arguments["path"]?.toString()?.trim().orEmpty()
        val compactResult = compact(result, maxMemoryEntryChars)

        when (toolName) {
            "readFile" -> {
                val key = "file:${path.ifBlank { "unknown" }}"
                putNewest(
                    key,
                    buildString {
                        appendLine("Latest readFile evidence for ${path.ifBlank { "unknown path" }}:")
                        append(compactResult)
                    }
                )
            }

            "updateFile" -> {
                val key = "change:${path.ifBlank { "unknown" }}"
                putNewest(
                    key,
                    "Latest staged-change status for ${path.ifBlank { "unknown path" }}:\n" +
                        compact(result, 6_000)
                )
            }

            "listFiles" -> {
                val key = "tree:${path.ifBlank { "/" }}"
                putNewest(key, compact(result, 30_000))
            }

            "searchCode" -> {
                rememberNote(
                    "searchCode(${arguments["query"]?.toString().orEmpty()}):\n" +
                        compact(result, 18_000)
                )
            }

            else -> rememberNote(
                "$toolName result:\n" + compact(result, 15_000)
            )
        }
    }

    fun rememberAssistantText(text: String) {
        if (text.isBlank()) return
        rememberNote("Assistant note:\n" + compact(text, 7_000))
    }

    fun rememberInstruction(text: String) {
        if (text.isBlank()) return
        rememberNote("Agent control note:\n" + compact(text, 5_000))
    }

    fun buildPrompt(extraInstruction: String? = null): String {
        val fixedHeader = buildString {
            append(basePrompt.trim())
            appendLine()
            appendLine()
            appendLine("--- COMPACT WORKING MEMORY ---")
            appendLine(
                "Older tool output may have been compacted to keep requests reliable. " +
                    "If exact repository content is missing or uncertain, call readFile/searchCode again instead of guessing."
            )
        }

        val remainingBudget = (maxContextChars - fixedHeader.length - 2_000).coerceAtLeast(20_000)
        val selectedMemory = mutableListOf<String>()
        var used = 0

        // Newest entries are preferred. Re-reading a file replaces its older copy.
        memory.entries.toList().asReversed().forEach { (key, value) ->
            val block = "[$key]\n$value"
            if (used + block.length <= remainingBudget) {
                selectedMemory += block
                used += block.length
            }
        }

        val selectedNotes = mutableListOf<String>()
        recentNotes.toList().asReversed().forEach { note ->
            val block = "[recent]\n$note"
            if (used + block.length <= remainingBudget) {
                selectedNotes += block
                used += block.length
            }
        }

        return buildString {
            append(fixedHeader)
            appendLine()
            selectedMemory.asReversed().forEach {
                appendLine(it)
                appendLine()
            }
            selectedNotes.asReversed().forEach {
                appendLine(it)
                appendLine()
            }
            appendLine("--- END COMPACT WORKING MEMORY ---")
            if (!extraInstruction.isNullOrBlank()) {
                appendLine()
                appendLine("--- CURRENT CONTROL INSTRUCTION ---")
                appendLine(extraInstruction.trim())
                appendLine("--- END CURRENT CONTROL INSTRUCTION ---")
            }
        }.take(maxContextChars)
    }

    fun snapshot(): AgentContextSnapshot = AgentContextSnapshot(
        memoryEntries = memory.map { (key, content) -> AgentMemoryEntry(key, content) },
        recentNotes = recentNotes.toList()
    )

    private fun putNewest(key: String, value: String) {
        memory.remove(key)
        memory[key] = compact(value, maxMemoryEntryChars)
    }

    private fun rememberNote(note: String) {
        recentNotes.addLast(compact(note, 18_000))
        trimRecentNotes()
    }

    private fun trimRecentNotes() {
        while (recentNotes.size > maxRecentNotes) {
            recentNotes.removeFirst()
        }
    }

    private fun compact(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val marker = "\n\n[...context compacted; re-read the relevant file/range for exact omitted content...]\n\n"
        val available = (limit - marker.length).coerceAtLeast(2_000)
        val head = (available * 3) / 4
        val tail = available - head
        return text.take(head) + marker + text.takeLast(tail)
    }
}

data class AgentMemoryEntry(
    val key: String,
    val content: String
)

data class AgentContextSnapshot(
    val memoryEntries: List<AgentMemoryEntry> = emptyList(),
    val recentNotes: List<String> = emptyList()
)
