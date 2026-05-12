package com.youtu.graphrag.shared.retriever

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorchCacheInteropTest {
    @Test
    fun `pt to npz conversion returns false when pt cache is missing`() {
        val root = Files.createTempDirectory("youtu-graphrag-pt-missing-test")
        val converted =
            TorchCacheInterop.tryConvertPtToNpz(
                ptPath = root.resolve("missing.pt"),
                npzPath = root.resolve("out.npz"),
                runner = { _ -> 0 },
            )

        assertFalse(converted)
    }

    @Test
    fun `pt to npz conversion uses runner and succeeds when output is created`() {
        val root = Files.createTempDirectory("youtu-graphrag-pt-convert-test")
        val ptPath = root.resolve("chunk_embedding_cache.pt")
        val npzPath = root.resolve("chunk_embedding_cache.npz")
        Files.write(ptPath, byteArrayOf(1, 2, 3))

        var observedCommand: List<String> = emptyList()
        val converted =
            TorchCacheInterop.tryConvertPtToNpz(
                ptPath = ptPath,
                npzPath = npzPath,
                runner = { command ->
                    observedCommand = command
                    NpzEmbeddingCache.write(
                        path = npzPath,
                        vectorsByKey = mapOf("c1" to floatArrayOf(0.1f, 0.2f, 0.3f)),
                        expectedDimensions = 3,
                    )
                    0
                },
            )

        assertTrue(converted)
        assertTrue(npzPath.exists())
        assertEquals("python3", observedCommand.first())
        assertTrue(observedCommand.contains("-c"))
    }

    @Test
    fun `npz to pt conversion uses runner and succeeds when output is created`() {
        val root = Files.createTempDirectory("youtu-graphrag-npz-convert-test")
        val npzPath = root.resolve("chunk_embedding_cache.npz")
        val ptPath = root.resolve("chunk_embedding_cache.pt")
        NpzEmbeddingCache.write(
            path = npzPath,
            vectorsByKey = mapOf("c1" to floatArrayOf(0.1f, 0.2f, 0.3f)),
            expectedDimensions = 3,
        )

        var observedCommand: List<String> = emptyList()
        val converted =
            TorchCacheInterop.tryConvertNpzToPt(
                npzPath = npzPath,
                ptPath = ptPath,
                runner = { command ->
                    observedCommand = command
                    Files.write(ptPath, byteArrayOf(9, 8, 7))
                    0
                },
            )

        assertTrue(converted)
        assertTrue(ptPath.exists())
        assertEquals("python3", observedCommand.first())
        assertTrue(observedCommand.contains("-c"))
    }
}
