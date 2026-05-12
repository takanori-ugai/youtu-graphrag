package com.youtu.graphrag.shared.constructor

import com.youtu.graphrag.shared.config.ConfigManager
import com.youtu.graphrag.shared.graph.GraphRelationship

class KTBuilder(
    private val datasetName: String,
    private val schemaPath: String,
    private val mode: String,
    private val config: ConfigManager,
) {
    fun buildKnowledgeGraph(corpusPath: String): List<GraphRelationship> {
        // Phase scaffold only: full constructor parity will be implemented in subsequent phases.
        if (datasetName.isBlank() || schemaPath.isBlank() || corpusPath.isBlank() || mode.isBlank()) {
            return emptyList()
        }
        config.createOutputDirectories()
        return emptyList()
    }
}
