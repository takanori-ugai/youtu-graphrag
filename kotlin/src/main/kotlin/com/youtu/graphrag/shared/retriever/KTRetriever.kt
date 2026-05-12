package com.youtu.graphrag.shared.retriever

import com.youtu.graphrag.shared.config.ConfigManager

class KTRetriever(
    private val datasetName: String,
    private val graphPath: String,
    private val recallPaths: Int,
    private val schemaPath: String,
    private val topK: Int,
    private val mode: String,
    private val config: ConfigManager,
) {
    fun buildIndices() {
        if (datasetName.isBlank() || graphPath.isBlank() || schemaPath.isBlank()) {
            return
        }
        config.createOutputDirectories()
    }

    fun processRetrievalResults(
        question: String,
        involvedTypes: Map<String, List<String>> = emptyMap(),
    ): Map<String, Any> =
        mapOf(
            "question" to question,
            "triples" to emptyList<String>(),
            "chunk_ids" to emptyList<String>(),
            "chunk_contents" to emptyMap<String, String>(),
            "top_k" to topK,
            "recall_paths" to recallPaths,
            "involved_types" to involvedTypes,
            "mode" to mode,
        )
}
