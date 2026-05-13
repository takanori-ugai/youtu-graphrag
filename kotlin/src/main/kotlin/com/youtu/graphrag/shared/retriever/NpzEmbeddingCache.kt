package com.youtu.graphrag.shared.retriever

import org.jetbrains.bio.npy.NpzFile
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * NPZ codec for float32 1D embedding vectors, backed by Multik's JVM NPY/NPZ stack.
 */
internal object NpzEmbeddingCache {
    fun read(
        path: Path,
        expectedDimensions: Int,
    ): Map<String, FloatArray> {
        if (!path.exists()) {
            return emptyMap()
        }

        val vectors = linkedMapOf<String, FloatArray>()
        NpzFile.read(path).use { reader ->
            reader.introspect().forEach { entry ->
                val key = entry.name
                if (key.isBlank()) {
                    return@forEach
                }
                if (entry.type != Float::class.java && entry.type != Float::class.javaObjectType) {
                    return@forEach
                }

                val npyArray = reader[key]
                if (npyArray.shape.size != 1 || npyArray.shape[0] != expectedDimensions) {
                    return@forEach
                }
                vectors[key] = npyArray.asFloatArray()
            }
        }
        return vectors
    }

    fun write(
        path: Path,
        vectorsByKey: Map<String, FloatArray>,
        expectedDimensions: Int,
    ) {
        if (vectorsByKey.isEmpty()) {
            return
        }

        path.parent?.createDirectories()
        NpzFile.write(path, compressed = true).use { writer ->
            vectorsByKey.forEach { (key, vector) ->
                if (key.isBlank() || vector.size != expectedDimensions) {
                    return@forEach
                }
                writer.write(
                    name = key,
                    data = vector,
                    shape = intArrayOf(vector.size),
                    order = ByteOrder.LITTLE_ENDIAN,
                )
            }
        }
    }
}
