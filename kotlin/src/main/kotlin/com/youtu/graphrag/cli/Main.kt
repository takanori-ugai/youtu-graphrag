package com.youtu.graphrag.cli

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.youtu.graphrag.server.api.QuestionAnsweringService
import com.youtu.graphrag.shared.config.ConfigManager
import com.youtu.graphrag.shared.config.ConfigProvider
import com.youtu.graphrag.shared.constructor.KTBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.system.exitProcess

data class QaItem(
    val question: String,
    val referenceAnswer: String? = null,
)

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

        runConstructorWorkflow(config)
        runRetrievalWorkflow(config)
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

    private fun runConstructorWorkflow(config: ConfigManager) {
        if (!config.triggers.constructorTrigger) {
            logger.info { "Constructor workflow disabled by triggers.constructor_trigger=false" }
            return
        }

        logger.info { "Starting knowledge graph construction..." }
        datasets.forEach { datasetName ->
            try {
                val datasetConfig = config.getDatasetConfig(datasetName)
                logger.info { "Building knowledge graph for dataset: $datasetName" }
                clearCacheFiles(datasetName, config)

                val builder =
                    KTBuilder(
                        datasetName = datasetName,
                        schemaPath = datasetConfig.schemaPath,
                        mode = config.construction.mode,
                        config = config,
                    )
                builder.buildKnowledgeGraph(datasetConfig.corpusPath)
                logger.info { "Successfully built knowledge graph for dataset: $datasetName" }
            } catch (error: Exception) {
                logger.error(error) { "Failed to build knowledge graph for dataset: $datasetName" }
            }
        }
    }

    private fun runRetrievalWorkflow(config: ConfigManager) {
        if (!config.triggers.retrieveTrigger) {
            logger.info { "Retrieval workflow disabled by triggers.retrieve_trigger=false" }
            return
        }

        logger.info { "Starting retrieval and QA..." }
        datasets.forEach { datasetName ->
            try {
                val datasetConfig = config.getDatasetConfig(datasetName)
                val qaItems = loadQaItems(datasetConfig.qaPath)
                if (qaItems.isEmpty()) {
                    logger.warn { "No questions found for dataset '$datasetName' at ${datasetConfig.qaPath}" }
                    return@forEach
                }

                val qaService = QuestionAnsweringService(config = config)
                var evaluatedCount = 0
                var correctCount = 0

                val qaResults =
                    runBlocking {
                        val results = mutableListOf<Map<String, Any?>>()
                        qaItems.forEachIndexed { index, qaItem ->
                            val response =
                                qaService.answerQuestion(
                                    datasetName = datasetName,
                                    question = qaItem.question,
                                )
                            val evalResult = computeEvalResult(qaItem.referenceAnswer, response.answer)
                            if (evalResult != null) {
                                evaluatedCount += 1
                                if (evalResult == "1") {
                                    correctCount += 1
                                }
                            }

                            val result =
                                mapOf(
                                    "question" to qaItem.question,
                                    "reference_answer" to qaItem.referenceAnswer,
                                    "answer" to response.answer,
                                    "eval_result" to evalResult,
                                    "sub_questions" to response.subQuestions,
                                    "retrieved_triples" to response.retrievedTriples,
                                    "retrieved_chunks" to response.retrievedChunks,
                                    "reasoning_steps" to
                                        response.reasoningSteps.map { step ->
                                            mapOf(
                                                "type" to step.type,
                                                "question" to step.question,
                                                "triples" to step.triples,
                                                "triples_count" to step.triplesCount,
                                                "chunk_contents" to step.chunkContents,
                                                "chunks_count" to step.chunksCount,
                                                "processing_time" to step.processingTime,
                                                "thought" to step.thought,
                                            )
                                        },
                                    "visualization_data" to response.visualizationData.toString(),
                                    "triples_count" to response.retrievedTriples.size,
                                    "chunks_count" to response.retrievedChunks.size,
                                )
                            logger.info {
                                "Answered question ${index + 1}/${qaItems.size} for dataset '$datasetName'"
                            }
                            results.add(result)
                        }
                        results
                    }

                if (evaluatedCount > 0) {
                    val accuracy = correctCount.toDouble() / evaluatedCount.toDouble() * 100.0
                    logger.info {
                        "Evaluation summary for '$datasetName': $correctCount/$evaluatedCount correct (${String.format("%.2f", accuracy)}%)"
                    }
                }

                val logsDir = resolvePath(config.output.logsDir).also { it.createDirectories() }
                val outputFile = logsDir.resolve("${datasetName}_qa_results.json")
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), qaResults)
                logger.info { "Saved QA results for '$datasetName' to $outputFile" }
            } catch (error: Exception) {
                logger.error(error) { "Retrieval workflow failed for dataset '$datasetName'" }
            }
        }
    }

    private fun clearCacheFiles(
        datasetName: String,
        config: ConfigManager,
    ) {
        deleteRecursivelyIfExists(resolvePath(config.retrieval.cacheDir).resolve(datasetName))
        deleteRecursivelyIfExists(resolvePath(config.output.chunksDir).resolve("$datasetName.txt"))
        deleteRecursivelyIfExists(resolvePath(config.output.graphsDir).resolve("${datasetName}_new.json"))

        deleteDatasetPrefixEntries(resolvePath(config.output.logsDir), datasetName)
        deleteDatasetPrefixEntries(resolvePath(config.output.chunksDir), datasetName)
        deleteDatasetPrefixEntries(resolvePath(config.output.graphsDir), datasetName)
    }

    private fun deleteDatasetPrefixEntries(
        directory: Path,
        datasetName: String,
    ) {
        if (!directory.exists() || !directory.isDirectory()) {
            return
        }

        val prefix = "${datasetName}_"
        directory.listDirectoryEntries().forEach { entry ->
            if (entry.fileName.toString().startsWith(prefix)) {
                deleteRecursivelyIfExists(entry)
            }
        }
    }

    private fun deleteRecursivelyIfExists(path: Path) {
        if (!path.exists()) {
            return
        }

        if (path.isDirectory()) {
            path.listDirectoryEntries().forEach { child ->
                deleteRecursivelyIfExists(child)
            }
        }

        path.deleteIfExists()
    }

    private fun resolvePath(path: String): Path {
        val candidate = Path.of(path)
        return if (candidate.isAbsolute) candidate else Path.of(".").resolve(candidate)
    }

    private fun computeEvalResult(
        referenceAnswer: String?,
        generatedAnswer: String,
    ): String? {
        val reference = referenceAnswer?.trim().orEmpty()
        if (reference.isBlank()) {
            return null
        }

        val normalizedReference = normalizeForEval(reference)
        val normalizedGenerated = normalizeForEval(generatedAnswer)
        if (normalizedReference.isBlank() || normalizedGenerated.isBlank()) {
            return "0"
        }

        return if (
            normalizedReference == normalizedGenerated ||
            normalizedGenerated.contains(normalizedReference) ||
            normalizedReference.contains(normalizedGenerated)
        ) {
            "1"
        } else {
            "0"
        }
    }

    private fun normalizeForEval(text: String): String =
        text
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}

