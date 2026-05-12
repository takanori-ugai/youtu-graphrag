package com.youtu.graphrag.shared.ingest

interface DocumentParser {
    fun parseFile(
        path: String,
        extension: String,
    ): String
}

class NoopDocumentParser : DocumentParser {
    override fun parseFile(
        path: String,
        extension: String,
    ): String {
        if (path.isBlank() || extension.isBlank()) {
            return ""
        }
        return ""
    }
}
