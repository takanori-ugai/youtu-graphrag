package com.youtu.graphrag.shared.decomposer

import com.youtu.graphrag.shared.config.ConfigManager
import com.youtu.graphrag.shared.llm.LlmClient
import com.youtu.graphrag.shared.llm.LlmClientFactory
import com.youtu.graphrag.shared.llm.LlmOutputParser
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

class GraphQ(
    private val datasetName: String,
    private val config: ConfigManager,
    private val llmClient: LlmClient = LlmClientFactory.fromEnvironment(),
) {
    private val logger = KotlinLogging.logger {}

    fun decompose(
        question: String,
        schemaPath: String,
    ): Map<String, Any> {
        val normalizedQuestion = question.trim()
        val schema = readSchema(schemaPath)
        val prompt = promptFormat(schema, normalizedQuestion)
        val llmResponse = llmClient.complete(prompt)
        val parsed = parseDecomposition(llmResponse)
        val subQuestions = parsed?.first?.ifEmpty { null } ?: buildSubQuestions(normalizedQuestion)
        val involvedTypes = parsed?.second ?: emptyInvolvedTypes()

        return mapOf(
            "dataset" to datasetName,
            "schema_path" to schemaPath,
            "sub_questions" to subQuestions,
            "involved_types" to involvedTypes,
            "mode" to config.triggers.mode,
        )
    }

    private fun readSchema(schemaPath: String): String {
        return runCatching {
            val path = Path.of(schemaPath)
            if (!Files.exists(path)) {
                return@runCatching ""
            }
            Files.readString(path)
        }.getOrElse { error ->
            logger.warn(error) { "Failed to read schema at '$schemaPath'" }
            ""
        }
    }

    private fun promptFormat(
        schema: String,
        question: String,
    ): String =
        runCatching {
            val template =
                config.getPrompt(
                    category = "decomposition",
                    promptType = decompositionPromptType(),
                )
            template
                .replace("{ontology}", schema)
                .replace("{question}", question)
        }.getOrElse { error ->
            logger.warn(error) { "Falling back to inline decomposition prompt template" }
            """
            You are a professional question decomposition expert specializing in multi-hop reasoning.
            Given the following ontology and question, return a concise JSON object:
            {
              "sub_questions": [{"sub-question": "..."}],
              "involved_types": {"nodes": [], "relations": [], "attributes": []}
            }
            Ontology:
            $schema
            Question: $question
            """.trimIndent()
        }

    private fun decompositionPromptType(): String =
        if (datasetName == "anony_chs") {
            "novel"
        } else {
            "general"
        }

    private fun parseDecomposition(response: String): Pair<List<Map<String, String>>, Map<String, List<String>>>? {
        val parsed = LlmOutputParser.parseJsonValue(response) ?: return null
        return when (parsed) {
            is List<*> -> {
                val subQuestions = normalizeSubQuestions(parsed)
                if (subQuestions.isEmpty()) {
                    null
                } else {
                    subQuestions to emptyInvolvedTypes()
                }
            }

            is Map<*, *> -> {
                val normalizedMap =
                    parsed.entries.associate { (key, value) ->
                        key.toString() to value
                    }
                val subQuestions = normalizeSubQuestions(normalizedMap["sub_questions"])
                if (subQuestions.isEmpty()) {
                    null
                } else {
                    val involvedTypes = normalizeInvolvedTypes(normalizedMap["involved_types"])
                    subQuestions to involvedTypes
                }
            }

            else -> {
                null
            }
        }
    }

    private fun normalizeSubQuestions(value: Any?): List<Map<String, String>> {
        if (value !is List<*>) {
            return emptyList()
        }

        return value.mapNotNull { item ->
            val text =
                when (item) {
                    is Map<*, *> -> {
                        item["sub-question"]?.toString()
                            ?: item["sub_question"]?.toString()
                    }

                    is String -> {
                        item
                    }

                    else -> {
                        null
                    }
                }?.trim()

            if (text.isNullOrBlank()) {
                null
            } else {
                mapOf("sub-question" to text)
            }
        }
    }

    private fun normalizeInvolvedTypes(value: Any?): Map<String, List<String>> {
        if (value !is Map<*, *>) {
            return emptyInvolvedTypes()
        }

        return mapOf(
            "nodes" to toStringList(value["nodes"]),
            "relations" to toStringList(value["relations"]),
            "attributes" to toStringList(value["attributes"]),
        )
    }

    private fun toStringList(value: Any?): List<String> =
        when (value) {
            is List<*> -> {
                value
                    .mapNotNull { it?.toString()?.trim() }
                    .filter { it.isNotBlank() }
            }

            is String -> {
                listOf(value).map { it.trim() }.filter { it.isNotBlank() }
            }

            else -> {
                emptyList()
            }
        }

    private fun emptyInvolvedTypes(): Map<String, List<String>> =
        mapOf(
            "nodes" to emptyList(),
            "relations" to emptyList(),
            "attributes" to emptyList(),
        )

    private fun buildSubQuestions(question: String): List<Map<String, String>> {
        if (question.isBlank()) {
            return listOf(mapOf("sub-question" to question))
        }

        val splitCandidates =
            question
                .split(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE))
                .map { candidate -> candidate.trim() }
                .filter { candidate -> candidate.isNotBlank() }

        val shouldSplit =
            splitCandidates.size in 2..3 &&
                splitCandidates.all { candidate -> candidate.length >= 8 }

        if (!shouldSplit) {
            return listOf(mapOf("sub-question" to question))
        }

        return splitCandidates.map { candidate ->
            val normalized =
                if (candidate.endsWith("?")) {
                    candidate
                } else {
                    "$candidate?"
                }
            mapOf("sub-question" to normalized)
        }
    }
}
