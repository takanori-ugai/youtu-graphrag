package com.youtu.graphrag.cli

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.youtu.graphrag.shared.config.ConfigManager
import com.youtu.graphrag.shared.config.ConfigProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import kotlin.system.exitProcess

@Command(
    name = "youtu-graphrag",
    description = ["Youtu-GraphRAG Kotlin CLI"],
    mixinStandardHelpOptions = true,
)
class MainCommand : Runnable {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Option(
        names = ["--config"],
        description = ["Path to configuration file"],
        defaultValue = "config/base_config.yaml",
    )
    lateinit var configPath: String

    @Option(
        names = ["--datasets"],
        arity = "1..*",
        description = ["List of datasets to process"],
        defaultValue = "demo",
    )
    var datasets: List<String> = listOf("demo")

    @Option(
        names = ["--override"],
        description = ["JSON string with configuration overrides"],
    )
    var overrideJson: String? = null

    override fun run() {
        val config =
            try {
                ConfigProvider.reloadConfig(configPath)
            } catch (error: Exception) {
                throw CommandLine.ParameterException(
                    CommandLine(this),
                    "Failed to load configuration: ${error.message}",
                    error,
                )
            }

        applyOverridesIfPresent(config)
        setupEnvironment(config)

        if (config.triggers.constructorTrigger) {
            logger.info { "Starting knowledge graph construction (conversion scaffold stage)..." }
        }

        if (config.triggers.retrieveTrigger) {
            logger.info { "Starting retrieval and QA (conversion scaffold stage)..." }
        }
    }

    private fun applyOverridesIfPresent(config: ConfigManager) {
        val overrideInput = overrideJson ?: return

        try {
            val overrides: Map<String, Any?> =
                objectMapper.readValue(overrideInput, object : TypeReference<Map<String, Any?>>() {})
            config.overrideConfig(overrides)
            logger.info { "Applied configuration overrides" }
        } catch (error: Exception) {
            throw CommandLine.ParameterException(
                CommandLine(this),
                "Invalid JSON in --override argument: ${error.message}",
                error,
            )
        }
    }

    private fun setupEnvironment(config: ConfigManager) {
        config.createOutputDirectories()

        logger.info { "Youtu-GraphRAG Kotlin initialized" }
        logger.info { "Mode: ${config.triggers.mode}" }
        logger.info { "Constructor enabled: ${config.triggers.constructorTrigger}" }
        logger.info { "Retriever enabled: ${config.triggers.retrieveTrigger}" }
        logger.info { "Datasets: ${datasets.joinToString(", ")}" }
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(MainCommand()).execute(*args)
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}
