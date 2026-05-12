package com.youtu.graphrag.shared.decomposer

import com.youtu.graphrag.shared.config.ConfigManager

class GraphQ(
    private val datasetName: String,
    private val config: ConfigManager,
) {
    fun decompose(
        question: String,
        schemaPath: String,
    ): Map<String, Any> {
        val subQuestion = mapOf("sub-question" to question)
        return mapOf(
            "dataset" to datasetName,
            "schema_path" to schemaPath,
            "sub_questions" to listOf(subQuestion),
            "involved_types" to
                mapOf(
                    "nodes" to emptyList<String>(),
                    "relations" to emptyList<String>(),
                    "attributes" to emptyList<String>(),
                ),
            "mode" to config.triggers.mode,
        )
    }
}
