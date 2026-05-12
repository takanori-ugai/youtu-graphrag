package com.youtu.graphrag.shared.llm

import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import io.github.oshai.kotlinlogging.KotlinLogging

interface LlmClient {
    fun complete(prompt: String): String
}

class NoopLlmClient : LlmClient {
    override fun complete(prompt: String): String = ""
}

class ChatModelLlmClient(
    private val chatModel: ChatModel,
) : LlmClient {
    private val logger = KotlinLogging.logger {}

    override fun complete(prompt: String): String {
        if (prompt.isBlank()) {
            return ""
        }

        return runCatching {
            val rawText =
                chatModel
                    .chat(UserMessage.from(prompt))
                    .aiMessage()
                    .text()
                    .orEmpty()
            LlmOutputParser.cleanLlmContent(rawText)
        }.getOrElse { error ->
            logger.warn(error) { "ChatModel completion failed" }
            ""
        }
    }
}

object LlmClientFactory {
    private val logger = KotlinLogging.logger {}

    fun fromEnvironment(env: Map<String, String> = System.getenv()): LlmClient {
        val provider = env["OPENAI_PROVIDER"]?.trim()?.lowercase().orEmpty()
        return when (provider) {
            "ollama" -> {
                createOllamaClient(env)
            }

            "openai",
            "azure",
            "",
            -> {
                createOpenAiClient(env)
            }

            else -> {
                logger.warn { "Unsupported OPENAI_PROVIDER='$provider'. Falling back to OpenAI-compatible mode." }
                createOpenAiClient(env)
            }
        }
    }

    private fun createOpenAiClient(env: Map<String, String>): LlmClient {
        val apiKey =
            env["LLM_API_KEY"]?.takeIf { it.isNotBlank() }
                ?: env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() }
                ?: return NoopLlmClient()

        val modelName =
            env["LLM_MODEL"]?.takeIf { it.isNotBlank() }
                ?: env["OPENAI_MODEL"]?.takeIf { it.isNotBlank() }
                ?: "gpt-4o-mini"

        val builder =
            OpenAiChatModel
                .builder()
                .apiKey(apiKey)
                .modelName(modelName)

        env["LLM_BASE_URL"]?.takeIf { it.isNotBlank() }?.let(builder::baseUrl)
            ?: env["OPENAI_BASE_URL"]?.takeIf { it.isNotBlank() }?.let(builder::baseUrl)
            ?: env["AZURE_OPENAI_ENDPOINT"]?.takeIf { it.isNotBlank() }?.let(builder::baseUrl)

        env["LLM_TEMPERATURE"]?.toDoubleOrNull()?.let(builder::temperature)
            ?: builder.temperature(0.3)

        return runCatching {
            ChatModelLlmClient(builder.build())
        }.getOrElse { error ->
            logger.warn(error) { "Failed to initialize OpenAI ChatModel. Falling back to NoopLlmClient." }
            NoopLlmClient()
        }
    }

    private fun createOllamaClient(env: Map<String, String>): LlmClient {
        val modelName =
            env["LLM_MODEL"]?.takeIf { it.isNotBlank() }
                ?: env["OLLAMA_MODEL"]?.takeIf { it.isNotBlank() }
                ?: "llama3"

        val baseUrl =
            env["LLM_BASE_URL"]?.takeIf { it.isNotBlank() }
                ?: env["OLLAMA_BASE_URL"]?.takeIf { it.isNotBlank() }
                ?: "http://localhost:11434"

        val builder =
            OllamaChatModel
                .builder()
                .baseUrl(baseUrl)
                .modelName(modelName)

        env["LLM_TEMPERATURE"]?.toDoubleOrNull()?.let(builder::temperature)
            ?: builder.temperature(0.3)

        return runCatching {
            ChatModelLlmClient(builder.build())
        }.getOrElse { error ->
            logger.warn(error) { "Failed to initialize Ollama ChatModel. Falling back to NoopLlmClient." }
            NoopLlmClient()
        }
    }
}
