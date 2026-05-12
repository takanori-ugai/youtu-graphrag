package com.youtu.graphrag.cli

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainCommandTest {
    @Test
    fun `loadQaItems extracts common qa shapes`() {
        val tempDir = Files.createTempDirectory("main-command-qa")
        val qaFile = tempDir.resolve("qa.json")
        qaFile.writeText(
            """
            {
              "qa_pairs": [
                {"question": "Who leads the team?", "answer": "Alice"},
                {"query": "Where is the team based?", "gold_answer": "Tokyo"}
              ],
              "items": [
                {"q": "What does the team build?", "output": "Knowledge graph system"}
              ]
            }
            """.trimIndent(),
        )

        val items = loadQaItems(qaFile.toString())

        assertEquals(3, items.size)
        assertEquals("Who leads the team?", items[0].question)
        assertEquals("Alice", items[0].referenceAnswer)
        assertEquals("Where is the team based?", items[1].question)
        assertEquals("Tokyo", items[1].referenceAnswer)
        assertEquals("What does the team build?", items[2].question)
    }

    @Test
    fun `main command executes constructor and retrieval workflows`() {
        val tempDir = Files.createTempDirectory("main-command-run")

        val dataDir = tempDir.resolve("data/demo").also { it.createDirectories() }
        val schemasDir = tempDir.resolve("schemas").also { it.createDirectories() }
        val outputBase = tempDir.resolve("output").also { it.createDirectories() }
        val outputGraphs = outputBase.resolve("graphs").also { it.createDirectories() }
        val outputChunks = outputBase.resolve("chunks").also { it.createDirectories() }
        val outputLogs = outputBase.resolve("logs").also { it.createDirectories() }
        val cacheDir = tempDir.resolve("retriever/faiss_cache_new").also { it.createDirectories() }
        val configDir = tempDir.resolve("config").also { it.createDirectories() }

        val corpusPath = dataDir.resolve("demo_corpus.json")
        val qaPath = dataDir.resolve("demo.json")
        val schemaPath = schemasDir.resolve("demo.json")
        val graphOutputPath = outputGraphs.resolve("demo_new.json")
        val chunkOutputPath = outputChunks.resolve("demo.txt")
        val qaResultPath = outputLogs.resolve("demo_qa_results.json")

        corpusPath.writeText(
            """
            [
              {
                "title": "Project Alpha",
                "text": "Alice leads Project Alpha in Tokyo."
              }
            ]
            """.trimIndent(),
        )
        qaPath.writeText(
            """
            [
              {
                "question": "Who leads Project Alpha?",
                "answer": "Alice"
              }
            ]
            """.trimIndent(),
        )
        schemaPath.writeText(
            """
            {
              "Nodes": ["person", "organization", "location"],
              "Relations": ["leads", "located_in"],
              "Attributes": ["name"]
            }
            """.trimIndent(),
        )

        val configPath = configDir.resolve("base_config.yaml")
        configPath.writeText(
            """
            datasets:
              demo:
                corpus_path: ${corpusPath.toAbsolutePath()}
                qa_path: ${qaPath.toAbsolutePath()}
                schema_path: ${schemaPath.toAbsolutePath()}
                graph_output: ${graphOutputPath.toAbsolutePath()}
            triggers:
              constructor_trigger: true
              retrieve_trigger: true
              mode: noagent
            construction:
              mode: noagent
            retrieval:
              top_k_filter: 5
              recall_paths: 2
              cache_dir: ${cacheDir.toAbsolutePath()}
            output:
              base_dir: ${outputBase.toAbsolutePath()}
              graphs_dir: ${outputGraphs.toAbsolutePath()}
              chunks_dir: ${outputChunks.toAbsolutePath()}
              logs_dir: ${outputLogs.toAbsolutePath()}
            """.trimIndent(),
        )

        val command = MainCommand()
        command.configPath = configPath.toString()
        command.datasets = listOf("demo")

        command.run()

        assertTrue(graphOutputPath.exists(), "Graph output should be generated")
        assertTrue(chunkOutputPath.exists(), "Chunk output should be generated")
        assertTrue(qaResultPath.exists(), "QA result output should be generated")

        val resultJson = qaResultPath.readText()
        assertTrue(resultJson.contains("Who leads Project Alpha?"))
        assertTrue(resultJson.contains("Alice"))
        assertTrue(resultJson.contains("\"eval_result\" : \"1\""))
        assertTrue(resultJson.contains("\"reasoning_steps\""))
    }
}
