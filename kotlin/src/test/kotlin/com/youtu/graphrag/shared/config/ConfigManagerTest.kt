package com.youtu.graphrag.shared.config

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigManagerTest {
    @Test
    fun `loads base config and key dataset contracts`() {
        val config = ConfigManager("config/base_config.yaml")

        assertTrue(config.datasets.containsKey("demo"))
        assertEquals("agent", config.triggers.mode)
        assertEquals(
            "output/graphs/demo_new.json",
            config.getDatasetConfig("demo").graphOutput,
        )
        assertTrue(config.prompts["construction"]?.containsKey("general") == true)
    }

    @Test
    fun `applies nested overrides with python-compatible keys`() {
        val config = ConfigManager("config/base_config.yaml")

        config.overrideConfig(
            mapOf(
                "triggers" to mapOf("mode" to "noagent"),
                "retrieval" to mapOf("top_k" to 9),
            ),
        )

        assertEquals("noagent", config.triggers.mode)
        assertEquals(9, config.retrieval.topK)
    }

    @Test
    fun `formats prompts and fails on missing variable`() {
        val config = ConfigManager("config/base_config.yaml")

        val rendered =
            config.getPromptFormatted(
                category = "retrieval",
                promptType = "general",
                variables =
                    mapOf(
                        "question" to "What is the capital of France?",
                        "context" to "Paris is the capital city of France.",
                    ),
            )

        assertTrue(rendered.contains("What is the capital of France?"))
        assertTrue(rendered.contains("Paris is the capital city of France."))

        assertFailsWith<IllegalArgumentException> {
            config.getPromptFormatted(
                category = "retrieval",
                promptType = "general",
                variables = mapOf("question" to "Missing context"),
            )
        }
    }

    @Test
    fun `creates output directories based on config`() {
        val tempRoot = createTempDirectory("youtu-graphrag-config-test")
        val baseDir = tempRoot.resolve("output")

        val config = ConfigManager("config/base_config.yaml")
        config.overrideConfig(
            mapOf(
                "output" to
                    mapOf(
                        "base_dir" to baseDir.toString(),
                        "graphs_dir" to baseDir.resolve("graphs").toString(),
                        "chunks_dir" to baseDir.resolve("chunks").toString(),
                        "logs_dir" to baseDir.resolve("logs").toString(),
                    ),
            ),
        )

        config.createOutputDirectories()

        assertTrue(Files.isDirectory(baseDir))
        assertTrue(Files.isDirectory(baseDir.resolve("graphs")))
        assertTrue(Files.isDirectory(baseDir.resolve("chunks")))
        assertTrue(Files.isDirectory(baseDir.resolve("logs")))
    }
}
