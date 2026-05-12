package com.youtu.graphrag.shared.config

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

class ConfigManager(
    configPath: String? = null,
) {
    private val logger = KotlinLogging.logger {}
    private val yamlMapper: ObjectMapper =
        ObjectMapper(YAMLFactory())
            .registerKotlinModule()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val jsonMapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val configPath: Path = Path.of(configPath ?: defaultConfigPath())
    private var rawConfig: MutableMap<String, Any?> = mutableMapOf()
    private var parsedConfig: AppConfig = AppConfig()

    init {
        loadConfig()
    }

    val datasets: Map<String, DatasetConfig>
        get() = parsedConfig.datasets

    val triggers: TriggersConfig
        get() = parsedConfig.triggers

    val construction: ConstructionConfig
        get() = parsedConfig.construction

    val treeComm: TreeCommConfig
        get() = parsedConfig.construction.treeComm

    val retrieval: RetrievalConfig
        get() = parsedConfig.retrieval

    val embeddings: EmbeddingsConfig
        get() = parsedConfig.embeddings

    val nlp: NlpConfig
        get() = parsedConfig.nlp

    val prompts: Map<String, Map<String, String>>
        get() = parsedConfig.prompts

    val output: OutputConfig
        get() = parsedConfig.output

    val performance: PerformanceConfig
        get() = parsedConfig.performance

    val evaluation: EvaluationConfig
        get() = parsedConfig.evaluation

    fun loadConfig() {
        if (!Files.exists(configPath)) {
            throw IllegalArgumentException("Configuration file not found: $configPath")
        }

        rawConfig =
            yamlMapper.readValue(configPath.toFile(), object : TypeReference<MutableMap<String, Any?>>() {})

        parseConfig()
        validateConfig()

        logger.info { "Configuration loaded successfully from $configPath" }
    }

    fun getDatasetConfig(datasetName: String): DatasetConfig =
        datasets[datasetName]
            ?: throw IllegalArgumentException("Dataset '$datasetName' not found in configuration")

    fun getPrompt(
        category: String,
        promptType: String,
    ): String =
        prompts[category]?.get(promptType)
            ?: throw IllegalArgumentException("Prompt not found: $category.$promptType")

    fun getPromptFormatted(
        category: String,
        promptType: String,
        variables: Map<String, Any?>,
    ): String {
        var rendered = getPrompt(category, promptType)
        for ((key, value) in variables) {
            rendered = rendered.replace("{$key}", value?.toString() ?: "")
        }

        val missing = PLACEHOLDER_REGEX.find(rendered)?.groupValues?.get(1)
        if (missing != null) {
            throw IllegalArgumentException(
                "Missing variable '$missing' for prompt $category.$promptType",
            )
        }

        return rendered
    }

    fun overrideConfig(overrides: Map<String, Any?>) {
        deepMerge(rawConfig, overrides)
        parseConfig()
        validateConfig()
    }

    fun saveConfig(outputPath: String) {
        val path = Path.of(outputPath)
        Files.createDirectories(path.parent ?: Path.of("."))

        if (path.toString().endsWith(".json")) {
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rawConfig)
            return
        }

        yamlMapper.writeValue(path.toFile(), rawConfig)
    }

    fun toMap(): Map<String, Any?> =
        jsonMapper.readValue(
            jsonMapper.writeValueAsBytes(rawConfig),
            object : TypeReference<Map<String, Any?>>() {},
        )

    fun createOutputDirectories() {
        val directories = listOf(output.baseDir, output.graphsDir, output.chunksDir, output.logsDir)
        directories.forEach { directory -> Files.createDirectories(Path.of(directory)) }
    }

    private fun parseConfig() {
        parsedConfig = yamlMapper.convertValue(rawConfig, AppConfig::class.java)
    }

    private fun validateConfig() {
        datasets.forEach { (datasetName, datasetConfig) ->
            if (!Files.exists(Path.of(datasetConfig.corpusPath))) {
                logger.warn { "Corpus path not found for $datasetName: ${datasetConfig.corpusPath}" }
            }
            if (!Files.exists(Path.of(datasetConfig.schemaPath))) {
                logger.warn { "Schema path not found for $datasetName: ${datasetConfig.schemaPath}" }
            }
        }

        val validModes = setOf("agent", "noagent")
        if (triggers.mode !in validModes) {
            throw IllegalArgumentException("Invalid mode: ${triggers.mode}. Must be one of $validModes")
        }
        if (construction.mode !in validModes) {
            throw IllegalArgumentException("Invalid construction mode: ${construction.mode}")
        }
        if (retrieval.topK <= 0) {
            throw IllegalArgumentException("top_k must be positive")
        }
        if (treeComm.structWeight !in 0.0..1.0) {
            throw IllegalArgumentException("struct_weight must be between 0 and 1")
        }
    }

    private fun deepMerge(
        base: MutableMap<String, Any?>,
        overrides: Map<String, Any?>,
    ) {
        overrides.forEach { (key, overrideValue) ->
            val baseValue = base[key]
            if (baseValue is Map<*, *> && overrideValue is Map<*, *>) {
                val mergedChild = toStringAnyMap(baseValue).toMutableMap()
                deepMerge(mergedChild, toStringAnyMap(overrideValue))
                base[key] = mergedChild
            } else {
                base[key] = overrideValue
            }
        }
    }

    private fun toStringAnyMap(map: Map<*, *>): Map<String, Any?> = map.entries.associate { (key, value) -> key.toString() to value }

    private fun defaultConfigPath(): String = "config/base_config.yaml"

    companion object {
        private val PLACEHOLDER_REGEX = Regex("\\{([^{}]+)}")
    }
}
