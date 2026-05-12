package com.youtu.graphrag.shared.decomposer

import com.youtu.graphrag.shared.config.ConfigManager
import com.youtu.graphrag.shared.llm.LlmClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphQTest {
    @Test
    fun `decompose keeps single question for simple input`() {
        val config = ConfigManager("config/base_config.yaml")
        val graphQ = GraphQ(datasetName = "demo", config = config, llmClient = emptyLlmClient())

        val result = graphQ.decompose("Who leads Project Alpha?", "schemas/demo.json")
        val subQuestions = result["sub_questions"] as List<*>

        assertEquals(1, subQuestions.size)
        val first = subQuestions.first() as Map<*, *>
        assertEquals("Who leads Project Alpha?", first["sub-question"])
    }

    @Test
    fun `decompose splits conjunction question into multiple subquestions`() {
        val config = ConfigManager("config/base_config.yaml")
        val graphQ = GraphQ(datasetName = "demo", config = config, llmClient = emptyLlmClient())

        val result =
            graphQ.decompose(
                "Who leads Project Alpha and where is Project Alpha based?",
                "schemas/demo.json",
            )
        val subQuestions = result["sub_questions"] as List<*>

        assertEquals(2, subQuestions.size)
        val first = subQuestions[0] as Map<*, *>
        val second = subQuestions[1] as Map<*, *>
        assertTrue((first["sub-question"] as String).endsWith("?"))
        assertTrue((second["sub-question"] as String).endsWith("?"))
    }

    @Test
    fun `decompose uses llm json output when available`() {
        val config = ConfigManager("config/base_config.yaml")
        val graphQ =
            GraphQ(
                datasetName = "demo",
                config = config,
                llmClient =
                    object : LlmClient {
                        override fun complete(prompt: String): String =
                            """
                            {
                              "sub_questions": [
                                {"sub-question": "Who is the director of Ethnic Notions?"},
                                {"sub-question": "When did the director die?"}
                              ],
                              "involved_types": {
                                "nodes": ["creative_work", "person"],
                                "relations": ["directed_by"],
                                "attributes": ["date"]
                              }
                            }
                            """.trimIndent()
                    },
            )

        val result = graphQ.decompose("Complex question?", "schemas/demo.json")
        val subQuestions = result["sub_questions"] as List<*>
        val involvedTypes = result["involved_types"] as Map<*, *>

        assertEquals(2, subQuestions.size)
        assertEquals(2, (involvedTypes["nodes"] as List<*>).size)
    }

    @Test
    fun `decompose supports legacy llm list format`() {
        val config = ConfigManager("config/base_config.yaml")
        val graphQ =
            GraphQ(
                datasetName = "demo",
                config = config,
                llmClient =
                    object : LlmClient {
                        override fun complete(prompt: String): String =
                            """
                            [
                              {"sub-question": "Subquestion A?"},
                              {"sub-question": "Subquestion B?"}
                            ]
                            """.trimIndent()
                    },
            )

        val result = graphQ.decompose("Question", "schemas/demo.json")
        val subQuestions = result["sub_questions"] as List<*>
        val involvedTypes = result["involved_types"] as Map<*, *>

        assertEquals(2, subQuestions.size)
        assertTrue((involvedTypes["nodes"] as List<*>).isEmpty())
        assertTrue((involvedTypes["relations"] as List<*>).isEmpty())
        assertTrue((involvedTypes["attributes"] as List<*>).isEmpty())
    }

    private fun emptyLlmClient(): LlmClient =
        object : LlmClient {
            override fun complete(prompt: String): String = ""
        }
}