internal fun loadQaItems(
    qaPath: String,
    objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule(),
): List<QaItem> {
    val qaFile = Path.of(qaPath)
    if (!qaFile.exists()) {
        return emptyList()
    }

    val root = runCatching { objectMapper.readTree(qaFile.toFile()) }.getOrNull() ?: return emptyList()
    val collected = mutableListOf<QaItem>()
    collectQaItems(root, collected)
    return collected
        .map { item -> item.copy(question = item.question.trim(), referenceAnswer = item.referenceAnswer?.trim()) }
        .filter { item -> item.question.isNotBlank() }
        .distinctBy { item -> item.question }
}

private fun collectQaItems(
    node: JsonNode,
    destination: MutableList<QaItem>,
) {
    when {
        node.isArray -> {
            node.forEach { element -> collectQaItems(element, destination) }
        }

        node.isObject -> {
            val question = extractText(node, QUESTION_KEYS)
            if (!question.isNullOrBlank()) {
                destination.add(
                    QaItem(
                        question = question,
                        referenceAnswer = extractText(node, ANSWER_KEYS),
                    ),
                )
            }

            COMMON_CONTAINER_KEYS.forEach { key ->
                val child = node.get(key)
                if (child != null) {
                    collectQaItems(child, destination)
                }
            }
        }
    }
}

private fun extractText(
    node: JsonNode,
    keys: Set<String>,
): String? {
    keys.forEach { key ->
        val value = node.get(key) ?: return@forEach
        when {
            value.isTextual -> {
                return value.asText()
            }

            value.isArray -> {
                val joined = value.filter { it.isTextual }.joinToString(" | ") { it.asText() }
                if (joined.isNotBlank()) {
                    return joined
                }
            }
        }
    }
    return null
}

private val QUESTION_KEYS = setOf("question", "query", "q", "question_text", "input")
private val ANSWER_KEYS = setOf("answer", "gold_answer", "reference_answer", "output")
private val COMMON_CONTAINER_KEYS = setOf("qa_pairs", "questions", "items", "data", "examples")

fun main(args: Array<String>) {
    val exitCode = CommandLine(MainCommand()).execute(*args)
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}
