package com.youtu.graphrag.server.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApplicationWsParityTest {
    @Test
    fun `construction start progress cadence matches python backend stages`() {
        assertEquals(
            listOf(
                2 to "Cleaning old cache files...",
                5 to "Initializing graph builder...",
                10 to "Loading configuration and corpus...",
                20 to "Starting entity-relation extraction...",
            ),
            constructionProgressStartUpdates(),
        )
    }

    @Test
    fun `reconstruction start progress cadence matches python backend stages`() {
        assertEquals(
            listOf(
                5 to "Starting reconstruction...",
                15 to "Old graph file deleted...",
                25 to "Cache files cleared...",
                35 to "Reinitializing graph builder...",
                50 to "Rebuilding knowledge graph...",
            ),
            reconstructionProgressStartUpdates(),
        )
    }

    @Test
    fun `qa stage maps to python-aligned progress updates`() {
        assertEquals(
            50 to "Decomposing question...",
            qaProgressUpdateForStage(QaStageUpdate(stage = "decompose")),
        )
        assertEquals(
            65 to "Initial retrieval...",
            qaProgressUpdateForStage(QaStageUpdate(stage = "sub_question")),
        )
        assertEquals(
            75 to "Iterative reasoning...",
            qaProgressUpdateForStage(QaStageUpdate(stage = "ircot_start")),
        )
        assertEquals(
            80 to "Iterative retrieval step 1...",
            qaProgressUpdateForStage(QaStageUpdate(stage = "ircot", payload = mapOf("step" to 1))),
        )
        assertEquals(
            90 to "Iterative retrieval step 8...",
            qaProgressUpdateForStage(QaStageUpdate(stage = "ircot", payload = mapOf("step" to 8))),
        )
        assertEquals(
            90 to "Iterative retrieval step 4...",
            qaProgressUpdateForStage(QaStageUpdate(stage = "ircot", payload = mapOf("step" to "4"))),
        )
    }

    @Test
    fun `qa stage mapping returns null when unsupported or malformed`() {
        assertNull(qaProgressUpdateForStage(QaStageUpdate(stage = "start")))
        assertNull(qaProgressUpdateForStage(QaStageUpdate(stage = "ircot")))
    }
}
