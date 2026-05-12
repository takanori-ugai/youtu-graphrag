package com.youtu.graphrag.server.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.youtu.graphrag.server.api.contracts.BaseOperationResponse
import com.youtu.graphrag.server.api.contracts.FileUploadResponse
import com.youtu.graphrag.server.api.contracts.GraphConstructionRequest
import com.youtu.graphrag.server.api.contracts.GraphConstructionResponse
import com.youtu.graphrag.server.api.contracts.QuestionRequest
import com.youtu.graphrag.shared.config.ConfigManager
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val logger = KotlinLogging.logger {}
private val objectMapper = jacksonObjectMapper()

fun main() {
    embeddedServer(
        Netty,
        host = "0.0.0.0",
        port = 8000,
        module = Application::youtuGraphRagModule,
    ).start(wait = true)
}

fun Application.youtuGraphRagModule() {
    val wsManager = WebSocketConnectionManager()

    val runtimeConfig = ConfigManager("config/base_config.yaml")
    val graphConstructionService = GraphConstructionService(config = runtimeConfig)
    val questionAnsweringService = QuestionAnsweringService(config = runtimeConfig)

    val datasetFileService = DatasetFileService()
    datasetFileService.ensureStartupDirectories()

    val frontendDir = resolveStaticDir("frontend")
    val assetsDir = resolveStaticDir("assets")
    val frontendIndex = frontendDir?.resolve("index.html")?.takeIf { it.exists() && it.isRegularFile() }

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            },
        )
    }

    install(CORS) {
        anyHost()
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Delete)
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
    }

    install(WebSockets)

    routing {
        if (assetsDir != null) {
            staticFiles("/assets", assetsDir.toFile())
        } else {
            logger.warn { "Static assets directory not found at ./assets or ../assets" }
        }

        if (frontendDir != null) {
            staticFiles("/frontend", frontendDir.toFile())
        } else {
            logger.warn { "Frontend directory not found at ./frontend or ../frontend" }
        }

        get("/") {
            if (frontendIndex != null) {
                call.respondFile(frontendIndex.toFile())
            } else {
                call.respond(
                    mapOf(
                        "message" to "Youtu-GraphRAG Unified Interface is running!",
                        "status" to "ok",
                    ),
                )
            }
        }

        get("/api/status") {
            call.respond(
                mapOf(
                    "message" to "Youtu-GraphRAG Unified Interface is running!",
                    "status" to "ok",
                    "graphrag_available" to true,
                ),
            )
        }

        webSocket("/ws/{client_id}") {
            val clientId = call.parameters["client_id"]
            if (clientId.isNullOrBlank() || clientId == "default") {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing or invalid client_id"))
                return@webSocket
            }
            wsManager.connect(clientId, this)

            try {
                for (frame in incoming) {
                    if (frame is Frame.Close) {
                        break
                    }
                }
            } finally {
                wsManager.disconnect(clientId)
                close(CloseReason(CloseReason.Codes.NORMAL, "Connection closed"))
            }
        }

        post("/api/upload") {
            val multipart = call.receiveMultipart()
            val files = mutableListOf<IncomingFile>()
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part is PartData.FileItem) {
                        val fileName = part.originalFileName ?: "uploaded_${files.size}"
                        val bytes = part.provider().toInputStream().readBytes()
                        files.add(IncomingFile(fileName = fileName, bytes = bytes))
                    }
                } finally {
                    part.dispose.invoke()
                }
            }

            if (files.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "No files were provided"))
                return@post
            }

            try {
                val result = datasetFileService.uploadFiles(files)
                call.respond(
                    FileUploadResponse(
                        success = result.success,
                        message = result.message,
                        datasetName = result.datasetName,
                        filesCount = result.filesCount,
                    ),
                )
            } catch (error: IllegalArgumentException) {
                logger.warn(error) { "Invalid upload request" }
                call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Invalid upload request"))
            } catch (error: Exception) {
                logger.error(error) { "Upload failed" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to "Upload failed"))
            }
        }

        post("/api/construct-graph") {
            val request = call.receive<GraphConstructionRequest>()
            val clientId = call.request.queryParameters["client_id"]
            if (clientId.isNullOrBlank() || clientId == "default") {
                call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Missing or invalid client_id query parameter"))
                return@post
            }

            sendProgressUpdate(
                wsManager = wsManager,
                clientId = clientId,
                stage = "construction",
                progress = 5,
                message = "Initializing graph builder...",
            )

            try {
                val result = graphConstructionService.constructGraph(request.datasetName)
                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "construction",
                    progress = 100,
                    message = "Graph construction completed!",
                )
                sendStageEvent(
                    wsManager = wsManager,
                    clientId = clientId,
                    type = "complete",
                    stage = "construction",
                    message = "Graph construction completed!",
                )
                call.respond(
                    GraphConstructionResponse(
                        success = result.success,
                        message = result.message,
                        graphData = result.graphData,
                    ),
                )
            } catch (error: DatasetNotFoundException) {
                logger.warn(error) { "Construction failed: Dataset not found" }
                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "construction",
                    progress = 0,
                    message = "Construction failed: Dataset not found",
                )
                sendStageEvent(
                    wsManager = wsManager,
                    clientId = clientId,
                    type = "error",
                    stage = "construction",
                    message = "Construction failed: Dataset not found",
                )
                call.respond(HttpStatusCode.NotFound, mapOf("detail" to "Dataset not found"))
            } catch (error: IllegalArgumentException) {
                logger.warn(error) { "Invalid construction request for '${request.datasetName}'" }
                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "construction",
                    progress = 0,
                    message = "Construction failed: Invalid request",
                )
                sendStageEvent(
                    wsManager = wsManager,
                    clientId = clientId,
                    type = "error",
                    stage = "construction",
                    message = "Construction failed: Invalid request",
                )
                call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Invalid request"))
            } catch (error: Exception) {
                logger.error(error) { "Graph construction failed for dataset '${request.datasetName}'" }
                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "construction",
                    progress = 0,
                    message = "Construction failed: Internal error",
                )
                sendStageEvent(
                    wsManager = wsManager,
                    clientId = clientId,
                    type = "error",
                    stage = "construction",
                    message = "Construction failed: Internal error",
                )
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("detail" to "Graph construction failed"),
                )
            }
        }

        post("/api/ask-question") {
            val request = call.receive<QuestionRequest>()
            val clientId = call.request.queryParameters["client_id"]
            if (clientId.isNullOrBlank() || clientId == "default") {
                call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Missing or invalid client_id query parameter"))
                return@post
            }

            sendProgressUpdate(
                wsManager = wsManager,
                clientId = clientId,
                stage = "retrieval",
                progress = 10,
                message = "Initializing retrieval system (agent mode)...",
            )
            sendQaUpdate(
                wsManager = wsManager,
                clientId = clientId,
                stage = "start",
                extra =
                    mapOf(
                        "dataset" to request.datasetName,
                        "question" to request.question,
                        "message" to "Question processing started",
                    ),
            )

            try {
                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "retrieval",
                    progress = 40,
                    message = "Building indices...",
                )
                var initialRetrievalProgressSent = false

                val response =
                    questionAnsweringService.answerQuestion(
                        datasetName = request.datasetName,
                        question = request.question,
                        onQaUpdate = { update ->
                            qaProgressUpdateForStage(update)?.let { (progress, message) ->
                                if (update.stage == "sub_question") {
                                    if (!initialRetrievalProgressSent) {
                                        sendProgressUpdate(
                                            wsManager = wsManager,
                                            clientId = clientId,
                                            stage = "retrieval",
                                            progress = progress,
                                            message = message,
                                        )
                                        initialRetrievalProgressSent = true
                                    }
                                } else {
                                    sendProgressUpdate(
                                        wsManager = wsManager,
                                        clientId = clientId,
                                        stage = "retrieval",
                                        progress = progress,
                                        message = message,
                                    )
                                }
                            }
                            sendQaUpdate(
                                wsManager = wsManager,
                                clientId = clientId,
                                stage = update.stage,
                                extra = update.payload,
                            )
                        },
                    )

                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "retrieval",
                    progress = 100,
                    message = "Answer generation completed!",
                )
                wsManager.sendMessage(
                    clientId = clientId,
                    message =
                        mapOf(
                            "type" to "qa_complete",
                            "answer_preview" to response.answer.take(300),
                            "sub_questions_count" to response.subQuestions.size,
                            "triples_final_count" to response.retrievedTriples.size,
                            "chunks_final_count" to response.retrievedChunks.size,
                            "timestamp" to websocketTimestamp(),
                        ),
                )

                call.respond(response)
            } catch (error: GraphArtifactNotFoundException) {
                logger.warn(error) { "Question answering failed: Graph artifacts not found" }
                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "retrieval",
                    progress = 0,
                    message = "Question answering failed: Graph not found",
                )
                sendStageEvent(
                    wsManager = wsManager,
                    clientId = clientId,
                    type = "error",
                    stage = "retrieval",
                    message = "Question answering failed: Graph not found",
                )
                call.respond(HttpStatusCode.NotFound, mapOf("detail" to "Graph not found"))
            } catch (error: IllegalArgumentException) {
                logger.warn(error) { "Invalid question answering request for '${request.datasetName}'" }
                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "retrieval",
                    progress = 0,
                    message = "Question answering failed: Invalid request",
                )
                sendStageEvent(
                    wsManager = wsManager,
                    clientId = clientId,
                    type = "error",
                    stage = "retrieval",
                    message = "Question answering failed: Invalid request",
                )
                call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Invalid request"))
            } catch (error: Exception) {
                logger.error(error) { "Question answering failed for dataset '${request.datasetName}'" }
                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "retrieval",
                    progress = 0,
                    message = "Question answering failed: Internal error",
                )
                sendStageEvent(
                    wsManager = wsManager,
                    clientId = clientId,
                    type = "error",
                    stage = "retrieval",
                    message = "Question answering failed: Internal error",
                )
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("detail" to "Question answering failed"),
                )
            }
        }

        get("/api/datasets") {
            call.respond(datasetFileService.getDatasets())
        }

        post("/api/datasets/{dataset_name}/schema") {
            val datasetName =
                call.parameters["dataset_name"]
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        BaseOperationResponse(success = false, message = "Missing dataset_name path parameter"),
                    )

            val multipart = call.receiveMultipart()
            var schemaFileName: String? = null
            var schemaBytes: ByteArray? = null

            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part is PartData.FileItem && schemaBytes == null) {
                        schemaFileName = part.originalFileName ?: "schema.json"
                        schemaBytes = part.provider().toInputStream().readBytes()
                    }
                } finally {
                    part.dispose.invoke()
                }
            }

            if (schemaBytes == null || schemaFileName == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "No schema file was provided"))
                return@post
            }
            val safeSchemaBytes = requireNotNull(schemaBytes)
            val safeSchemaFileName = requireNotNull(schemaFileName)

            try {
                call.respond(datasetFileService.saveSchema(datasetName, safeSchemaFileName, safeSchemaBytes))
            } catch (error: IllegalArgumentException) {
                logger.warn(error) { "Invalid schema for '$datasetName'" }
                call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Invalid schema"))
            } catch (error: Exception) {
                logger.error(error) { "Schema upload failed for '$datasetName'" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("detail" to "Failed to upload schema"),
                )
            }
        }

        delete("/api/datasets/{dataset_name}") {
            val datasetName =
                call.parameters["dataset_name"]
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        BaseOperationResponse(success = false, message = "Missing dataset_name path parameter"),
                    )

            try {
                call.respond(datasetFileService.deleteDataset(datasetName))
            } catch (error: IllegalArgumentException) {
                logger.warn(error) { "Invalid dataset deletion request for '$datasetName'" }
                call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Invalid dataset"))
            } catch (error: Exception) {
                logger.error(error) { "Delete dataset failed for '$datasetName'" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("detail" to "Failed to delete dataset"),
                )
            }
        }

        post("/api/datasets/{dataset_name}/reconstruct") {
            val datasetName =
                call.parameters["dataset_name"]
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        BaseOperationResponse(success = false, message = "Missing dataset_name path parameter"),
                    )
            val clientId = call.request.queryParameters["client_id"]
            if (clientId.isNullOrBlank() || clientId == "default") {
                call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Missing or invalid client_id query parameter"))
                return@post
            }

            sendProgressUpdate(
                wsManager = wsManager,
                clientId = clientId,
                stage = "reconstruction",
                progress = 5,
                message = "Starting reconstruction...",
            )

            try {
                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "reconstruction",
                    progress = 50,
                    message = "Rebuilding knowledge graph...",
                )

                graphConstructionService.reconstructGraph(datasetName)

                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "reconstruction",
                    progress = 100,
                    message = "Graph reconstruction completed!",
                )
                sendStageEvent(
                    wsManager = wsManager,
                    clientId = clientId,
                    type = "complete",
                    stage = "reconstruction",
                    message = "Graph reconstruction completed!",
                )

                call.respond(
                    mapOf(
                        "success" to true,
                        "message" to "Dataset reconstructed successfully",
                        "dataset_name" to datasetName,
                    ),
                )
            } catch (error: DatasetNotFoundException) {
                logger.warn(error) { "Reconstruction failed: Dataset '$datasetName' not found" }
                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "reconstruction",
                    progress = 0,
                    message = "Reconstruction failed: Dataset not found",
                )
                sendStageEvent(
                    wsManager = wsManager,
                    clientId = clientId,
                    type = "error",
                    stage = "reconstruction",
                    message = "Reconstruction failed: Dataset not found",
                )
                call.respond(HttpStatusCode.NotFound, mapOf("detail" to "Dataset not found"))
            } catch (error: IllegalArgumentException) {
                logger.warn(error) { "Invalid reconstruction request for '$datasetName'" }
                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "reconstruction",
                    progress = 0,
                    message = "Reconstruction failed: Invalid request",
                )
                sendStageEvent(
                    wsManager = wsManager,
                    clientId = clientId,
                    type = "error",
                    stage = "reconstruction",
                    message = "Reconstruction failed: Invalid request",
                )
                call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Invalid request"))
            } catch (error: Exception) {
                logger.error(error) { "Reconstruction failed for '$datasetName'" }
                sendProgressUpdate(
                    wsManager = wsManager,
                    clientId = clientId,
                    stage = "reconstruction",
                    progress = 0,
                    message = "Reconstruction failed: Internal error",
                )
                sendStageEvent(
                    wsManager = wsManager,
                    clientId = clientId,
                    type = "error",
                    stage = "reconstruction",
                    message = "Reconstruction failed: Internal error",
                )
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("detail" to "Reconstruction failed"),
                )
            }
        }

        get("/api/graph/{dataset_name}") {
            val datasetName = call.parameters["dataset_name"] ?: "demo"
            try {
                val graphData =
                    graphConstructionService.loadGraphVisualization(datasetName) ?: run {
                        val payload = objectMapper.writeValueAsString(defaultGraphVisualization())
                        call.respondText(payload, ContentType.Application.Json)
                        return@get
                    }
                val payload = graphData.toString()
                call.respondText(payload, ContentType.Application.Json)
            } catch (error: IllegalArgumentException) {
                logger.warn(error) { "Invalid graph request for '$datasetName'" }
                call.respond(HttpStatusCode.BadRequest, mapOf("detail" to "Invalid dataset name"))
            } catch (error: Exception) {
                logger.error(error) { "Failed to load graph for '$datasetName'" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("detail" to "Failed to load graph"))
            }
        }
    }

    logger.info { "Kotlin API initialized at ${Instant.now()}" }
}

