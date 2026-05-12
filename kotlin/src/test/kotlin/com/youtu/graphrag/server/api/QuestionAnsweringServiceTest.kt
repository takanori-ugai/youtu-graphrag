package com.youtu.graphrag.server.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.youtu.graphrag.shared.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuestionAnsweringServiceTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `answer question returns triples chunks and qa response fields`() {
        val root = createRootDir()
        val config = createTestConfig(root)

        writeCorpus(
            root = root,
            datasetName = "qa_ds",
            documents =
                listOf(
                    mapOf(
                        "title" to "FC Barcelona",
                        "text" to "Lionel Messi played for FC Barcelona and won many titles.",
                    ),
                ),
        )

        val constructionService = GraphConstructionService(config = config, rootDir = root)
        constructionService.constructGraph("qa_ds")

        val qaService = QuestionAnsweringService(config = config, rootDir = root)
        val callbackStages = mutableListOf<String>()
        val response =
            runBlocking {
                qaService.answerQuestion(
                    "qa_ds",
                    "Who played for FC Barcelona?",
                    onQaUpdate = { update -> callbackStages.add(update.stage) },
                )
            }

        assertTrue(response.answer.isNotBlank())
        assertTrue(response.subQuestions.isNotEmpty())
        assertTrue(response.reasoningSteps.isNotEmpty())
        assertTrue(response.retrievedTriples.isNotEmpty())
        assertTrue(response.retrievedChunks.isNotEmpty())
        assertTrue(response.visualizationData["subqueries"] != null)
        assertTrue(response.visualizationData["knowledge_graph"] != null)
        assertTrue(response.visualizationData["reasoning_flow"] != null)
        assertTrue(response.visualizationData["retrieval_details"]?.jsonObject != null)
        assertTrue(response.reasoningSteps.any { it.type == "ircot_step" })
        assertTrue("decompose" in callbackStages)
        assertTrue("sub_question" in callbackStages)
        assertTrue("ircot_start" in callbackStages)
        assertTrue("ircot" in callbackStages)
    }

    @Test
    fun `answer question falls back to demo graph when dataset graph missing`() {
        val root = createRootDir()
        val config = createTestConfig(root)

        root.resolve("data/demo").createDirectories()
        mapper.writerWithDefaultPrettyPrinter().writeValue(
            root.resolve("data/demo/demo_corpus.json").toFile(),
            listOf(
                mapOf(
                    "title" to "Demo Title",
                    "text" to "Demo graph fallback content about a sample entity.",
                ),
            ),
        )

        val constructionService = GraphConstructionService(config = config, rootDir = root)
        constructionService.constructGraph("demo")

        assertTrue(root.resolve("output/graphs/demo_new.json").exists())

        val qaService = QuestionAnsweringService(config = config, rootDir = root)
        val response =
            runBlocking {
                qaService.answerQuestion("missing_ds", "What does demo mention?")
            }

        assertTrue(response.answer.isNotBlank())
        assertTrue(response.retrievedTriples.isNotEmpty())
    }

    @Test
    fun `answer question throws when no graph artifacts exist`() {
        val root = createRootDir()
        val config = createTestConfig(root)
        val qaService = QuestionAnsweringService(config = config, rootDir = root)

        assertFailsWith<GraphArtifactNotFoundException> {
            runBlocking {
                qaService.answerQuestion("absent_ds", "Any question")
            }
        }
    }

    private fun createTestConfig(root: Path): ConfigManager {
        val config = ConfigManager("config/base_config.yaml")
        config.overrideConfig(
            mapOf(
                "construction" to
                    mapOf(
                        "chunk_size" to 64,
                        "overlap" to 16,
                        "datasets_no_chunk" to emptyList<String>(),
                    ),
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

    private fun writeCorpus(
        root: Path,
        datasetName: String,
        documents: List<Map<String, String>>,
    ) {
        val corpusDir = root.resolve("data/uploaded/$datasetName")
        corpusDir.createDirectories()
        mapper.writerWithDefaultPrettyPrinter().writeValue(corpusDir.resolve("corpus.json").toFile(), documents)
    }

    private fun createRootDir(): Path {
        return Files.createTempDirectory("youtu-graphrag-question-answering-test")
    }
}
