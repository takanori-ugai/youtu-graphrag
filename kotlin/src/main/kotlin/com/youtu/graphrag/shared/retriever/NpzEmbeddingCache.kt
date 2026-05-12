package com.youtu.graphrag.shared.retriever

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Minimal NPZ (zip of NPY arrays) codec for float32 1D embedding vectors.
 */
internal object NpzEmbeddingCache {
    private val npyMagic =
        byteArrayOf(
            0x93.toByte(),
            'N'.code.toByte(),
            'U'.code.toByte(),
            'M'.code.toByte(),
            'P'.code.toByte(),
            'Y'.code.toByte(),
        )

    fun read(
        path: Path,
        expectedDimensions: Int,
    ): Map<String, FloatArray> {
        if (!path.exists()) {
            return emptyMap()
        }

        val vectors = linkedMapOf<String, FloatArray>()
        Files.newInputStream(path).use { fileInput ->
            ZipInputStream(fileInput).use { zipInput ->
                var entry = zipInput.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".npy")) {
                        val key = entry.name.removeSuffix(".npy")
                        val bytes = readCurrentEntryBytes(zipInput)
                        val vector = decodeNpyFloat32(bytes)
                        if (key.isNotBlank() && vector != null && vector.size == expectedDimensions) {
                            vectors[key] = vector
                        }
                    }
                    zipInput.closeEntry()
                    entry = zipInput.nextEntry
                }
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
        Files.newOutputStream(path).use { fileOutput ->
            ZipOutputStream(fileOutput).use { zipOutput ->
                vectorsByKey.forEach { (key, vector) ->
                    if (key.isBlank() || vector.size != expectedDimensions) {
                        return@forEach
                    }
                    val entry = ZipEntry("$key.npy")
                    zipOutput.putNextEntry(entry)
                    zipOutput.write(encodeNpyFloat32(vector))
                    zipOutput.closeEntry()
                }
            }
        }
    }

    private fun readCurrentEntryBytes(zipInput: ZipInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = zipInput.read(buffer)
            if (read <= 0) {
                break
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun decodeNpyFloat32(bytes: ByteArray): FloatArray? {
        if (bytes.size < 10 || !bytes.copyOfRange(0, 6).contentEquals(npyMagic)) {
            return null
        }

        val majorVersion = bytes[6].toInt() and 0xFF
        val minorVersion = bytes[7].toInt() and 0xFF
        if (minorVersion !in 0..9) {
            return null
        }

        val headerLengthOffset: Int
        val headerLength: Int
        when (majorVersion) {
            1 -> {
                headerLengthOffset = 10
                headerLength = littleEndianUnsignedShort(bytes, 8)
            }

            2 -> {
                headerLengthOffset = 12
                headerLength = littleEndianInt(bytes, 8)
            }

            else -> {
                return null
            }
        }

        if (headerLength <= 0 || bytes.size < headerLengthOffset + headerLength) {
            return null
        }

        val header =
            String(
                bytes,
                headerLengthOffset,
                headerLength,
                StandardCharsets.US_ASCII,
            )

        if ("'fortran_order': True" in header || "\"fortran_order\": True" in header) {
            return null
        }
        if (!header.contains("'descr': '<f4'") && !header.contains("\"descr\": \"<f4\"")) {
            return null
        }

        val shapeMatch =
            Regex("['\"]shape['\"]\\s*:\\s*\\(([^\\)]*)\\)")
                .find(header)
                ?: return null
        val shapeValues =
            shapeMatch.groupValues[1]
                .split(',')
                .mapNotNull { raw -> raw.trim().takeIf { it.isNotEmpty() }?.toIntOrNull() }
        if (shapeValues.size != 1) {
            return null
        }
        val dimensions = shapeValues.first()
        if (dimensions <= 0) {
            return null
        }

        val dataOffset = headerLengthOffset + headerLength
        val dataLength = dimensions * Float.SIZE_BYTES
        if (bytes.size < dataOffset + dataLength) {
            return null
        }

        val vector = FloatArray(dimensions)
        val dataBuffer =
            ByteBuffer
                .wrap(bytes, dataOffset, dataLength)
                .order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until dimensions) {
            vector[index] = dataBuffer.float
        }
        return vector
    }

    private fun encodeNpyFloat32(vector: FloatArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(npyMagic)
        out.write(byteArrayOf(1, 0))

        val headerBase = "{'descr': '<f4', 'fortran_order': False, 'shape': (${vector.size},), }"
        val preambleSize = 10
        val header = buildNpyHeader(headerBase, preambleSize)
        val headerLength = header.size
        out.write(byteArrayOf((headerLength and 0xFF).toByte(), ((headerLength ushr 8) and 0xFF).toByte()))
        out.write(header)

        val data =
            ByteBuffer
                .allocate(vector.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { value -> data.putFloat(value) }
        out.write(data.array())
        return out.toByteArray()
    }

    private fun buildNpyHeader(
        base: String,
        preambleSize: Int,
    ): ByteArray {
        val bodyLength = base.length + 1
        val padding = (16 - ((preambleSize + bodyLength) % 16)) % 16
        val header = base + " ".repeat(padding) + "\n"
        return header.toByteArray(StandardCharsets.US_ASCII)
    }

    private fun littleEndianUnsignedShort(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        return b0 or (b1 shl 8)
    }

    private fun littleEndianInt(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
