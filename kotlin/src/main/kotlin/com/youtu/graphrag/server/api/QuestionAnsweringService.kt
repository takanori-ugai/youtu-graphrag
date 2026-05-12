package com.youtu.graphrag.server.api

import com.youtu.graphrag.server.api.contracts.QuestionResponse
import com.youtu.graphrag.server.api.contracts.ReasoningStep
import com.youtu.graphrag.shared.config.ConfigManager
import com.youtu.graphrag.shared.decomposer.GraphQ
import com.youtu.graphrag.shared.retriever.KTRetriever
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.system.measureTimeMillis
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GraphArtifactNotFoundException(
    message: String,
) : RuntimeException(message)

data class QaStageUpdate(
    val stage: String,
    val payload: Map<String, Any?> = emptyMap(),
)

class QuestionAnsweringService(
    private val config: ConfigManager,
    private val rootDir: Path = Path.of("."),
) {
    private val logger = KotlinLogging.logger {}

    suspend fun answerQuestion(
        datasetName: String,
        question: String,
        onQaUpdate: suspend (QaStageUpdate) -> Unit = {},
    ): QuestionResponse {
        val graphPath = resolveGraphPathWithDemoFallback(datasetName)
            ?: throw GraphArtifactNotFoundException("Graph not found. Please construct graph first.")
        val schemaPath = resolveSchemaPath(datasetName)

        val graphq = GraphQ(datasetName, config)
        val ktRetriever =
            KTRetriever(
                datasetName = datasetName,
                graphPath = graphPath.toString(),
                recallPaths = config.retrieval.recallPaths,
                schemaPath = schemaPath.toString(),
                topK = config.retrieval.topKFilter,
                mode = config.triggers.mode,
                config = config,
                rootDir = rootDir,
            )

        ktRetriever.buildIndices()

        val decomposition = graphq.decompose(question, schemaPath.toString())
        val subQuestions = parseSubQuestions(decomposition["sub_questions"])
            .ifEmpty { listOf(mapOf("sub-question" to question)) }
        val involvedTypes = parseInvolvedTypes(decomposition["involved_types"])

        onQaUpdate(
            QaStageUpdate(
                stage = "decompose",
                payload =
                    mapOf(
                        "sub_questions_count" to subQuestions.size,
                        "sub_questions" to subQuestions.map { it["sub-question"].orEmpty() }.take(5),
                    ),
            ),
        )

        val allTriples = linkedSetOf<String>()
        val chunkById = linkedMapOf<String, String>()
        val reasoningSteps = mutableListOf<ReasoningStep>()

        subQuestions.forEachIndexed { index, subQuestion ->
            val subQuestionText = subQuestion["sub-question"].orEmpty().ifBlank { question }
            val retrievalResultsHolder = mutableMapOf<String, Any>()
            val elapsedMs =
                measureTimeMillis {
                    retrievalResultsHolder.putAll(
                        ktRetriever.processRetrievalResults(
                            question = subQuestionText,
                            involvedTypes = involvedTypes,
                        ),
                    )
                }

            mergeRetrieval(
                retrievalResults = retrievalResultsHolder,
                allTriples = allTriples,
                chunkById = chunkById,
            )

            val triples = parseStringList(retrievalResultsHolder["triples"])
            val chunkIds = parseStringList(retrievalResultsHolder["chunk_ids"])
            val chunkContents = parseChunkContentMap(retrievalResultsHolder["chunk_contents"])
            val chunkPreview = chunkIds.mapNotNull { chunkContents[it] }.take(3)

            val step =
                ReasoningStep(
                    type = "sub_question",
                    question = subQuestionText,
                    triples = triples.take(10),
                    triplesCount = triples.size,
                    chunkContents = chunkPreview,
                    chunksCount = chunkIds.size,
                    processingTime = elapsedMs / 1000.0,
                )
            reasoningSteps.add(step)

            onQaUpdate(
                QaStageUpdate(
                    stage = "sub_question",
                    payload =
                        mapOf(
                            "index" to (index + 1),
                            "total" to subQuestions.size,
                            "question" to subQuestionText,
                            "triples_preview" to triples.distinct().take(5),
                            "triples_count" to step.triplesCount,
                            "chunks_count" to step.chunksCount,
                            "processing_time" to step.processingTime,
                        ),
                ),
            )
        }

        var finalAnswer = synthesizeAnswer(allTriples.toList(), chunkById.values.toList())

        if (shouldRunIrcot()) {
            onQaUpdate(
                QaStageUpdate(
                    stage = "ircot_start",
                    payload = mapOf("message" to "Starting iterative reasoning"),
                ),
            )

            val thoughts = mutableListOf<String>()
            var currentQuery = question
            val maxSteps = config.retrieval.agent.maxSteps.coerceAtLeast(1)

            for (stepIndex in 1..maxSteps) {
                var thought = ""
                val triplesSnapshotBefore = allTriples.toList()
                val chunksSnapshotBefore = chunkById.values.toList()
                val stepElapsedMs =
                    measureTimeMillis {
                        val candidateAnswer = synthesizeAnswer(triplesSnapshotBefore, chunksSnapshotBefore)
                        val hasContext = triplesSnapshotBefore.isNotEmpty() || chunksSnapshotBefore.isNotEmpty()

                        if (hasContext || stepIndex == maxSteps) {
                            thought = "So the answer is: $candidateAnswer"
                            finalAnswer = candidateAnswer
                        } else {
                            val newQuery = buildFollowUpQuery(question, currentQuery, thoughts)
                            thought = "The new query is: $newQuery"
                            currentQuery = newQuery

                            val additionalRetrieval =
                                ktRetriever.processRetrievalResults(
                                    question = newQuery,
                                    involvedTypes = involvedTypes,
                                )
                            mergeRetrieval(
                                retrievalResults = additionalRetrieval,
                                allTriples = allTriples,
                                chunkById = chunkById,
                            )
                        }
                    }

                thoughts.add(thought)

                val triplesSnapshotAfter = allTriples.toList()
                val chunksSnapshotAfter = chunkById.values.toList()

                val ircotStep =
                    ReasoningStep(
                        type = "ircot_step",
                        question = currentQuery,
                        triples = triplesSnapshotAfter.take(10),
                        triplesCount = triplesSnapshotAfter.size,
                        chunkContents = chunksSnapshotAfter.take(3),
                        chunksCount = chunksSnapshotAfter.size,
                        processingTime = stepElapsedMs / 1000.0,
                        thought = thought.take(300),
                    )
                reasoningSteps.add(ircotStep)

                onQaUpdate(
                    QaStageUpdate(
                        stage = "ircot",
                        payload =
                            mapOf(
                                "step" to stepIndex,
                                "max_steps" to maxSteps,
                                "current_query" to currentQuery,
                                "thought_preview" to thought.take(200),
                            ),
                    ),
                )

                if (thought.startsWith("So the answer is:")) {
                    break
                }
            }
        }

        val finalTriples = allTriples.take(20)
        val finalChunks = chunkById.values.take(10)

        logger.info {
            "Answered question for dataset '$datasetName' with ${finalTriples.size} triples and ${finalChunks.size} chunks"
        }

        return QuestionResponse(
            answer = finalAnswer,
            subQuestions = subQuestions,
            retrievedTriples = finalTriples,
            retrievedChunks = finalChunks,
            reasoningSteps = reasoningSteps,
            visualizationData =
                buildVisualizationData(
                    subQuestions = subQuestions,
                    reasoningSteps = reasoningSteps,
                    finalTriples = finalTriples,
                    finalChunks = finalChunks,
                ),
        )
    }

    private fun shouldRunIrcot(): Boolean {
        return config.triggers.mode == "agent" && config.retrieval.agent.enableIrcot
    }

    private fun synthesizeAnswer(
        triples: List<String>,
        chunks: List<String>,
    ): String {
        if (chunks.isNotEmpty()) {
            return chunks.first().take(400)
        }

        if (triples.isNotEmpty()) {
            return triples.first()
        }

        return "I do not have enough knowledge context to answer this question."
    }

    private fun buildFollowUpQuery(
        originalQuestion: String,
        currentQuery: String,
        thoughts: List<String>,
    ): String {
        val lastThought = thoughts.lastOrNull().orEmpty()
        val tokens = tokenize(originalQuestion).filter { token -> token !in tokenize(lastThought) }
        val refinement = tokens.take(5).joinToString(" ")

        if (refinement.isBlank()) {
            return currentQuery
        }

        return "$currentQuery $refinement".trim()
    }

    private fun tokenize(input: String): Set<String> {
        val tokenRegex = Regex("[A-Za-z0-9_]{2,}")
        return tokenRegex.findAll(input.lowercase()).map { it.value }.toSet()
    }

    private fun buildVisualizationData(
        subQuestions: List<Map<String, String>>,
        reasoningSteps: List<ReasoningStep>,
        finalTriples: List<String>,
        finalChunks: List<String>,
    ): JsonObject {
        val subqueryGraph = buildSubqueryVisualization(subQuestions)
        val retrievedGraph = buildRetrievedGraphVisualization(finalTriples)
        val reasoningFlow = buildReasoningFlowVisualization(reasoningSteps)

        val triplesBySubquery = reasoningSteps.filter { it.type == "sub_question" }.map { it.triplesCount }

        return buildJsonObject {
            put("subqueries", subqueryGraph)
            put("knowledge_graph", retrievedGraph)
            put("reasoning_flow", reasoningFlow)
            put(
                "retrieval_details",
                buildJsonObject {
                    put("total_triples", JsonPrimitive(finalTriples.size))
                    put("total_chunks", JsonPrimitive(finalChunks.size))
                    put("sub_questions_count", JsonPrimitive(subQuestions.size))
                    put(
                        "triples_by_subquery",
                        buildJsonArray {
                            triplesBySubquery.forEach { count -> add(JsonPrimitive(count)) }
                        },
                    )
                },
            )
        }
    }

    private fun buildSubqueryVisualization(subQuestions: List<Map<String, String>>): JsonObject {
        val nodes =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive("original"))
                        put("name", JsonPrimitive("Original Question"))
                        put("category", JsonPrimitive("question"))
                        put("symbolSize", JsonPrimitive(40))
                    },
                )
                subQuestions.forEachIndexed { index, subQuestion ->
                    val subText = subQuestion["sub-question"].orEmpty()
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive("sub_$index"))
                            put("name", JsonPrimitive(subText.take(20) + if (subText.length > 20) "..." else ""))
                            put("category", JsonPrimitive("sub_question"))
                            put("symbolSize", JsonPrimitive(30))
                        },
                    )
                }
            }

        val links =
            buildJsonArray {
                subQuestions.forEachIndexed { index, _ ->
                    add(
                        buildJsonObject {
                            put("source", JsonPrimitive("original"))
                            put("target", JsonPrimitive("sub_$index"))
                            put("name", JsonPrimitive("decomposed to"))
                        },
                    )
                }
            }

        return buildJsonObject {
            put("nodes", nodes)
            put("links", links)
            put(
                "categories",
                buildJsonArray {
                    add(buildJsonObject { put("name", JsonPrimitive("question")) })
                    add(buildJsonObject { put("name", JsonPrimitive("sub_question")) })
                },
            )
        }
    }

    private fun buildRetrievedGraphVisualization(triples: List<String>): JsonObject {
        val nodes = mutableListOf<JsonObject>()
        val links = mutableListOf<JsonObject>()
        val seenNodes = mutableSetOf<String>()

        triples.take(10).forEach { tripleText ->
            val parsed = parseTriple(tripleText) ?: return@forEach
            val (source, relation, target) = parsed

            if (seenNodes.add(source)) {
                nodes.add(
                    buildJsonObject {
                        put("id", JsonPrimitive(source))
                        put("name", JsonPrimitive(source.take(20)))
                        put("category", JsonPrimitive("entity"))
                        put("symbolSize", JsonPrimitive(20))
                    },
                )
            }
            if (seenNodes.add(target)) {
                nodes.add(
                    buildJsonObject {
                        put("id", JsonPrimitive(target))
                        put("name", JsonPrimitive(target.take(20)))
                        put("category", JsonPrimitive("entity"))
                        put("symbolSize", JsonPrimitive(20))
                    },
                )
            }

            links.add(
                buildJsonObject {
                    put("source", JsonPrimitive(source))
                    put("target", JsonPrimitive(target))
                    put("name", JsonPrimitive(relation))
                },
            )
        }

        return buildJsonObject {
            put("nodes", JsonArray(nodes))
            put("links", JsonArray(links))
            put(
                "categories",
                buildJsonArray {
                    add(buildJsonObject { put("name", JsonPrimitive("entity")) })
                },
            )
        }
    }

    private fun buildReasoningFlowVisualization(reasoningSteps: List<ReasoningStep>): JsonObject {
        val stepsJson =
            buildJsonArray {
                reasoningSteps.forEachIndexed { index, step ->
                    add(
                        buildJsonObject {
                            put("step", JsonPrimitive(index + 1))
                            put("type", JsonPrimitive(step.type))
                            put("question", JsonPrimitive(step.question.take(50)))
                            put("triples_count", JsonPrimitive(step.triplesCount))
                            put("chunks_count", JsonPrimitive(step.chunksCount))
                            put("processing_time", JsonPrimitive(step.processingTime))
                        },
                    )
                }
            }

        val timelineJson =
            buildJsonArray {
                reasoningSteps.forEach { step -> add(JsonPrimitive(step.processingTime)) }
            }

        return buildJsonObject {
            put("steps", stepsJson)
            put("timeline", timelineJson)
        }
    }

    private fun parseTriple(triple: String): Triple<String, String, String>? {
        val quotedParts = Regex("\"([^\"]*)\"").findAll(triple).map { it.groupValues[1] }.toList()
        if (quotedParts.size == 3) {
            return Triple(quotedParts[0], quotedParts[1], quotedParts[2])
        }

        if (!triple.startsWith("[") || !triple.endsWith("]")) {
            return null
        }

        val rawParts =
            triple
                .removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.trim().trim('"', '\'', ' ') }
                .filter { it.isNotBlank() }

        return if (rawParts.size == 3) {
            Triple(rawParts[0], rawParts[1], rawParts[2])
        } else {
            null
        }
    }

    private fun mergeRetrieval(
        retrievalResults: Map<String, Any>,
        allTriples: MutableSet<String>,
        chunkById: MutableMap<String, String>,
    ) {
        val triples = parseStringList(retrievalResults["triples"])
        val chunkIds = parseStringList(retrievalResults["chunk_ids"])
        val chunkContents = parseChunkContentMap(retrievalResults["chunk_contents"])

        triples.forEach { allTriples.add(it) }
        chunkIds.forEach { chunkId ->
            val content = chunkContents[chunkId]
            if (content != null) {
                chunkById[chunkId] = content
            }
        }
    }

    private fun resolveGraphPathWithDemoFallback(datasetName: String): Path? {
        val datasetGraph = resolvePath("${config.output.graphsDir}/${datasetName}_new.json")
        if (datasetGraph.exists()) {
            return datasetGraph
        }

        val demoGraph = resolvePath("${config.output.graphsDir}/demo_new.json")
        if (demoGraph.exists()) {
            return demoGraph
        }

        return null
    }

    private fun resolveSchemaPath(datasetName: String): Path {
        val datasetSchema = resolvePath("schemas/$datasetName.json")
        if (datasetName != "demo" && datasetSchema.exists()) {
            return datasetSchema
        }

        return resolvePath("schemas/demo.json")
    }

    private fun resolvePath(path: String): Path {
        val candidate = Path.of(path)
        return if (candidate.isAbsolute) candidate else rootDir.resolve(candidate)
    }

    private fun parseSubQuestions(raw: Any?): List<Map<String, String>> {
        if (raw !is List<*>) {
            return emptyList()
        }

        return raw.mapNotNull { item ->
            if (item !is Map<*, *>) {
                return@mapNotNull null
            }

            mapOf("sub-question" to (item["sub-question"]?.toString().orEmpty()))
        }
    }

    private fun parseInvolvedTypes(raw: Any?): Map<String, List<String>> {
        if (raw !is Map<*, *>) {
            return emptyMap()
        }

        return raw.entries.associate { (key, value) ->
            key.toString() to parseStringList(value)
        }
    }

    private fun parseStringList(raw: Any?): List<String> {
        if (raw !is List<*>) {
            return emptyList()
        }

        return raw.mapNotNull { it?.toString() }
    }

    private fun parseChunkContentMap(raw: Any?): Map<String, String> {
        if (raw !is Map<*, *>) {
            return emptyMap()
        }

        return raw.entries
            .filter { (key, value) -> key != null && value != null }
            .associate { (key, value) -> key.toString() to value.toString() }
    }
}
