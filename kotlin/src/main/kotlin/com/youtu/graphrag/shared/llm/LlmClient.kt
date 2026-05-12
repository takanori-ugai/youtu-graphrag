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
        val provider = env.firstNonBlank("OPENAI_PROVIDER")?.lowercase() ?: "openai"
        return when (provider) {
            "ollama" -> {
                createOllamaClient(env)
            }

            "azure",
            "azure_openai",
            -> {
                createAzureOpenAiCompatibleClient(env)
            }

            "openai" -> {
                createOpenAiClient(env)
            }

            else -> {
                logger.warn { "Unsupported OPENAI_PROVIDER='$provider'. Falling back to OpenAI-compatible mode." }
                createOpenAiClient(env)
            }
        }
    }

    private fun createOpenAiClient(env: Map<String, String>): LlmClient {
        val apiKey = env.firstNonBlank("LLM_API_KEY", "OPENAI_API_KEY") ?: return NoopLlmClient()
        val modelName = env.firstNonBlank("LLM_MODEL", "OPENAI_MODEL") ?: "deepseek-chat"
        val baseUrl = env.firstNonBlank("LLM_BASE_URL", "OPENAI_BASE_URL") ?: "https://api.deepseek.com"

        val builder =
            OpenAiChatModel
                .builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)

        env["LLM_TEMPERATURE"]?.toDoubleOrNull()?.let(builder::temperature)
            ?: builder.temperature(0.3)

        return runCatching {
            ChatModelLlmClient(builder.build())
        }.getOrElse { error ->
            logger.warn(error) { "Failed to initialize OpenAI ChatModel. Falling back to NoopLlmClient." }
            NoopLlmClient()
        }
    }

    private fun createAzureOpenAiCompatibleClient(env: Map<String, String>): LlmClient {
        val apiKey =
            env.firstNonBlank(
                "LLM_API_KEY",
                "AZURE_OPENAI_API_KEY",
                "OPENAI_API_KEY",
            ) ?: return NoopLlmClient()
        val deploymentName = env.firstNonBlank("LLM_MODEL", "OPENAI_MODEL", "AZURE_OPENAI_DEPLOYMENT") ?: return NoopLlmClient()
        val endpoint = env.firstNonBlank("LLM_BASE_URL", "AZURE_OPENAI_ENDPOINT", "OPENAI_BASE_URL") ?: return NoopLlmClient()
        val apiVersion = env.firstNonBlank("API_VERSION", "AZURE_OPENAI_API_VERSION") ?: "2025-01-01-preview"

        val baseUrl = "${endpoint.trimEnd('/')}/openai/deployments/$deploymentName"
        val builder =
            OpenAiChatModel
                .builder()
                .apiKey(apiKey)
                .modelName(deploymentName)
                .baseUrl(baseUrl)
                .customHeaders(mapOf("api-key" to apiKey))
                .customQueryParams(mapOf("api-version" to apiVersion))

        env["LLM_TEMPERATURE"]?.toDoubleOrNull()?.let(builder::temperature)
            ?: builder.temperature(0.3)

        return runCatching {
            ChatModelLlmClient(builder.build())
        }.getOrElse { error ->
            logger.warn(error) { "Failed to initialize Azure OpenAI-compatible ChatModel. Falling back to NoopLlmClient." }
            NoopLlmClient()
        }
    }

    private fun createOllamaClient(env: Map<String, String>): LlmClient {
        val modelName = env.firstNonBlank("LLM_MODEL", "OLLAMA_MODEL") ?: "llama3"
        val baseUrl = env.firstNonBlank("LLM_BASE_URL", "OLLAMA_BASE_URL") ?: "http://localhost:11434"

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

    private fun Map<String, String>.firstNonBlank(vararg keys: String): String? =
        keys
            .asSequence()
            .mapNotNull { key -> this[key]?.trim() }
            .firstOrNull { value -> value.isNotBlank() }
}
