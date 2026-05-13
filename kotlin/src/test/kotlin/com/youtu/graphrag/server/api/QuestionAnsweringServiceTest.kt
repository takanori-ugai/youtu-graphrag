package com.youtu.graphrag.server.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.youtu.graphrag.shared.config.ConfigManager
import com.youtu.graphrag.shared.llm.LlmClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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

        val qaService =
            QuestionAnsweringService(
                config = config,
                rootDir = root,
                llmClient = createMockLlmClient(),
            )
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

        val qaService =
            QuestionAnsweringService(
                config = config,
                rootDir = root,
                llmClient = createMockLlmClient(),
            )
        val response =
            runBlocking {
                qaService.answerQuestion("missing_ds", "What does demo mention?")
            }

        assertTrue(response.answer.isNotBlank())
        assertTrue(response.retrievedTriples.isNotEmpty())
    }

    @Test
    fun `answer question supports multi-subquestion decomposition and subquestion reasoning steps`() {
        val root = createRootDir()
        val config = createTestConfig(root)

        writeCorpus(
            root = root,
            datasetName = "multi_ds",
            documents =
                listOf(
                    mapOf(
                        "title" to "Project Alpha",
                        "text" to "Alice leads Project Alpha. Project Alpha is based in Tokyo.",
                    ),
                ),
        )

        val constructionService = GraphConstructionService(config = config, rootDir = root)
        constructionService.constructGraph("multi_ds")

        val qaService =
            QuestionAnsweringService(
                config = config,
                rootDir = root,
                llmClient = createMockLlmClient(),
            )
        val updates = mutableListOf<QaStageUpdate>()
        val response =
            runBlocking {
                qaService.answerQuestion(
                    "multi_ds",
                    "Who leads Project Alpha and where is Project Alpha based?",
                    onQaUpdate = { update -> updates.add(update) },
                )
            }

        assertTrue(response.subQuestions.size >= 2)
        val subQuestionSteps = response.reasoningSteps.filter { it.type == "sub_question" }
        assertTrue(subQuestionSteps.size >= 2)
        assertTrue(subQuestionSteps.all { step -> step.question.isNotBlank() })

        val decomposeUpdate = updates.firstOrNull { update -> update.stage == "decompose" }
        assertNotNull(decomposeUpdate)
        assertTrue(decomposeUpdate.payload["parallel_subquestions"] == true)
    }

    @Test
    fun `answer question uses injected llm client output when available`() {
        val root = createRootDir()
        val config = createTestConfig(root)
        config.overrideConfig(
            mapOf(
                "triggers" to mapOf("mode" to "noagent"),
            ),
        )

        writeCorpus(
            root = root,
            datasetName = "llm_ds",
            documents =
                listOf(
                    mapOf(
                        "title" to "Project Alpha",
                        "text" to "Alice leads Project Alpha.",
                    ),
                ),
        )

        val constructionService = GraphConstructionService(config = config, rootDir = root)
        constructionService.constructGraph("llm_ds")

        val qaService =
            QuestionAnsweringService(
                config = config,
                rootDir = root,
                llmClient =
                    object : LlmClient {
                        override fun complete(prompt: String): String = "LLM injected answer"
                    },
            )

        val response =
            runBlocking {
                qaService.answerQuestion(
                    "llm_ds",
                    "Who leads Project Alpha?",
                )
            }

        assertTrue(response.answer == "LLM injected answer")
    }

    @Test
    fun `answer question preserves initial answer when ircot response has no new-query marker`() {
        val root = createRootDir()
        val config = createTestConfig(root)

        writeCorpus(
            root = root,
            datasetName = "ircot_fallback_ds",
            documents =
                listOf(
                    mapOf(
                        "title" to "Project Alpha",
                        "text" to "Alice leads Project Alpha.",
                    ),
                ),
        )

        val constructionService = GraphConstructionService(config = config, rootDir = root)
        constructionService.constructGraph("ircot_fallback_ds")

        var retrievalPromptCalls = 0
        val qaService =
            QuestionAnsweringService(
                config = config,
                rootDir = root,
                llmClient =
                    object : LlmClient {
                        override fun complete(prompt: String): String =
                            when {
                                prompt.contains("decompose", ignoreCase = true) -> {
                                    """
                                    {
                                      "sub_questions": [{"sub-question": "Who leads Project Alpha?"}],
                                      "involved_types": {"nodes": [], "relations": [], "attributes": []}
                                    }
                                    """.trimIndent()
                                }

                                prompt.contains("The new query is:", ignoreCase = true) -> {
                                    "I need one more clue."
                                }

                                else -> {
                                    retrievalPromptCalls += 1
                                    if (retrievalPromptCalls == 1) {
                                        "Seed answer from initial context."
                                    } else {
                                        ""
                                    }
                                }
                            }
                    },
            )

        val response =
            runBlocking {
                qaService.answerQuestion(
                    "ircot_fallback_ds",
                    "Who leads Project Alpha?",
                )
            }

        assertTrue(response.answer == "Seed answer from initial context.")
        assertTrue(response.reasoningSteps.count { it.type == "ircot_step" } == 1)
    }

    @Test
    fun `answer question throws when no graph artifacts exist`() {
        val root = createRootDir()
        val config = createTestConfig(root)
        val qaService =
            QuestionAnsweringService(
                config = config,
                rootDir = root,
                llmClient = createMockLlmClient(),
            )

        assertFailsWith<GraphArtifactNotFoundException> {
            runBlocking {
                qaService.answerQuestion("absent_ds", "Any question")
            }
        }
    }

    private fun createMockLlmClient(): LlmClient =
        object : LlmClient {
            override fun complete(prompt: String): String =
                when {
                    prompt.contains("decompose") -> {
                        """
                        {
                          "sub_questions": [
                            {"sub-question": "Sub-question 1"},
                            {"sub-question": "Sub-question 2"}
                          ]
                        }
                        """.trimIndent()
                    }

                    prompt.contains("thought") || prompt.contains("step") -> {
                        "Step reasoning details with thought process."
                    }

                    else -> {
                        "Mocked deterministic answer."
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

    private fun createRootDir(): Path = Files.createTempDirectory("youtu-graphrag-question-answering-test")
}
