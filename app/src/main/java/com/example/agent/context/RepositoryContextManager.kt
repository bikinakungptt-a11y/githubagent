package com.example.agent.context

import com.example.domain.model.RepositoryModel

class RepositoryContextManager(val repositoryModel: RepositoryModel) {
    private val activeFiles = mutableMapOf<String, String>()

    fun addFileToContext(path: String, content: String) {
        activeFiles[path] = content
    }

    fun removeFileFromContext(path: String) {
        activeFiles.remove(path)
    }

    fun getContextString(): String {
        val builder = StringBuilder()
        builder.append("Repository: ${repositoryModel.name}\n")
        builder.append("Branch: ${repositoryModel.defaultBranch}\n\n")
        
        if (activeFiles.isEmpty()) {
            builder.append("No files currently in context.\n")
        } else {
            builder.append("Files in context:\n")
            for ((path, content) in activeFiles) {
                builder.append("--- $path ---\n")
                builder.append(content)
                builder.append("\n\n")
            }
        }
        return builder.toString()
    }
}
