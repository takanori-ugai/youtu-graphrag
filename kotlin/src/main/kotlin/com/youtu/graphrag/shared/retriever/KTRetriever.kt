package com.youtu.graphrag.shared.retriever

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.youtu.graphrag.shared.config.ConfigManager
import com.youtu.graphrag.shared.graph.GraphRelationship
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines

private data class TripleRecord(
    val source: String,
    val relation: String,
    val target: String,
    val sourceLabel: String,
    val targetLabel: String,
    val sourceProperties: Map<String, String>,
    val targetProperties: Map<String, String>,
) {
    val serialized: String
        get() = "[\"${escape(source)}\", \"${escape(relation)}\", \"${escape(target)}\"]"

    val searchableText: String
        get() =
            buildString {
                append(source)
                append(' ')
                append(relation)
                append(' ')
                append(target)
                append(' ')
                append(sourceLabel)
                append(' ')
                append(targetLabel)
                append(' ')
                append(sourceProperties.values.joinToString(" "))
                append(' ')
                append(targetProperties.values.joinToString(" "))
            }

    val chunkIds: Set<String>
        get() =
            setOfNotNull(
                sourceProperties["chunk id"]?.takeIf { it.isNotBlank() },
                targetProperties["chunk id"]?.takeIf { it.isNotBlank() },
            )

    private fun escape(value: String): String = value.replace("\"", "\\\"")
}

private data class RetrievalChunk(
    val id: String,
    val text: String,
)

