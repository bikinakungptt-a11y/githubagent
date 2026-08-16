package com.example.agent.patch

data class FilePatch(
    val path: String,
    val originalContent: String?,
    val modifiedContent: String,
    val explanation: String
)

class PatchManager {
    private val patches = mutableListOf<FilePatch>()

    fun addPatch(patch: FilePatch) {
        patches.removeIf { it.path == patch.path }
        patches.add(patch)
    }

    fun getPatches(): List<FilePatch> = patches.toList()
    
    fun clearPatches() {
        patches.clear()
    }
}
