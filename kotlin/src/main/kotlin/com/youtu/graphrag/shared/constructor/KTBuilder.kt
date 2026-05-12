package com.youtu.graphrag.shared.constructor

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.youtu.graphrag.shared.config.ConfigManager
import com.youtu.graphrag.shared.graph.GraphNode
import com.youtu.graphrag.shared.graph.GraphRelationship
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.math.max
import kotlin.math.min

private data class ChunkRecord(
    val id: String,
    val title: String,
    val text: String,
)

class KTBuilder(
    private val datasetName: String,
    private val schemaPath: String,
    private val mode: String,
    private val config: ConfigManager,
    private val rootDir: Path = Path.of("."),
) {
    private val logger = KotlinLogging.logger {}
    private val mapper = ObjectMapper().registerKotlinModule()

    fun buildKnowledgeGraph(corpusPath: String): List<GraphRelationship> {
        if (datasetName.isBlank() || schemaPath.isBlank() || corpusPath.isBlank() || mode.isBlank()) {
            return emptyList()
        }

        val corpusFile = resolvePath(corpusPath)
        require(corpusFile.exists()) { "Corpus file not found: $corpusFile" }

        val documents = loadDocuments(corpusFile)
        val chunks = createChunks(documents)
        val defaultSchemaType = readDefaultSchemaType()

        writeChunksFile(chunks)
        val relationships = buildRelationships(chunks, defaultSchemaType)
        writeGraphFile(relationships)

        logger.info {
            "Knowledge graph built for '$datasetName' with ${relationships.size} relation(s) and ${chunks.size} chunk(s)"
        }

        return relationships
    }

    private fun loadDocuments(corpusFile: Path): List<Map<String, Any?>> {
        val root = mapper.readTree(corpusFile.toFile())

        if (root.isArray) {
            return root.map { node ->
                mapper.convertValue(node, object : TypeReference<Map<String, Any?>>() {})
            }
        }

        if (root.isObject) {
            return listOf(mapper.convertValue(root, object : TypeReference<Map<String, Any?>>() {}))
        }

        return emptyList()
    }

    private fun createChunks(documents: List<Map<String, Any?>>): List<ChunkRecord> {
        val chunkRecords = mutableListOf<ChunkRecord>()
        val skipChunking = datasetName in config.construction.datasetsNoChunk

        documents.forEachIndexed { documentIndex, rawDocument ->
            val title = rawDocument["title"]?.toString()?.ifBlank { "document_$documentIndex" } ?: "document_$documentIndex"
            val text = rawDocument["text"]?.toString()?.trim().orEmpty()
            if (text.isBlank()) {
                return@forEachIndexed
            }

            val chunkTexts =
                if (skipChunking) {
                    listOf(text)
                } else {
                    chunkText(
                        text = text,
                        chunkSize = config.construction.chunkSize,
                        overlap = config.construction.overlap,
                    )
                }

            chunkTexts.forEachIndexed { chunkIndex, chunkText ->
                val chunkId = buildChunkId(documentIndex, chunkIndex, chunkText)
                chunkRecords.add(
                    ChunkRecord(
                        id = chunkId,
                        title = title,
                        text = chunkText,
                    ),
                )
            }
        }

        return chunkRecords
    }

    private fun chunkText(
        text: String,
        chunkSize: Int,
        overlap: Int,
    ): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        val normalizedChunkSize = max(1, chunkSize)
        val normalizedOverlap = min(max(0, overlap), normalizedChunkSize - 1)

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < text.length) {
            val end = min(text.length, start + normalizedChunkSize)
            val chunk = text.substring(start, end).replace('\n', ' ').trim()
            if (chunk.isNotBlank()) {
                chunks.add(chunk)
            }

            if (end >= text.length) {
                break
            }

            start = end - normalizedOverlap
        }

        return chunks
    }

    private fun buildChunkId(
        documentIndex: Int,
        chunkIndex: Int,
        text: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "$datasetName|$documentIndex|$chunkIndex|$text"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(input.toByteArray(Charsets.UTF_8)))
        return encoded.take(8).padEnd(8, '0')
    }

    private fun readDefaultSchemaType(): String {
        val schemaFile = resolvePath(schemaPath)
        if (!schemaFile.exists()) {
            return "concept"
        }

        return runCatching {
            val root = mapper.readTree(schemaFile.toFile())
            root
                .path("Nodes")
                .firstOrNull()
                ?.asText()
                ?.ifBlank { null }
        }.getOrNull() ?: "concept"
    }

    private fun buildRelationships(
        chunks: List<ChunkRecord>,
        defaultSchemaType: String,
    ): List<GraphRelationship> {
        val relationships = mutableListOf<GraphRelationship>()

        chunks.forEach { chunk ->
            val entityName = chunk.title.ifBlank { "chunk_${chunk.id}" }
            val entityNode =
                GraphNode(
                    label = "entity",
                    properties =
                        mapOf(
                            "name" to entityName,
                            "chunk id" to chunk.id,
                            "schema_type" to defaultSchemaType,
                        ),
                )

            val attributeNode =
                GraphNode(
                    label = "attribute",
                    properties =
                        mapOf(
                            "name" to "length: ${chunk.text.length}",
                            "chunk id" to chunk.id,
                        ),
                )

            relationships.add(
                GraphRelationship(
                    startNode = entityNode,
                    relation = "has_attribute",
                    endNode = attributeNode,
                ),
            )

            val keyword = extractKeyword(chunk.text)
            if (keyword != null) {
                val keywordNode =
                    GraphNode(
                        label = "keyword",
                        properties =
                            mapOf(
                                "name" to keyword,
                                "chunk id" to chunk.id,
                            ),
                    )
                relationships.add(
                    GraphRelationship(
                        startNode = entityNode,
                        relation = "has_keyword",
                        endNode = keywordNode,
                    ),
                )
            }
        }

        return relationships
    }

    private fun extractKeyword(text: String): String? {
        val tokenRegex = Regex("[A-Za-z0-9_]{3,}")
        return tokenRegex.find(text)?.value?.lowercase()
    }

    private fun writeChunksFile(chunks: List<ChunkRecord>) {
        val chunkFile = resolveOutputPath(config.output.chunksDir).resolve("$datasetName.txt")
        chunkFile.parent?.createDirectories()

        val content =
            buildString {
                chunks.forEach { chunk ->
                    append("id: ")
                    append(chunk.id)
                    append('\t')
                    append("Chunk: ")
                    append(chunk.text.replace('\n', ' '))
                    append('\n')
                }
            }

        Files.writeString(
            chunkFile,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun writeGraphFile(relationships: List<GraphRelationship>) {
        val graphFile = resolveOutputPath(config.output.graphsDir).resolve("${datasetName}_new.json")
        graphFile.parent?.createDirectories()
        mapper.writerWithDefaultPrettyPrinter().writeValue(graphFile.toFile(), relationships)
    }

    private fun resolveOutputPath(configPath: String): Path {
        val configured = Path.of(configPath)
        return if (configured.isAbsolute) configured else rootDir.resolve(configured)
    }

    private fun resolvePath(path: String): Path {
        val candidate = Path.of(path)
        return if (candidate.isAbsolute) candidate else rootDir.resolve(candidate)
    }
}
