package com.youtu.graphrag.shared.retriever

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.youtu.graphrag.shared.config.ConfigManager
import com.youtu.graphrag.shared.graph.GraphRelationship
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

class KTRetriever(
    private val datasetName: String,
    private val graphPath: String,
    private val recallPaths: Int,
    private val schemaPath: String,
    private val topK: Int,
    private val mode: String,
    private val config: ConfigManager,
    private val rootDir: Path = Path.of("."),
) {
    private val logger = KotlinLogging.logger {}
    private val mapper = ObjectMapper().registerKotlinModule()

    private var indexedTriples: List<String> = emptyList()
    private var chunkById: Map<String, String> = emptyMap()

    fun buildIndices() {
        if (datasetName.isBlank() || graphPath.isBlank() || schemaPath.isBlank()) {
            throw IllegalArgumentException("datasetName, graphPath, and schemaPath must be non-blank")
        }

        config.createOutputDirectories()
        indexedTriples = loadTriples(resolvePath(graphPath))
        chunkById = loadChunks(resolvePath("${config.output.chunksDir}/$datasetName.txt"))

        logger.info {
            "Built retrieval indices for '$datasetName' with ${indexedTriples.size} triples and ${chunkById.size} chunks"
        }
    }

    fun processRetrievalResults(
        question: String,
        involvedTypes: Map<String, List<String>> = emptyMap(),
    ): Map<String, Any> {
        val normalizedQuestionKeywords = tokenize(question)

        val rankedTriples =
            rankedItems(
                items = indexedTriples,
                questionKeywords = normalizedQuestionKeywords,
                topLimit = topK,
            )

        val rankedChunkEntries =
            rankedItems(
                items = chunkById.entries.toList(),
                questionKeywords = normalizedQuestionKeywords,
                topLimit = topK,
                textSelector = { entry -> entry.value },
            )

        val chunkIds = rankedChunkEntries.map { it.key }
        val chunkContents = rankedChunkEntries.associate { it.key to it.value }

        return mapOf(
            "question" to question,
            "triples" to rankedTriples,
            "chunk_ids" to chunkIds,
            "chunk_contents" to chunkContents,
            "top_k" to topK,
            "recall_paths" to recallPaths,
            "involved_types" to involvedTypes,
            "mode" to mode,
        )
    }

    private fun loadTriples(graphFile: Path): List<String> {
        if (!graphFile.exists()) {
            return emptyList()
        }

        val relationships =
            runCatching {
                mapper.readValue(graphFile.toFile(), object : TypeReference<List<GraphRelationship>>() {})
            }.getOrElse { error ->
                logger.warn(error) { "Failed to load graph relationships from $graphFile" }
                emptyList()
            }

        return relationships.map { relationship ->
            val source = relationship.startNode.properties["name"].orEmpty()
            val target = relationship.endNode.properties["name"].orEmpty()
            "[\"$source\", \"${relationship.relation}\", \"$target\"]"
        }
    }

    private fun loadChunks(chunkFile: Path): Map<String, String> {
        if (!chunkFile.exists()) {
            return emptyMap()
        }

        val map = linkedMapOf<String, String>()
        chunkFile.readLines().forEach { line ->
            if (!line.startsWith("id:")) {
                return@forEach
            }

            val idPart = line.substringAfter("id:").substringBefore('\t').trim()
            if (idPart.isBlank()) {
                return@forEach
            }

            val chunkContent =
                if ("\tChunk:" in line) {
                    line.substringAfter("\tChunk:").trim()
                } else {
                    line.substringAfter("Chunk:", "").trim()
                }

            map[idPart] = chunkContent
        }

        return map
    }

    private fun tokenize(input: String): Set<String> {
        val tokenRegex = Regex("[A-Za-z0-9_]{2,}")
        return tokenRegex.findAll(input.lowercase()).map { it.value }.toSet()
    }

    private fun <T> rankedItems(
        items: List<T>,
        questionKeywords: Set<String>,
        topLimit: Int,
        textSelector: (T) -> String = { it.toString() },
    ): List<T> {
        if (items.isEmpty()) {
            return emptyList()
        }

        val scored =
            items.mapIndexed { index, item ->
                val text = textSelector(item).lowercase()
                val score = if (questionKeywords.isEmpty()) 0 else questionKeywords.count { keyword -> keyword in text }
                Triple(item, score, index)
            }

        val sorted =
            scored.sortedWith(
                compareByDescending<Triple<T, Int, Int>> { it.second }
                    .thenBy { it.third },
            )

        val selected =
            if (sorted.any { it.second > 0 }) {
                sorted.take(topLimit)
            } else {
                sorted.take(minOf(topLimit, sorted.size))
            }

        return selected.map { it.first }
    }

    private fun resolvePath(path: String): Path {
        val candidate = Path.of(path)
        return if (candidate.isAbsolute) candidate else rootDir.resolve(candidate)
    }
}
