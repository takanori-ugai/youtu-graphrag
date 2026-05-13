package com.youtu.graphrag.shared.retriever

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.youtu.graphrag.shared.config.ConfigManager
import com.youtu.graphrag.shared.graph.GraphRelationship
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class RetrievalChunkFixture(
    val id: String,
    val text: String,
)

private data class RetrievalExpectedFixture(
    val triples: List<String>,
    val chunkIds: List<String>,
    val chunkContents: List<String>,
    val chunkRetrievalPrefixes: List<String>,
    val retrievalStrategyPrefix: String,
)

private data class RetrievalParityFixture(
    val name: String,
    val question: String,
    val involvedTypes: Map<String, List<String>>,
    val configOverrides: Map<String, Any?> = emptyMap(),
    val topK: Int,
    val recallPaths: Int,
    val graph: List<GraphRelationship>,
    val chunks: List<RetrievalChunkFixture>,
    val expected: RetrievalExpectedFixture,
)

class RetrievalFixtureParityTest {
    private val mapper =
        ObjectMapper()
            .registerKotlinModule()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    @Test
    fun `retrieval fixtures lock parity fields and strategy metadata`() {
        val fixtures = loadFixtures()
        assertTrue(fixtures.isNotEmpty())

        fixtures.forEach { fixture ->
            val root = Files.createTempDirectory("youtu-graphrag-retrieval-fixture-${fixture.name}")
            val config = createTestConfig(root)
            if (fixture.configOverrides.isNotEmpty()) {
                config.overrideConfig(fixture.configOverrides)
            }
            val datasetName = "fixture_${fixture.name}"
            val graphPath = root.resolve("output/graphs/${datasetName}_new.json")
            val chunkPath = root.resolve("output/chunks/$datasetName.txt")

            graphPath.parent?.createDirectories()
            mapper.writerWithDefaultPrettyPrinter().writeValue(graphPath.toFile(), fixture.graph)
            writeChunks(chunkPath = chunkPath, chunks = fixture.chunks)

            val retriever =
                KTRetriever.createAndBuild(
                    datasetName = datasetName,
                    graphPath = graphPath.toString(),
                    recallPaths = fixture.recallPaths,
                    schemaPath = "unused_schema.json",
                    topK = fixture.topK,
                    mode = "agent",
                    config = config,
                    rootDir = root,
                )

            val result =
                retriever.processRetrievalResults(
                    question = fixture.question,
                    involvedTypes = fixture.involvedTypes,
                )

            val triples =
                (result["triples"] as? List<*>)?.map { value -> value.toString() }
                    ?: error("Expected 'triples' list in result for fixture '${fixture.name}'")
            val chunkIds =
                (result["chunk_ids"] as? List<*>)?.map { value -> value.toString() }
                    ?: error("Expected 'chunk_ids' list in result for fixture '${fixture.name}'")
            val chunkContents =
                (result["chunk_contents"] as? List<*>)?.map { value -> value.toString() }
                    ?: error("Expected 'chunk_contents' list in result for fixture '${fixture.name}'")
            val chunkRetrievalLines =
                (result["chunk_retrieval_results"] as? List<*>)?.map { value -> value.toString() }
                    ?: error("Expected 'chunk_retrieval_results' list in result for fixture '${fixture.name}'")
            val retrievalStrategy =
                result["retrieval_strategy"]?.toString()
                    ?: error("Expected 'retrieval_strategy' in result for fixture '${fixture.name}'")
            val resultTopK = (result["top_k"] as? Number)?.toInt()
            val resultRecallPaths = (result["recall_paths"] as? Number)?.toInt()

            assertEquals(fixture.expected.triples, triples, "triples parity failed for fixture '${fixture.name}'")
            assertEquals(fixture.expected.chunkIds, chunkIds, "chunk_ids parity failed for fixture '${fixture.name}'")
            assertEquals(
                fixture.expected.chunkContents,
                chunkContents,
                "chunk_contents parity failed for fixture '${fixture.name}'",
            )
            assertEquals(
                fixture.expected.chunkRetrievalPrefixes.size,
                chunkRetrievalLines.size,
                "chunk_retrieval_results size mismatch for fixture '${fixture.name}'",
            )
            fixture.expected.chunkRetrievalPrefixes.zip(chunkRetrievalLines).forEach { (expectedPrefix, actualLine) ->
                assertTrue(
                    actualLine.startsWith(expectedPrefix),
                    "chunk_retrieval_results parity failed for fixture '${fixture.name}': expected prefix '$expectedPrefix', actual '$actualLine'",
                )
            }
            assertTrue(
                retrievalStrategy.startsWith(fixture.expected.retrievalStrategyPrefix),
                "retrieval_strategy parity failed for fixture '${fixture.name}': '$retrievalStrategy'",
            )
            assertEquals(fixture.topK, resultTopK, "top_k metadata mismatch for fixture '${fixture.name}'")
            assertEquals(fixture.recallPaths, resultRecallPaths, "recall_paths metadata mismatch for fixture '${fixture.name}'")
        }
    }

    private fun loadFixtures(): List<RetrievalParityFixture> {
        val resourcePath = "fixtures/retrieval/retrieval_parity_fixtures.json"
        val stream =
            requireNotNull(javaClass.classLoader.getResourceAsStream(resourcePath)) {
                "Fixture resource not found: $resourcePath"
            }
        return stream.use { input ->
            mapper.readValue(input, object : TypeReference<List<RetrievalParityFixture>>() {})
        }
    }

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
                        "enable_caching" to false,
                    ),
            ),
        )
        return config
    }

    private fun writeChunks(
        chunkPath: Path,
        chunks: List<RetrievalChunkFixture>,
    ) {
        chunkPath.parent?.createDirectories()
        val content =
            buildString {
                chunks.forEach { chunk ->
                    append("id: ")
                    append(chunk.id)
                    append('\t')
                    append("Chunk: ")
                    append(chunk.text)
                    append('\n')
                }
            }
        chunkPath.writeText(content)
    }
}