private fun resolveStaticDir(dirName: String): Path? {
    val candidates =
        listOf(
            Path.of(dirName),
            Path.of("..", dirName),
        )

    return candidates.firstOrNull { candidate -> candidate.exists() && candidate.isDirectory() }
}

suspend fun sendProgressUpdate(
    wsManager: WebSocketConnectionManager,
    clientId: String,
    stage: String,
    progress: Int,
    message: String,
) {
    wsManager.sendMessage(
        clientId = clientId,
        message =
            mapOf(
                "type" to "progress",
                "stage" to stage,
                "progress" to progress,
                "message" to message,
                "timestamp" to websocketTimestamp(),
            ),
    )
}

suspend fun sendStageEvent(
    wsManager: WebSocketConnectionManager,
    clientId: String,
    type: String,
    stage: String,
    message: String,
) {
    wsManager.sendMessage(
        clientId = clientId,
        message =
            mapOf(
                "type" to type,
                "stage" to stage,
                "message" to message,
                "timestamp" to websocketTimestamp(),
            ),
    )
}

suspend fun sendQaUpdate(
    wsManager: WebSocketConnectionManager,
    clientId: String,
    stage: String,
    extra: Map<String, Any?> = emptyMap(),
) {
    val payload =
        mutableMapOf<String, Any?>(
            "type" to "qa_update",
            "stage" to stage,
            "timestamp" to websocketTimestamp(),
        )
    payload.putAll(extra)

    wsManager.sendMessage(
        clientId = clientId,
        message = payload,
    )
}