private data class EmbeddingCacheFile(
    val modelTag: String,
    val dimensions: Int,
    val vectors: Map<String, List<Float>> = emptyMap(),
)

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
    private val embedder = RetrieverEmbedderFactory.fromConfig(config = config)
    private val cacheModelTag = embedder.modelTag
    private val tripleCacheFileName = "triple_embedding_cache.json"
    private val chunkCacheFileName = "chunk_embedding_cache.json"
    private val tripleCacheNpzFileName = "triple_embedding_cache.npz"
    private val chunkCacheNpzFileName = "chunk_embedding_cache.npz"
    private val tripleCachePtFileName = "triple_embedding_cache.pt"
    private val chunkCachePtFileName = "chunk_embedding_cache.pt"

    private var tripleRecords: List<TripleRecord> = emptyList()
    private var adjacencyByNode: Map<String, List<TripleRecord>> = emptyMap()
    private var tripleVectorIndex: SemanticVectorIndex<TripleRecord>? = null
    private var tripleOrdinalByRecord: Map<TripleRecord, Int> = emptyMap()
    private var chunkRecords: List<RetrievalChunk> = emptyList()
    private var chunkVectorIndex: SemanticVectorIndex<RetrievalChunk>? = null
    private var chunkOrdinalById: Map<String, Int> = emptyMap()
    private var chunkById: Map<String, String> = emptyMap()
    private var vectorStrategyTag: String = "hybrid_lexical_vector"

    fun buildIndices() {
        if (datasetName.isBlank() || graphPath.isBlank() || schemaPath.isBlank()) {
            throw IllegalArgumentException("datasetName, graphPath, and schemaPath must be non-blank")
        }

        config.createOutputDirectories()
        tripleRecords = loadTriples(resolvePath(graphPath))
        adjacencyByNode = buildAdjacency(tripleRecords)
        tripleOrdinalByRecord = tripleRecords.withIndex().associate { (index, record) -> record to index }
        chunkById = loadChunks(resolvePath("${config.output.chunksDir}/$datasetName.txt"))
        chunkRecords = chunkById.entries.map { (chunkId, text) -> RetrievalChunk(id = chunkId, text = text) }
        chunkOrdinalById = chunkRecords.withIndex().associate { (index, chunk) -> chunk.id to index }

        val tripleVectors = prepareTripleVectors()
        val chunkVectors = prepareChunkVectors()

        tripleVectorIndex =
            buildSemanticIndex(
                items = tripleRecords,
                textSelector = { record -> record.searchableText },
                vectorSelector = { record -> tripleVectors[record] ?: embedder.embed(record.searchableText) },
            )
        chunkVectorIndex =
            buildSemanticIndex(
                items = chunkRecords,
                textSelector = { chunk -> chunk.text },
                vectorSelector = { chunk -> chunkVectors[chunk] ?: embedder.embed(chunk.text) },
            )
        vectorStrategyTag = tripleVectorIndex?.strategyTag ?: "hybrid_lexical_vector"

        logger.info {
            "Built retrieval indices for '$datasetName' with ${tripleRecords.size} triples and ${chunkById.size} chunks"
        }
    }

    fun processRetrievalResults(
        question: String,
        involvedTypes: Map<String, List<String>> = emptyMap(),
    ): Map<String, Any> {
        val normalizedTopK = topK.coerceAtLeast(1)
        val vectorSearchK =
            config.retrieval.faiss.searchK
                .coerceAtLeast(normalizedTopK)
        val vectorEnabled = config.retrieval.enableReranking
        val normalizedQuestionKeywords = tokenize(question)
        val typeFilteredTriples = filterTriplesByInvolvedTypes(tripleRecords, involvedTypes)
        val retrievalPool = typeFilteredTriples.ifEmpty { tripleRecords }

        val seedTriples =
            rankedItems(
                items = retrievalPool,
                questionKeywords = normalizedQuestionKeywords,
                topLimit = normalizedTopK,
                textSelector = { record -> record.searchableText },
            )

        val expandedTriples = expandTriplesFromSeeds(seedTriples, retrievalPool).ifEmpty { seedTriples }
        val vectorTripleScores =
            if (vectorEnabled) {
                vectorSearchScoresForTriples(
                    question = question,
                    searchLimit = vectorSearchK,
                    allowedTriples = expandedTriples,
                )
            } else {
                emptyMap()
            }
        val selectedTriples =
            rerankTriples(
                triples = expandedTriples,
                questionKeywords = normalizedQuestionKeywords,
                vectorScores = vectorTripleScores,
                topLimit = normalizedTopK,
            )
        val selectedTripleStrings = selectedTriples.map { record -> record.serialized }

        val chunkIdsFromTriples =
            linkedSetOf<String>().apply {
                selectedTriples.forEach { record ->
                    record.chunkIds.forEach { chunkId ->
                        if (chunkId in chunkById) {
                            add(chunkId)
                        }
                    }
                }
            }

        val rankedChunkEntries =
            rankedItems(
                items = chunkRecords,
                questionKeywords = normalizedQuestionKeywords,
                topLimit = normalizedTopK,
                textSelector = { chunk -> chunk.text },
            )
        val vectorChunkScores =
            if (vectorEnabled) {
                vectorSearchScoresForChunks(
                    question = question,
                    searchLimit = vectorSearchK,
                )
            } else {
                emptyMap()
            }

        val mergedChunkIds =
            linkedSetOf<String>().apply {
                addAll(chunkIdsFromTriples)
                rankedChunkEntries.forEach { chunk -> add(chunk.id) }
                vectorChunkScores.keys.forEach { chunkId -> add(chunkId) }
            }
        val chunkIds =
            rerankChunks(
                chunkIds = mergedChunkIds.toList(),
                tripleBoostChunkIds = chunkIdsFromTriples,
                questionKeywords = normalizedQuestionKeywords,
                vectorScores = vectorChunkScores,
                topLimit = normalizedTopK,
            )
        val chunkContents =
            chunkIds.associateWith { chunkId ->
                chunkById[chunkId].orEmpty()
            }

        return mapOf(
            "question" to question,
            "triples" to selectedTripleStrings,
            "chunk_ids" to chunkIds,
            "chunk_contents" to chunkContents,
            "top_k" to topK,
            "recall_paths" to recallPaths,
            "involved_types" to involvedTypes,
            "retrieval_strategy" to if (vectorEnabled) vectorStrategyTag else "lexical",
            "mode" to mode,
        )
    }

    fun buildKnowledgeContext(
        triples: List<String>,
        chunks: List<String>,
        tripleLimit: Int = 20,
        chunkLimit: Int = 10,
    ): String {
        val tripleSection = triples.take(tripleLimit).joinToString("\n")
        val chunkSection = chunks.take(chunkLimit).joinToString("\n")
        return buildString {
            append("=== Triples ===\n")
            append(tripleSection)
            append("\n=== Chunks ===\n")
            append(chunkSection)
        }
    }

    fun generatePrompt(
        question: String,
        context: String,
    ): String =
        runCatching {
            config.getPromptFormatted(
                category = "retrieval",
                promptType = retrievalPromptType(),
                variables =
                    mapOf(
                        "question" to question,
                        "context" to context,
                    ),
            )
        }.getOrElse {
            """
            Question: $question

            Knowledge Context:
            $context

            Answer (be specific and direct):
            """.trimIndent()
        }

    private fun retrievalPromptType(): String =
        when (datasetName) {
            "anony_chs",
            "novel",
            -> "novel"

            "anony_eng",
            "novel_eng",
            -> "novel_eng"

            else -> "general"
        }

    fun generateIrcotPrompt(
        currentQuery: String,
        context: String,
        previousThoughts: String,
        step: Int,
    ): String =
        runCatching {
            config.getPromptFormatted(
                category = "retrieval",
                promptType = "ircot",
                variables =
                    mapOf(
                        "current_query" to currentQuery,
                        "context" to context,
                        "previous_thoughts" to previousThoughts,
                        "step" to step,
                    ),
            )
        }.getOrElse {
            """
            Current Question: $currentQuery
            Available Knowledge Context:
            $context
            Previous Thoughts: $previousThoughts
            Step $step
            """.trimIndent()
        }

    private fun loadTriples(graphFile: Path): List<TripleRecord> {
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
            val source =
                relationship.startNode.properties["name"]
                    ?.takeIf { value -> value.isNotBlank() }
                    ?: relationship.startNode.label
            val target =
                relationship.endNode.properties["name"]
                    ?.takeIf { value -> value.isNotBlank() }
                    ?: relationship.endNode.label
            TripleRecord(
                source = source,
                relation = relationship.relation,
                target = target,
                sourceLabel = relationship.startNode.label,
                targetLabel = relationship.endNode.label,
                sourceProperties = relationship.startNode.properties,
                targetProperties = relationship.endNode.properties,
            )
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

    private fun buildAdjacency(records: List<TripleRecord>): Map<String, List<TripleRecord>> {
        if (records.isEmpty()) {
            return emptyMap()
        }

        val adjacency = linkedMapOf<String, MutableList<TripleRecord>>()
        records.forEach { record ->
            adjacency.getOrPut(record.source) { mutableListOf() }.add(record)
            adjacency.getOrPut(record.target) { mutableListOf() }.add(record)
        }
        return adjacency.mapValues { (_, value) -> value.toList() }
    }

    private fun filterTriplesByInvolvedTypes(
        records: List<TripleRecord>,
        involvedTypes: Map<String, List<String>>,
    ): List<TripleRecord> {
        val nodeFilters = normalizeFilters(involvedTypes["nodes"])
        val relationFilters = normalizeFilters(involvedTypes["relations"])
        val attributeFilters = normalizeFilters(involvedTypes["attributes"])
        if (nodeFilters.isEmpty() && relationFilters.isEmpty() && attributeFilters.isEmpty()) {
            return records
        }

        return records.filter { record ->
            val nodeMatch =
                nodeFilters.isNotEmpty() && matchesNodeFilter(record, nodeFilters)
            val relationMatch =
                relationFilters.isNotEmpty() &&
                    relationFilters.any { filter -> record.relation.lowercase().contains(filter) }
            val attributeMatch =
                attributeFilters.isNotEmpty() && matchesAttributeFilter(record, attributeFilters)

            nodeMatch || relationMatch || attributeMatch
        }
    }

    private fun matchesNodeFilter(
        record: TripleRecord,
        nodeFilters: Set<String>,
    ): Boolean {
        val candidates =
            listOf(
                record.sourceLabel,
                record.targetLabel,
                record.source,
                record.target,
                record.sourceProperties["schema_type"].orEmpty(),
                record.targetProperties["schema_type"].orEmpty(),
            ).map { value -> value.lowercase() }

        return nodeFilters.any { filter ->
            candidates.any { candidate -> candidate.contains(filter) }
        }
    }

    private fun matchesAttributeFilter(
        record: TripleRecord,
        attributeFilters: Set<String>,
    ): Boolean {
        val candidates =
            buildList {
                addAll(record.sourceProperties.keys)
                addAll(record.sourceProperties.values)
                addAll(record.targetProperties.keys)
                addAll(record.targetProperties.values)
            }.map { value -> value.lowercase() }

        return attributeFilters.any { filter ->
            candidates.any { candidate -> candidate.contains(filter) }
        }
    }

    private fun expandTriplesFromSeeds(
        seedTriples: List<TripleRecord>,
        retrievalPool: List<TripleRecord>,
    ): List<TripleRecord> {
        if (seedTriples.isEmpty()) {
            return emptyList()
        }

        val pooledSet = retrievalPool.toSet()
        val collected = linkedSetOf<TripleRecord>()
        val visitedNodes = mutableSetOf<String>()
        var frontier =
            seedTriples
                .flatMap { seed -> listOf(seed.source, seed.target) }
                .toMutableSet()

        seedTriples.forEach { seed -> collected.add(seed) }
        visitedNodes.addAll(frontier)

        val hops = (recallPaths.coerceAtLeast(1) - 1)
        repeat(hops) {
            if (frontier.isEmpty()) {
                return@repeat
            }

            val nextFrontier = mutableSetOf<String>()
            frontier.forEach { node ->
                adjacencyByNode[node].orEmpty().forEach { candidate ->
                    if (candidate !in pooledSet) {
                        return@forEach
                    }
                    collected.add(candidate)
                    if (candidate.source !in visitedNodes) {
                        nextFrontier.add(candidate.source)
                    }
                    if (candidate.target !in visitedNodes) {
                        nextFrontier.add(candidate.target)
                    }
                }
            }
            frontier = nextFrontier
            visitedNodes.addAll(nextFrontier)
        }

        return collected.toList()
    }

    private fun vectorSearchScoresForTriples(
        question: String,
        searchLimit: Int,
        allowedTriples: List<TripleRecord>,
    ): Map<TripleRecord, Double> {
        val allowed = allowedTriples.toSet()
        return tripleVectorIndex
            ?.search(question, searchLimit)
            .orEmpty()
            .asSequence()
            .filter { hit -> hit.item in allowed }
            .associate { hit -> hit.item to hit.score }
    }

    private fun vectorSearchScoresForChunks(
        question: String,
        searchLimit: Int,
    ): Map<String, Double> =
        chunkVectorIndex
            ?.search(question, searchLimit)
            .orEmpty()
            .associate { hit -> hit.item.id to hit.score }

    private fun prepareTripleVectors(): Map<TripleRecord, FloatArray> {
        if (tripleRecords.isEmpty()) {
            return emptyMap()
        }

        val expectedKeys = tripleRecords.map { record -> record.serialized }.toSet()
        val cachePath = resolveCacheFile(tripleCacheFileName)
        val cacheNpzPath = resolveCacheFile(tripleCacheNpzFileName)
        val cachePtPath = resolveCacheFile(tripleCachePtFileName)
        val cachedByKey =
            if (config.retrieval.enableCaching) {
                loadEmbeddingCache(cachePath, cacheNpzPath, cachePtPath)
            } else {
                mutableMapOf()
            }

        val vectorsByRecord = linkedMapOf<TripleRecord, FloatArray>()
        var changed = false

        tripleRecords.forEach { record ->
            val key = record.serialized
            val vector =
                cachedByKey[key] ?: embedder.embed(record.searchableText).also {
                    if (config.retrieval.enableCaching) {
                        cachedByKey[key] = it
                        changed = true
                    }
                }
            vectorsByRecord[record] = vector
        }

        if (config.retrieval.enableCaching) {
            val staleKeys = cachedByKey.keys - expectedKeys
            if (staleKeys.isNotEmpty()) {
                staleKeys.forEach { staleKey -> cachedByKey.remove(staleKey) }
                changed = true
            }

            val needsPtExport = config.embeddings.exportPtCache && !cachePtPath.exists()
            if (changed || !cachePath.exists() || !cacheNpzPath.exists() || needsPtExport) {
                saveEmbeddingCache(cachePath, cacheNpzPath, cachePtPath, cachedByKey)
            }
        }

        return vectorsByRecord
    }

    private fun prepareChunkVectors(): Map<RetrievalChunk, FloatArray> {
        if (chunkRecords.isEmpty()) {
            return emptyMap()
        }

        val expectedKeys = chunkRecords.map { chunk -> chunk.id }.toSet()
        val cachePath = resolveCacheFile(chunkCacheFileName)
        val cacheNpzPath = resolveCacheFile(chunkCacheNpzFileName)
        val cachePtPath = resolveCacheFile(chunkCachePtFileName)
        val cachedByKey =
            if (config.retrieval.enableCaching) {
                loadEmbeddingCache(cachePath, cacheNpzPath, cachePtPath)
            } else {
                mutableMapOf()
            }

        val vectorsByChunk = linkedMapOf<RetrievalChunk, FloatArray>()
        var changed = false

        chunkRecords.forEach { chunk ->
            val key = chunk.id
            val vector =
                cachedByKey[key] ?: embedder.embed(chunk.text).also {
                    if (config.retrieval.enableCaching) {
                        cachedByKey[key] = it
                        changed = true
                    }
                }
            vectorsByChunk[chunk] = vector
        }

        if (config.retrieval.enableCaching) {
            val staleKeys = cachedByKey.keys - expectedKeys
            if (staleKeys.isNotEmpty()) {
                staleKeys.forEach { staleKey -> cachedByKey.remove(staleKey) }
                changed = true
            }

            val needsPtExport = config.embeddings.exportPtCache && !cachePtPath.exists()
            if (changed || !cachePath.exists() || !cacheNpzPath.exists() || needsPtExport) {
                saveEmbeddingCache(cachePath, cacheNpzPath, cachePtPath, cachedByKey)
            }
        }

        return vectorsByChunk
    }

    private fun loadEmbeddingCache(
        cachePath: Path,
        cacheNpzPath: Path,
        cachePtPath: Path,
    ): MutableMap<String, FloatArray> {
        if (cacheNpzPath.exists()) {
            val npzVectors =
                runCatching {
                    NpzEmbeddingCache.read(cacheNpzPath, expectedDimensions = embedder.dimensions)
                }.getOrElse { error ->
                    logger.warn(error) { "Failed to load NPZ embedding cache from $cacheNpzPath. Falling back to JSON cache." }
                    emptyMap()
                }
            if (npzVectors.isNotEmpty()) {
                return npzVectors.toMutableMap()
            }
        }

        if (cachePtPath.exists()) {
            val ptNpzVectors =
                runCatching {
                    NpzEmbeddingCache.read(cachePtPath, expectedDimensions = embedder.dimensions)
                }.getOrElse {
                    emptyMap()
                }
            if (ptNpzVectors.isNotEmpty()) {
                return ptNpzVectors.toMutableMap()
            }

            val converted =
                TorchCacheInterop.tryConvertPtToNpz(
                    ptPath = cachePtPath,
                    npzPath = cacheNpzPath,
                )
            if (converted) {
                val convertedVectors =
                    runCatching {
                        NpzEmbeddingCache.read(cacheNpzPath, expectedDimensions = embedder.dimensions)
                    }.getOrElse {
                        emptyMap()
                    }
                if (convertedVectors.isNotEmpty()) {
                    return convertedVectors.toMutableMap()
                }
            }
        }

        if (!cachePath.exists()) {
            return linkedMapOf()
        }

        val cacheFile =
            runCatching {
                mapper.readValue(cachePath.toFile(), EmbeddingCacheFile::class.java)
            }.getOrElse { error ->
                logger.warn(error) { "Failed to load embedding cache from $cachePath. Rebuilding cache entries." }
                return linkedMapOf()
            }

        if (cacheFile.modelTag != cacheModelTag || cacheFile.dimensions != embedder.dimensions) {
            logger.info {
                "Embedding cache metadata mismatch at $cachePath (modelTag='${cacheFile.modelTag}', dimensions=${cacheFile.dimensions}), rebuilding."
            }
            return linkedMapOf()
        }

        return cacheFile.vectors.entries
            .mapNotNull { (key, values) ->
                if (values.size != embedder.dimensions) {
                    null
                } else {
                    key to values.toFloatArray()
                }
            }.toMap(linkedMapOf())
    }

    private fun saveEmbeddingCache(
        cachePath: Path,
        cacheNpzPath: Path,
        cachePtPath: Path,
        vectorsByKey: Map<String, FloatArray>,
    ) {
        runCatching {
            cachePath.parent?.createDirectories()
            val cacheFile =
                EmbeddingCacheFile(
                    modelTag = cacheModelTag,
                    dimensions = embedder.dimensions,
                    vectors = vectorsByKey.mapValues { (_, vector) -> vector.toList() },
                )
            mapper.writerWithDefaultPrettyPrinter().writeValue(cachePath.toFile(), cacheFile)
        }.onFailure { error ->
            logger.warn(error) { "Failed to persist embedding cache to $cachePath" }
        }

        runCatching {
            NpzEmbeddingCache.write(
                path = cacheNpzPath,
                vectorsByKey = vectorsByKey,
                expectedDimensions = embedder.dimensions,
            )
        }.onFailure { error ->
            logger.warn(error) { "Failed to persist NPZ embedding cache to $cacheNpzPath" }
        }

        if (config.embeddings.exportPtCache) {
            val converted =
                TorchCacheInterop.tryConvertNpzToPt(
                    npzPath = cacheNpzPath,
                    ptPath = cachePtPath,
                )
            if (!converted) {
                logger.info { "PT embedding cache export skipped for $cachePtPath (python3+torch not available)." }
            }
        }
    }

    private fun resolveCacheFile(fileName: String): Path =
        resolvePath(config.retrieval.cacheDir)
            .resolve(datasetName)
            .resolve(fileName)

    private fun <T> buildSemanticIndex(
        items: List<T>,
        textSelector: (T) -> String,
        vectorSelector: ((T) -> FloatArray)? = null,
    ): SemanticVectorIndex<T> {
        if (items.isEmpty()) {
            return LocalVectorIndex.build(
                items = items,
                embedder = embedder,
                textSelector = textSelector,
                vectorSelector = vectorSelector,
            )
        }

        return runCatching {
            LuceneAnnIndex.build(
                items = items,
                embedder = embedder,
                textSelector = textSelector,
                vectorSelector = vectorSelector,
            )
        }.getOrElse { error ->
            logger.warn(error) { "Failed to initialize Lucene ANN index. Falling back to local vector index." }
            LocalVectorIndex.build(
                items = items,
                embedder = embedder,
                textSelector = textSelector,
                vectorSelector = vectorSelector,
            )
        }
    }

    private fun rerankTriples(
        triples: List<TripleRecord>,
        questionKeywords: Set<String>,
        vectorScores: Map<TripleRecord, Double>,
        topLimit: Int,
    ): List<TripleRecord> {
        if (triples.isEmpty()) {
            return emptyList()
        }

        val scored =
            triples.map { triple ->
                val lexicalScore = keywordMatchCount(triple.searchableText, questionKeywords).toDouble()
                val vectorScore = vectorScores[triple] ?: 0.0
                val combined = lexicalScore + (vectorScore * 2.0)
                Triple(
                    triple,
                    combined,
                    tripleOrdinalByRecord[triple] ?: Int.MAX_VALUE,
                )
            }

        return scored
            .sortedWith(
                compareByDescending<Triple<TripleRecord, Double, Int>> { value -> value.second }
                    .thenBy { value -> value.third },
            ).take(topLimit)
            .map { value -> value.first }
    }

    private fun rerankChunks(
        chunkIds: List<String>,
        tripleBoostChunkIds: Set<String>,
        questionKeywords: Set<String>,
        vectorScores: Map<String, Double>,
        topLimit: Int,
    ): List<String> {
        if (chunkIds.isEmpty()) {
            return emptyList()
        }

        val scored =
            chunkIds.map { chunkId ->
                val text = chunkById[chunkId].orEmpty()
                val lexicalScore = keywordMatchCount(text, questionKeywords).toDouble()
                val vectorScore = vectorScores[chunkId] ?: 0.0
                val tripleBoost = if (chunkId in tripleBoostChunkIds) 0.75 else 0.0
                val combined = lexicalScore + (vectorScore * 2.0) + tripleBoost
                Triple(
                    chunkId,
                    combined,
                    chunkOrdinalById[chunkId] ?: Int.MAX_VALUE,
                )
            }

        return scored
            .sortedWith(
                compareByDescending<Triple<String, Double, Int>> { value -> value.second }
                    .thenBy { value -> value.third },
            ).take(topLimit)
            .map { value -> value.first }
    }

    private fun keywordMatchCount(
        text: String,
        questionKeywords: Set<String>,
    ): Int {
        if (questionKeywords.isEmpty()) {
            return 0
        }

        val normalizedText = text.lowercase()
        return questionKeywords.count { keyword -> keyword in normalizedText }
    }

    private fun normalizeFilters(rawValues: List<String>?): Set<String> =
        rawValues
            .orEmpty()
            .asSequence()
            .map { value -> value.trim().lowercase() }
            .filter { value -> value.isNotBlank() }
            .toSet()

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
