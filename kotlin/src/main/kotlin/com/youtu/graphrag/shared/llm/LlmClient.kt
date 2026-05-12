package com.youtu.graphrag.shared.llm

interface LlmClient {
    fun complete(prompt: String): String
}

class NoopLlmClient : LlmClient {
    override fun complete(prompt: String): String = "LLM client scaffold: no response generated"
}
