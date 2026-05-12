package com.youtu.graphrag.server.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.youtu.graphrag.server.api.contracts.BaseOperationResponse
import com.youtu.graphrag.server.api.contracts.DatasetsResponse
import com.youtu.graphrag.server.api.contracts.FileUploadResponse
import com.youtu.graphrag.server.api.contracts.GraphConstructionRequest
import com.youtu.graphrag.server.api.contracts.GraphConstructionResponse
import com.youtu.graphrag.server.api.contracts.QuestionRequest
import com.youtu.graphrag.server.api.contracts.QuestionResponse
import com.youtu.graphrag.server.api.contracts.ReasoningStep
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.serialization.json.Json
import java.time.Instant

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
        get("/") {
            call.respond(
                mapOf(
                    "message" to "Youtu-GraphRAG Unified Interface is running!",
                    "status" to "ok",
                ),
            )
        }

        get("/api/status") {
            call.respond(
                mapOf(
                    "message" to "Youtu-GraphRAG Unified Interface is running!",
                    "status" to "ok",
                    "graphrag_available" to false,
                ),
            )
        }

        webSocket("/ws/{client_id}") {
            try {
                for (frame in incoming) {
                    if (frame is Frame.Close) {
                        break
                    }
                }
            } finally {
                // intentionally no-op: Kotlin backend conversion scaffold does not keep WS state yet
            }
        }

        post("/api/upload") {
            call.respond(
                HttpStatusCode.NotImplemented,
                FileUploadResponse(
                    success = false,
                    message = "Upload pipeline is not implemented in Kotlin yet",
                ),
            )
        }

        post("/api/construct-graph") {
            val request = call.receive<GraphConstructionRequest>()
            call.respond(
                HttpStatusCode.NotImplemented,
                GraphConstructionResponse(
                    success = false,
                    message = "Graph construction is not implemented for dataset '${request.datasetName}'",
                ),
            )
        }

        post("/api/ask-question") {
            val request = call.receive<QuestionRequest>()
            call.respond(
                HttpStatusCode.NotImplemented,
                QuestionResponse(
                    answer = "Kotlin retrieval pipeline is not implemented yet for dataset '${request.datasetName}'",
                    subQuestions = emptyList(),
                    retrievedTriples = emptyList(),
                    retrievedChunks = emptyList(),
                    reasoningSteps =
                        listOf(
                            ReasoningStep(
                                type = "info",
                                question = request.question,
                                triplesCount = 0,
                                chunksCount = 0,
                                processingTime = 0.0,
                            ),
                        ),
                    visualizationData = mapOf("status" to "not_implemented"),
                ),
            )
        }

        get("/api/datasets") {
            call.respond(DatasetsResponse(datasets = emptyList()))
        }

        post("/api/datasets/{dataset_name}/schema") {
            val datasetName =
                call.parameters["dataset_name"]
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        BaseOperationResponse(success = false, message = "Missing dataset_name path parameter"),
                    )

            call.respond(
                HttpStatusCode.NotImplemented,
                BaseOperationResponse(
                    success = false,
                    message = "Custom schema upload is not implemented for dataset '$datasetName'",
                    datasetName = datasetName,
                ),
            )
        }

        delete("/api/datasets/{dataset_name}") {
            val datasetName =
                call.parameters["dataset_name"]
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        BaseOperationResponse(success = false, message = "Missing dataset_name path parameter"),
                    )

            call.respond(
                HttpStatusCode.NotImplemented,
                BaseOperationResponse(
                    success = false,
                    message = "Dataset deletion is not implemented for '$datasetName'",
                    datasetName = datasetName,
                ),
            )
        }

        post("/api/datasets/{dataset_name}/reconstruct") {
            val datasetName =
                call.parameters["dataset_name"]
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        BaseOperationResponse(success = false, message = "Missing dataset_name path parameter"),
                    )

            call.respond(
                HttpStatusCode.NotImplemented,
                BaseOperationResponse(
                    success = false,
                    message = "Dataset reconstruction is not implemented for '$datasetName'",
                    datasetName = datasetName,
                ),
            )
        }

        get("/api/graph/{dataset_name}") {
            val payload = objectMapper.writeValueAsString(defaultGraphVisualization())
            call.respondText(payload, ContentType.Application.Json)
        }
    }

    logger.info { "Kotlin API scaffold initialized at ${Instant.now()}" }
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
