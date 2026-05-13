package com.youtu.graphrag.shared.retriever

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.youtu.graphrag.shared.config.ConfigManager
import com.youtu.graphrag.shared.graph.GraphNode
import com.youtu.graphrag.shared.graph.GraphRelationship
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KTRetrieverTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `generatePrompt uses general retrieval prompt for default datasets`() {
        val config = ConfigManager("config/base_config.json")
        val retriever = createRetriever(datasetName = "demo", config = config)

        val prompt = retriever.generatePrompt(question = "Who directed the film?", context = "some context")

        assertTrue(prompt.contains("You are an expert knowledge assistant"))
        assertTrue(prompt.contains("Who directed the film?"))
    }

    @Test
    fun `generatePrompt uses chinese novel prompt for anony_chs dataset`() {
        val config = ConfigManager("config/base_config.json")
        val retriever = createRetriever(datasetName = "anony_chs", config = config)

        val prompt = retriever.generatePrompt(question = "PERSON#1是谁？", context = "小说知识")

        assertTrue(prompt.contains("你是小说知识助手"))
        assertTrue(prompt.contains("PERSON#1是谁？"))
    }

    @Test
    fun `generatePrompt uses english novel prompt for anony_eng dataset`() {
        val config = ConfigManager("config/base_config.json")
        val retriever = createRetriever(datasetName = "anony_eng", config = config)

        val prompt = retriever.generatePrompt(question = "Who is PERSON#1?", context = "novel context")

        assertTrue(prompt.contains("You are a novel knowledge assistant"))
        assertTrue(prompt.contains("Who is PERSON#1?"))
    }

    @Test
    fun `generatePrompt supports python prompt alias retrieval novel_chs`() {
        val config = ConfigManager("config/base_config.json")
        config.overrideConfig(
            mapOf(
                "prompts" to
                    mapOf(
                        "retrieval" to
                            mapOf(
                                "general" to "GENERAL::{question}::{context}",
                                "novel_chs" to "NOVEL_CHS::{question}::{context}",
                            ),
                    ),
            ),
        )

        val retriever = createRetriever(datasetName = "anony_chs", config = config)
        val prompt = retriever.generatePrompt(question = "PERSON#1是谁？", context = "小说知识上下文")

        assertTrue(prompt.startsWith("NOVEL_CHS::"))
        assertTrue(prompt.contains("PERSON#1是谁？"))
        assertTrue(prompt.contains("小说知识上下文"))
    }

    @Test
    fun `generateIrcotPrompt selects source-specific template keys`() {
        val config = ConfigManager("config/base_config.json")
        config.overrideConfig(
            mapOf(
                "prompts" to
                    mapOf(
                        "retrieval" to
                            mapOf(
                                "general" to "GENERAL::{question}::{context}",
                                "ircot" to "IRCOT::{current_query}::{step}",
                                "ircot_backend" to "BACKEND::{original_question}::{current_iteration_query}::{step}",
                                "ircot_main" to "MAIN::{current_query}::{step}",
                            ),
                    ),
            ),
        )

        val retriever = createRetriever(datasetName = "demo", config = config)

        val backendPrompt =
            retriever.generateIrcotPrompt(
                currentQuery = "Where is Project Alpha based?",
                originalQuestion = "Where is Project Alpha based?",
                context = "ctx",
                previousThoughts = "none",
                step = 2,
                promptSource = IrcotPromptSource.BACKEND,
            )
        val mainPrompt =
            retriever.generateIrcotPrompt(
                currentQuery = "Where is Project Alpha based?",
                originalQuestion = "Where is Project Alpha based?",
                context = "ctx",
                previousThoughts = "none",
                step = 3,
                promptSource = IrcotPromptSource.MAIN,
            )

        assertTrue(backendPrompt.startsWith("BACKEND::"))
        assertTrue("::2" in backendPrompt)
        assertTrue(mainPrompt.startsWith("MAIN::"))
        assertTrue("::3" in mainPrompt)
    }

    @Test
    fun `generateIrcotPrompt uses shared ircot template when source-specific keys are absent`() {
        val config = ConfigManager("config/base_config.json")
        config.overrideConfig(
            mapOf(
                "prompts" to
                    mapOf(
                        "retrieval" to
                            mapOf(
                                "general" to "GENERAL::{question}::{context}",
                                "ircot" to "SHARED::{current_query}::{step}",
                            ),
                    ),
            ),
        )

        val retriever = createRetriever(datasetName = "demo", config = config)
        val backendPrompt =
            retriever.generateIrcotPrompt(
                currentQuery = "current query",
                originalQuestion = "original question",
                context = "ctx",
                previousThoughts = "thought",
                step = 1,
                promptSource = IrcotPromptSource.BACKEND,
            )
        val mainPrompt =
            retriever.generateIrcotPrompt(
                currentQuery = "current query",
                originalQuestion = "original question",
                context = "ctx",
                previousThoughts = "thought",
                step = 1,
                promptSource = IrcotPromptSource.MAIN,
            )

        assertTrue(backendPrompt.startsWith("SHARED::"))
        assertTrue(mainPrompt.startsWith("SHARED::"))
        assertTrue("current query" in backendPrompt)
        assertTrue("::1" in mainPrompt)
    }

    @Test
    fun `enhanceQuery appends entities and key terms in python-compatible format`() {
        val config = ConfigManager("config/base_config.json")
        config.overrideConfig(
            mapOf(
                "nlp" to
                    mapOf(
                        "provider" to "regex",
                        "stopwords" to listOf("where", "does", "at", "the"),
                    ),
            ),
        )

        val retriever = createRetriever(datasetName = "demo", config = config)
        val question = "Where does Alice work at ACME Corp?"
        val enhanced = retriever.enhanceQuery(question)

        assertTrue(enhanced.startsWith(question))
        assertTrue("Entities:" in enhanced)
        assertTrue("Key terms:" in enhanced)
    }

    @Test
    fun `extractQueryKeywords uses stopword filtering and entity retention`() {
        val config = ConfigManager("config/base_config.json")
        config.overrideConfig(
            mapOf(
                "nlp" to
                    mapOf(
                        "provider" to "regex",
                        "stopwords" to listOf("what", "is", "the", "in"),
                    ),
            ),
        )

        val retriever = createRetriever(datasetName = "demo", config = config)
        val keywords = retriever.extractQueryKeywords("What is the capital city in France?")

        assertTrue("capital" in keywords)
        assertTrue("city" in keywords)
        assertTrue("france" in keywords)
        assertTrue("what" !in keywords)
        assertTrue("the" !in keywords)
    }

    @Test
    fun `processRetrievalResults includes enhanced question and can disable enhancement`() {
        val config = ConfigManager("config/base_config.json")
        val retriever = createRetriever(datasetName = "demo", config = config)
        val question = "Who leads OpenAI in San Francisco?"

        val enhancedResults = retriever.processRetrievalResults(question = question)
        val enhancedQuestion = enhancedResults["enhanced_question"]?.toString().orEmpty()
        val keywords = (enhancedResults["query_keywords"] as? List<*>).orEmpty().map { it.toString() }

        assertTrue(enhancedQuestion.startsWith(question))
        assertTrue("Entities:" in enhancedQuestion)
        assertTrue(keywords.isNotEmpty())

        config.overrideConfig(
            mapOf(
                "retrieval" to
                    mapOf(
                        "enable_query_enhancement" to false,
                    ),
            ),
        )
        val plainResults = retriever.processRetrievalResults(question = question)
        assertEquals(question, plainResults["enhanced_question"])
    }

    @Test
    fun `opennlp provider falls back to regex when models are unavailable`() {
        val config = ConfigManager("config/base_config.json")
        config.overrideConfig(
            mapOf(
                "nlp" to
                    mapOf(
                        "provider" to "opennlp",
                    ),
            ),
        )

        val retriever = createRetriever(datasetName = "demo", config = config)
        val keywords = retriever.extractQueryKeywords("Who founded OpenAI in 2015?")

        assertTrue("openai" in keywords)
        assertTrue(keywords.isNotEmpty())
    }

    @Test
    fun `processRetrievalResults prioritizes involved relation types`() {
        val root = Files.createTempDirectory("youtu-graphrag-retriever-filter-test")
        val config = createTestConfig(root)
        val datasetName = "filter_ds"
        val graphPath = root.resolve("output/graphs/${datasetName}_new.json")

        writeGraph(
            graphPath = graphPath,
            relationships =
                listOf(
                    relationship(
                        sourceName = "Alice",
                        sourceLabel = "person",
                        relation = "works_at",
                        targetName = "ACME",
                        targetLabel = "organization",
                        chunkId = "c1",
                    ),
                    relationship(
                        sourceName = "Alice",
                        sourceLabel = "person",
                        relation = "lives_in",
                        targetName = "Tokyo",
                        targetLabel = "location",
                        chunkId = "c2",
                    ),
                ),
        )
        writeChunks(
            chunkFile = root.resolve("output/chunks/$datasetName.txt"),
            entries =
                listOf(
                    "c1" to "Alice works at ACME as an engineer.",
                    "c2" to "Alice lives in Tokyo.",
                ),
        )

        val retriever =
            createRetriever(
                datasetName = datasetName,
                config = config,
                graphPath = graphPath.toString(),
                rootDir = root,
                topK = 3,
                recallPaths = 2,
            )

        val results =
            retriever.processRetrievalResults(
                question = "Where does Alice work?",
                involvedTypes =
                    mapOf(
                        "nodes" to listOf("person"),
                        "relations" to listOf("works_at"),
                        "attributes" to emptyList(),
                    ),
            )

        val triples =
            (results["triples"] as? List<*>)?.map { value -> value.toString() }
                ?: error("Expected 'triples' list in results")
        val chunkIds =
            (results["chunk_ids"] as? List<*>)?.map { value -> value.toString() }
                ?: error("Expected 'chunk_ids' list in results")

        assertTrue(triples.isNotEmpty())
        assertTrue(triples.first().contains("\"works_at\""))
        assertTrue(triples.any { triple -> "\"works_at\"" in triple })
        assertTrue("c1" in chunkIds)
    }

    @Test
    fun `processRetrievalResults expands to neighbor triples based on recallPaths`() {
        val root = Files.createTempDirectory("youtu-graphrag-retriever-path-test")
        val config = createTestConfig(root)
        val datasetName = "path_ds"
        val graphPath = root.resolve("output/graphs/${datasetName}_new.json")

        writeGraph(
            graphPath = graphPath,
            relationships =
                listOf(
                    relationship(
                        sourceName = "Alpha",
                        sourceLabel = "entity",
                        relation = "linked_to",
                        targetName = "Beta",
                        targetLabel = "entity",
                        chunkId = "c1",
                    ),
                    relationship(
                        sourceName = "Beta",
                        sourceLabel = "entity",
                        relation = "connected_to",
                        targetName = "Gamma",
                        targetLabel = "entity",
                        chunkId = "c2",
                    ),
                ),
        )
        writeChunks(
            chunkFile = root.resolve("output/chunks/$datasetName.txt"),
            entries =
                listOf(
                    "c1" to "Alpha is directly linked to Beta.",
                    "c2" to "Beta is connected to Gamma.",
                ),
        )

        val retriever =
            createRetriever(
                datasetName = datasetName,
                config = config,
                graphPath = graphPath.toString(),
                rootDir = root,
                topK = 3,
                recallPaths = 2,
            )

        val results = retriever.processRetrievalResults(question = "What is related to Alpha?")
        val triples =
            (results["triples"] as? List<*>)?.map { value -> value.toString() }
                ?: error("Expected 'triples' list in results")

        assertTrue(triples.any { triple -> "\"linked_to\"" in triple })
        assertTrue(triples.any { triple -> "\"connected_to\"" in triple })
    }

    @Test
    fun `processRetrievalResults merges triple chunk ids with ranked chunk hits`() {
        val root = Files.createTempDirectory("youtu-graphrag-retriever-chunk-merge-test")
        val config = createTestConfig(root)
        val datasetName = "chunk_merge_ds"
        val graphPath = root.resolve("output/graphs/${datasetName}_new.json")

        writeGraph(
            graphPath = graphPath,
            relationships =
                listOf(
                    relationship(
                        sourceName = "Delta",
                        sourceLabel = "entity",
                        relation = "mentions",
                        targetName = "Echo",
                        targetLabel = "entity",
                        chunkId = "c1",
                    ),
                ),
        )
        writeChunks(
            chunkFile = root.resolve("output/chunks/$datasetName.txt"),
            entries =
                listOf(
                    "c1" to "Delta and Echo are linked in the source text.",
                    "c2" to "Beta appears frequently in this standalone chunk.",
                ),
        )

        val retriever =
            createRetriever(
                datasetName = datasetName,
                config = config,
                graphPath = graphPath.toString(),
                rootDir = root,
                topK = 2,
                recallPaths = 1,
            )

        val results = retriever.processRetrievalResults(question = "Give Beta details")
        val chunkIds =
            (results["chunk_ids"] as? List<*>)?.map { value -> value.toString() }
                ?: error("Expected 'chunk_ids' list")
        val chunkContents =
            (results["chunk_contents"] as? List<*>)?.map { value -> value.toString() }
                ?: error("Expected 'chunk_contents' list")
        val chunkContentsById =
            (results["chunk_contents_by_id"] as? Map<*, *>)?.mapValues { entry -> entry.value.toString() }
                ?: error("Expected 'chunk_contents_by_id' map")
        val chunkRetrievalResults =
            (results["chunk_retrieval_results"] as? List<*>)?.map { value -> value.toString() }
                ?: error("Expected 'chunk_retrieval_results' list")

        assertEquals(2, chunkIds.size)
        assertTrue("c1" in chunkIds)
        assertTrue("c2" in chunkIds)
        assertEquals(2, chunkContents.size)
        assertTrue(chunkContentsById["c1"]?.contains("Delta") == true)
        assertTrue(chunkContentsById["c2"]?.contains("Beta") == true)
        assertEquals(2, chunkRetrievalResults.size)
        assertTrue(chunkRetrievalResults.all { line -> line.startsWith("[Chunk ") })
    }

    @Test
    fun `processRetrievalResults reports hybrid strategy when reranking enabled`() {
        val root = Files.createTempDirectory("youtu-graphrag-retriever-strategy-test")
        val config = createTestConfig(root)
        val datasetName = "strategy_ds"
        val graphPath = root.resolve("output/graphs/${datasetName}_new.json")

        writeGraph(
            graphPath = graphPath,
            relationships =
                listOf(
                    relationship(
                        sourceName = "NodeA",
                        sourceLabel = "entity",
                        relation = "related_to",
                        targetName = "NodeB",
                        targetLabel = "entity",
                        chunkId = "c1",
                    ),
                ),
        )
        writeChunks(
            chunkFile = root.resolve("output/chunks/$datasetName.txt"),
            entries = listOf("c1" to "NodeA related to NodeB."),
        )

        val retriever =
            createRetriever(
                datasetName = datasetName,
                config = config,
                graphPath = graphPath.toString(),
                rootDir = root,
                topK = 2,
            )

        val results = retriever.processRetrievalResults(question = "How is NodeA connected?")
        val hybridStrategy = results["retrieval_strategy"]?.toString().orEmpty()
        assertTrue(hybridStrategy.startsWith("hybrid_lexical"))

        config.overrideConfig(mapOf("retrieval" to mapOf("enable_reranking" to false)))
        val lexicalResults = retriever.processRetrievalResults(question = "How is NodeA connected?")
        assertEquals("lexical", lexicalResults["retrieval_strategy"])
    }

    @Test
    fun `buildIndices persists embedding caches when caching enabled`() {
        val root = Files.createTempDirectory("youtu-graphrag-retriever-cache-enabled-test")
        val config = createTestConfig(root)
        val datasetName = "cache_enabled_ds"
        val graphPath = root.resolve("output/graphs/${datasetName}_new.json")

        writeGraph(
            graphPath = graphPath,
            relationships =
                listOf(
                    relationship(
                        sourceName = "CacheNodeA",
                        sourceLabel = "entity",
                        relation = "relates_to",
                        targetName = "CacheNodeB",
                        targetLabel = "entity",
                        chunkId = "c1",
                    ),
                ),
        )
        writeChunks(
            chunkFile = root.resolve("output/chunks/$datasetName.txt"),
            entries = listOf("c1" to "CacheNodeA relates to CacheNodeB."),
        )

        val retriever =
            createRetriever(
                datasetName = datasetName,
                config = config,
                graphPath = graphPath.toString(),
                rootDir = root,
            )

        val cacheRoot = root.resolve("retriever/faiss_cache_new").resolve(datasetName)
        assertTrue(cacheRoot.resolve("triple_embedding_cache.json").exists())
        assertTrue(cacheRoot.resolve("triple_embedding_cache.npz").exists())
        assertTrue(cacheRoot.resolve("chunk_embedding_cache.json").exists())
        assertTrue(cacheRoot.resolve("chunk_embedding_cache.npz").exists())
    }

    @Test
    fun `buildIndices skips embedding cache persistence when caching disabled`() {
        val root = Files.createTempDirectory("youtu-graphrag-retriever-cache-disabled-test")
        val config = createTestConfig(root)
        config.overrideConfig(
            mapOf(
                "retrieval" to
                    mapOf(
                        "enable_caching" to false,
                    ),
            ),
        )
        val datasetName = "cache_disabled_ds"
        val graphPath = root.resolve("output/graphs/${datasetName}_new.json")

        writeGraph(
            graphPath = graphPath,
            relationships =
                listOf(
                    relationship(
                        sourceName = "NoCacheA",
                        sourceLabel = "entity",
                        relation = "relates_to",
                        targetName = "NoCacheB",
                        targetLabel = "entity",
                        chunkId = "c1",
                    ),
                ),
        )
        writeChunks(
            chunkFile = root.resolve("output/chunks/$datasetName.txt"),
            entries = listOf("c1" to "NoCacheA relates to NoCacheB."),
        )

        val retriever =
            createRetriever(
                datasetName = datasetName,
                config = config,
                graphPath = graphPath.toString(),
                rootDir = root,
            )

        val cacheRoot = root.resolve("retriever/faiss_cache_new").resolve(datasetName)
        assertTrue(!cacheRoot.resolve("triple_embedding_cache.json").exists())
        assertTrue(!cacheRoot.resolve("triple_embedding_cache.npz").exists())
        assertTrue(!cacheRoot.resolve("chunk_embedding_cache.json").exists())
        assertTrue(!cacheRoot.resolve("chunk_embedding_cache.npz").exists())
    }

    @Test
    fun `buildIndices ingests npz embedding caches`() {
        val root = Files.createTempDirectory("youtu-graphrag-retriever-npz-cache-load-test")
        val config = createTestConfig(root)
        val datasetName = "npz_cache_load_ds"
        val graphPath = root.resolve("output/graphs/${datasetName}_new.json")

        writeGraph(
            graphPath = graphPath,
            relationships =
                listOf(
                    relationship(
                        sourceName = "PtNodeA",
                        sourceLabel = "entity",
                        relation = "relates_to",
                        targetName = "PtNodeB",
                        targetLabel = "entity",
                        chunkId = "c1",
                    ),
                ),
        )
        writeChunks(
            chunkFile = root.resolve("output/chunks/$datasetName.txt"),
            entries = listOf("c1" to "PtNodeA relates to PtNodeB."),
        )

        val cacheRoot = root.resolve("retriever/faiss_cache_new").resolve(datasetName)
        cacheRoot.createDirectories()
        val dims = HashTextEmbedder().dimensions
        val customVector = FloatArray(dims).also { vector -> vector[0] = 1.0f }
        NpzEmbeddingCache.write(
            path = cacheRoot.resolve("triple_embedding_cache.npz"),
            vectorsByKey = mapOf("[\"PtNodeA\", \"relates_to\", \"PtNodeB\"]" to customVector),
            expectedDimensions = dims,
        )
        NpzEmbeddingCache.write(
            path = cacheRoot.resolve("chunk_embedding_cache.npz"),
            vectorsByKey = mapOf("c1" to customVector),
            expectedDimensions = dims,
        )

        val retriever =
            createRetriever(
                datasetName = datasetName,
                config = config,
                graphPath = graphPath.toString(),
                rootDir = root,
            )

        val tripleCacheTree = mapper.readTree(cacheRoot.resolve("triple_embedding_cache.json").toFile())
        val chunkCacheTree = mapper.readTree(cacheRoot.resolve("chunk_embedding_cache.json").toFile())
        val tripleFirst =
            tripleCacheTree
                .path("vectors")
                .path("[\"PtNodeA\", \"relates_to\", \"PtNodeB\"]")
                .path(0)
                .asDouble()
        val chunkFirst =
            chunkCacheTree
                .path("vectors")
                .path("c1")
                .path(0)
                .asDouble()

        assertEquals(1.0, tripleFirst)
        assertEquals(1.0, chunkFirst)
        assertTrue(cacheRoot.resolve("triple_embedding_cache.npz").exists())
        assertTrue(cacheRoot.resolve("chunk_embedding_cache.npz").exists())
    }

    @Test
    fun `buildIndices throws when topK is negative`() {
        val root = Files.createTempDirectory("youtu-graphrag-retriever-negative-topk")
        val config = createTestConfig(root)
        assertFailsWith<IllegalArgumentException> {
            createRetriever(
                datasetName = "negative_topk_ds",
                config = config,
                topK = -1,
            )
        }
    }

    private fun createRetriever(
        datasetName: String,
        config: ConfigManager,
        graphPath: String = "unused_graph.json",
        rootDir: Path = Path.of("."),
        topK: Int = 5,
        recallPaths: Int = 2,
    ): KTRetriever =
        KTRetriever.createAndBuild(
            datasetName = datasetName,
            graphPath = graphPath,
            recallPaths = recallPaths,
            schemaPath = "unused_schema.json",
            topK = topK,
            mode = "agent",
            config = config,
            rootDir = rootDir,
        )

    private fun createTestConfig(root: Path): ConfigManager {
        val config = ConfigManager("config/base_config.json")
        config.overrideConfig(
            mapOf(
                "output" to
                    mapOf(
                        "base_dir" to root.resolve("output").toString(),
                        "graphs_dir" to root.resolve("output/graphs").toString(),
                        "chunks_dir" to root.resolve("output/chunks").toString(),
                        "logs_dir" to root.resolve("output/logs").toString(),
                    ),
                "retrieval" to
                    mapOf(
                        "cache_dir" to root.resolve("retriever/faiss_cache_new").toString(),
                    ),
            ),
        )
        return config
    }

    private fun writeGraph(
        graphPath: Path,
        relationships: List<GraphRelationship>,
    ) {
        graphPath.parent?.createDirectories()
        mapper.writerWithDefaultPrettyPrinter().writeValue(graphPath.toFile(), relationships)
    }

    private fun writeChunks(
        chunkFile: Path,
        entries: List<Pair<String, String>>,
    ) {
        chunkFile.parent?.createDirectories()
        val content =
            buildString {
                entries.forEach { (chunkId, text) ->
                    append("id: ")
                    append(chunkId)
                    append('\t')
                    append("Chunk: ")
                    append(text)
                    append('\n')
                }
            }
        chunkFile.writeText(content)
    }

    private fun relationship(
        sourceName: String,
        sourceLabel: String,
        relation: String,
        targetName: String,
        targetLabel: String,
        chunkId: String,
    ): GraphRelationship =
        GraphRelationship(
            startNode =
                GraphNode(
                    label = sourceLabel,
                    properties =
                        mapOf(
                            "name" to sourceName,
                            "chunk id" to chunkId,
                            "schema_type" to sourceLabel,
                        ),
                ),
            relation = relation,
            endNode =
                GraphNode(
                    label = targetLabel,
                    properties =
                        mapOf(
                            "name" to targetName,
                            "chunk id" to chunkId,
                            "schema_type" to targetLabel,
                        ),
                ),
        )
}
