package com.youtu.graphrag.shared.llm

import com.sun.net.httpserver.HttpServer
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmClientTest {
    @Test
    fun `chat model client delegates to ChatModel and returns ai text`() {
        val model = RecordingChatModel(response = "model response")
        val client = ChatModelLlmClient(model)

        val output = client.complete("test prompt")

        assertEquals("model response", output)
        assertEquals("test prompt", model.lastPrompt)
    }

    @Test
    fun `chat model client strips markdown fence and json prefix from content`() {
        val model = RecordingChatModel(response = "```json\n{\"answer\":\"ok\"}\n```")
        val client = ChatModelLlmClient(model)

        val output = client.complete("test prompt")

        assertEquals("{\"answer\":\"ok\"}", output)
    }

    @Test
    fun `factory falls back to noop when no api credentials exist`() {
        val client = LlmClientFactory.fromEnvironment(env = emptyMap())
        assertTrue(client is NoopLlmClient)
        assertEquals("", client.complete("any prompt"))
    }

    @Test
    fun `factory openai client works against mock chat completions server`() {
        var requestBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { exchange ->
            requestBody = exchange.requestBody.bufferedReader().readText()
            val response =
                """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1,
                  "model": "test-model",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "mocked completion"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 1,
                    "completion_tokens": 1,
                    "total_tokens": 2
                  }
                }
                """.trimIndent()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { body ->
                body.write(response.toByteArray())
            }
        }
        server.start()

        try {
            val port = server.address.port
            val client =
                LlmClientFactory.fromEnvironment(
                    env =
                        mapOf(
                            "OPENAI_PROVIDER" to "openai",
                            "LLM_API_KEY" to "test-key",
                            "LLM_MODEL" to "test-model",
                            "LLM_BASE_URL" to "http://127.0.0.1:$port/v1",
                        ),
                )

            val output = client.complete("who is the leader?")

            assertEquals("mocked completion", output)
            assertTrue(requestBody.contains("test-model"))
            assertTrue(requestBody.contains("who is the leader?"))
        } finally {
            server.stop(0)
        }
    }
}

private class RecordingChatModel(
    private val response: String,
) : ChatModel {
    var lastPrompt: String? = null

    override fun doChat(request: ChatRequest): ChatResponse {
        val lastUserMessage = UserMessage.findLast(request.messages()).orElse(null)
        lastPrompt = lastUserMessage?.singleText()
        return ChatResponse.builder().aiMessage(AiMessage.from(response)).build()
    }
}