private fun websocketTimestamp(): String = LocalDateTime.now().toString()

internal fun qaProgressUpdateForStage(update: QaStageUpdate): Pair<Int, String>? =
    when (update.stage) {
        "decompose" -> {
            50 to "Decomposing question..."
        }

        "sub_question" -> {
            65 to "Initial retrieval..."
        }

        "ircot_start" -> {
            75 to "Iterative reasoning..."
        }

        "ircot" -> {
            val step = update.payload["step"].coerceIntOrNull() ?: return null
            minOf(90, 75 + step * 5) to "Iterative retrieval step $step..."
        }

        else -> {
            null
        }
    }

private fun Any?.coerceIntOrNull(): Int? =
    when (this) {
        is Int -> this
        is Long -> this.toInt()
        is Number -> this.toInt()
        is String -> this.toIntOrNull()
        else -> null
    }

private fun defaultGraphVisualization(): Map<String, Any> =
    mapOf(
        "nodes" to
            listOf(
                mapOf(
                    "id" to "node1",
                    "name" to "Example Entity 1",
                    "category" to "person",
                    "value" to 5,
                    "symbolSize" to 25,
                ),
                mapOf(
                    "id" to "node2",
                    "name" to "Example Entity 2",
                    "category" to "location",
                    "value" to 3,
                    "symbolSize" to 20,
                ),
            ),
        "links" to
            listOf(
                mapOf(
                    "source" to "node1",
                    "target" to "node2",
                    "name" to "located_in",
                    "value" to 1,
                ),
            ),
        "categories" to
            listOf(
                mapOf(
                    "name" to "person",
                    "itemStyle" to mapOf("color" to "#ff6b6b"),
                ),
                mapOf(
                    "name" to "location",
                    "itemStyle" to mapOf("color" to "#4ecdc4"),
                ),
            ),
        "stats" to
            mapOf(
                "total_nodes" to 2,
                "total_edges" to 1,
                "displayed_nodes" to 2,
                "displayed_edges" to 1,
            ),
    )
