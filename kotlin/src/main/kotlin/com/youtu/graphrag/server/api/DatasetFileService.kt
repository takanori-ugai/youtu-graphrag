package com.youtu.graphrag.server.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.youtu.graphrag.server.api.contracts.DatasetInfo
import com.youtu.graphrag.server.api.contracts.DatasetsResponse
import com.youtu.graphrag.shared.ingest.BestEffortDocumentParser
import com.youtu.graphrag.shared.ingest.DocumentParser
import com.youtu.graphrag.shared.io.decodeBytesWithDetection
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val demoSchema: Map<String, Any> =
    mapOf(
        "Nodes" to
            listOf(
                "person",
                "location",
                "organization",
                "event",
                "object",
                "concept",
                "time_period",
                "creative_work",
                "biological_entity",
                "natural_phenomenon",
            ),
        "Relations" to
            listOf(
                "is_a",
                "part_of",
                "located_in",
                "created_by",
                "used_by",
                "participates_in",
                "related_to",
                "belongs_to",
                "influences",
                "precedes",
                "arrives_in",
                "comparable_to",
            ),
        "Attributes" to
            listOf(
                "name",
                "date",
                "size",
                "type",
                "description",
                "status",
                "quantity",
                "value",
                "position",
                "duration",
                "time",
            ),
    )

data class IncomingFile(
    val fileName: String,
    val bytes: ByteArray,
)

data class TempFileInfo(
    val originalFileName: String,
    val tempFilePath: Path,
)

data class UploadResult(
    val success: Boolean,
    val message: String,
    val datasetName: String? = null,
    val filesCount: Int? = null,
)

class DatasetFileService(
    private val rootDir: Path = Path.of("."),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val documentParser: DocumentParser = BestEffortDocumentParser(),
) {
    private val logger = KotlinLogging.logger {}
    private val mapper = ObjectMapper().registerKotlinModule()

    companion object {
        const val MAX_UPLOAD_FILE_SIZE = 50 * 1024 * 1024L // 50MB
        const val MAX_SCHEMA_FILE_SIZE = 5 * 1024 * 1024L // 5MB

        private val ALLOWED_EXTENSIONS =
            setOf(
                ".txt",
                ".md",
                ".json",
                ".pdf",
                ".docx",
                ".doc",
            )

        fun InputStream.copyToWithLimit(
            output: OutputStream,
            maxSize: Long,
        ): Long {
            val buffer = ByteArray(8192)
            var totalRead = 0L
            while (true) {
                val read = this.read(buffer)
                if (read == -1) break
                totalRead += read
                require(totalRead <= maxSize) {
                    "File exceeds maximum allowed size of $maxSize bytes"
                }
                output.write(buffer, 0, read)
            }
            return totalRead
        }
    }

    private val dataUploadedDir = rootDir.resolve("data/uploaded")
    private val outputGraphsDir = rootDir.resolve("output/graphs")
    private val outputChunksDir = rootDir.resolve("output/chunks")
    private val outputLogsDir = rootDir.resolve("output/logs")
    private val schemasDir = rootDir.resolve("schemas")
    private val faissCacheRoot = rootDir.resolve("retriever/faiss_cache_new")

    private fun requireSafeDatasetName(datasetName: String): String {
        require(Regex("^[A-Za-z0-9_-]+$").matches(datasetName)) {
            "Invalid dataset name"
        }
        return datasetName
    }

    fun ensureStartupDirectories() {
        listOf(dataUploadedDir, outputGraphsDir, outputChunksDir, outputLogsDir, schemasDir).forEach { it.createDirectories() }
        ensureDemoSchemaExists()
    }

    fun uploadFilesFromTemp(tempFiles: List<TempFileInfo>): UploadResult {
        require(tempFiles.isNotEmpty()) { "No files were provided" }

        val datasetName = generateDatasetName(tempFiles.map { it.originalFileName })
        val uploadDir = dataUploadedDir.resolve(datasetName).apply { createDirectories() }

        val corpusData = mutableListOf<Any>()
        val skippedFiles = mutableListOf<String>()
        var processedCount = 0

        try {
            tempFiles.forEachIndexed { index, tempFile ->
                val safeFileName = sanitizeUploadFileName(tempFile.originalFileName, index)
                val filePath = uploadDir.resolve(safeFileName)

                Files.move(tempFile.tempFilePath, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

                val extension = safeFileName.substringAfterLast('.', "").lowercase()
                val extWithDot = if (extension.isEmpty()) "" else ".$extension"
                if (extWithDot !in ALLOWED_EXTENSIONS) {
                    skippedFiles.add(safeFileName)
                    return@forEachIndexed
                }

                when (extWithDot) {
                    ".txt", ".md" -> {
                        val text = decodeBytesWithDetection(Files.readAllBytes(filePath))
                        corpusData.add(mapOf("title" to safeFileName, "text" to text))
                        processedCount += 1
                    }

                    ".json" -> {
                        val bytes = Files.readAllBytes(filePath)
                        val decoded = decodeBytesWithDetection(bytes)
                        runCatching { mapper.readTree(decoded) }
                            .onSuccess { parsed ->
                                if (parsed.isArray) {
                                    parsed.forEach { node ->
                                        corpusData.add(mapper.convertValue(node, Any::class.java))
                                    }
                                } else {
                                    corpusData.add(mapper.convertValue(parsed, Any::class.java))
                                }
                                processedCount += 1
                            }.onFailure {
                                corpusData.add(mapOf("title" to safeFileName, "text" to decoded))
                                processedCount += 1
                            }
                    }

                    ".pdf", ".docx", ".doc" -> {
                        val parsedText = documentParser.parseFile(filePath.toString(), extWithDot)
                        if (parsedText.isBlank()) {
                            skippedFiles.add(safeFileName)
                        } else {
                            corpusData.add(mapOf("title" to safeFileName, "text" to parsedText))
                            processedCount += 1
                        }
                    }
                }
            }
        } finally {
            tempFiles.forEach { it.tempFilePath.deleteIfExists() }
        }

        val skippedSummary =
            if (skippedFiles.isEmpty()) {
                ""
            } else {
                "; skipped: ${skippedFiles.joinToString(", ")}"
            }
        require(processedCount > 0) {
            "No supported files were uploaded. Allowed: .txt, .md, .json, .pdf, .docx, .doc$skippedSummary"
        }

        val corpusPath = uploadDir.resolve("corpus.json")
        mapper.writerWithDefaultPrettyPrinter().writeValue(corpusPath.toFile(), corpusData)

        val message =
            buildString {
                append("Files uploaded successfully")
                if (skippedFiles.isNotEmpty()) {
                    append("; skipped unsupported: ")
                    append(skippedFiles.joinToString(", "))
                }
            }

        logger.info { "Uploaded dataset '$datasetName' with $processedCount supported file(s)" }

        return UploadResult(
            success = true,
            message = message,
            datasetName = datasetName,
            filesCount = processedCount,
        )
    }

    fun uploadFiles(files: List<IncomingFile>): UploadResult {
        require(files.isNotEmpty()) { "No files were provided" }

        val tempFiles =
            files.map { file ->
                val tempFile = Files.createTempFile("graphrag_compat_", ".tmp")
                Files.write(tempFile, file.bytes)
                TempFileInfo(file.fileName, tempFile)
            }

        return uploadFilesFromTemp(tempFiles)
    }

    fun getDatasets(): DatasetsResponse {
        val datasets = mutableListOf<DatasetInfo>()

        if (dataUploadedDir.exists() && dataUploadedDir.isDirectory()) {
            dataUploadedDir
                .listDirectoryEntries()
                .filter { it.isDirectory() }
                .sortedBy { it.name }
                .forEach { datasetDir ->
                    val corpusPath = datasetDir.resolve("corpus.json")
                    if (!corpusPath.exists()) {
                        return@forEach
                    }

                    val datasetName = datasetDir.name
                    val graphPath = outputGraphsDir.resolve("${datasetName}_new.json")
                    datasets.add(
                        DatasetInfo(
                            name = datasetName,
                            type = "uploaded",
                            status = if (graphPath.exists()) "ready" else "needs_construction",
                            hasCustomSchema = schemasDir.resolve("$datasetName.json").exists(),
                        ),
                    )
                }
        }

        val demoCorpusPath = rootDir.resolve("data/demo/demo_corpus.json")
        if (demoCorpusPath.exists()) {
            datasets.add(
                DatasetInfo(
                    name = "demo",
                    type = "demo",
                    status = if (outputGraphsDir.resolve("demo_new.json").exists()) "ready" else "needs_construction",
                    hasCustomSchema = false,
                ),
            )
        }

        return DatasetsResponse(datasets = datasets)
    }

    fun saveSchema(
        datasetName: String,
        schemaFileName: String,
        inputStream: InputStream,
    ): Map<String, Any> {
        val safeDatasetName = requireSafeDatasetName(datasetName)
        require(safeDatasetName != "demo") { "Cannot upload schema for demo dataset" }

        require(schemaFileName.lowercase().endsWith(".json")) { "Schema file must be a .json file" }

        val tempFile = Files.createTempFile("graphrag_schema_", ".json")
        val bytes =
            try {
                inputStream.use { input ->
                    input.copyToWithLimit(Files.newOutputStream(tempFile), MAX_SCHEMA_FILE_SIZE)
                }
                Files.readAllBytes(tempFile)
            } finally {
                tempFile.deleteIfExists()
            }

        val decoded = decodeBytesWithDetection(bytes)
        val parsedResult = runCatching { mapper.readTree(decoded) }
        require(parsedResult.isSuccess) {
            "Invalid JSON: ${parsedResult.exceptionOrNull()?.message}"
        }
        val parsed = parsedResult.getOrThrow()

        require(parsed.isObject) { "Schema JSON must be an object" }

        schemasDir.createDirectories()
        mapper.writerWithDefaultPrettyPrinter().writeValue(schemasDir.resolve("$safeDatasetName.json").toFile(), parsed)

        return mapOf(
            "success" to true,
            "message" to "Schema uploaded successfully",
            "dataset_name" to safeDatasetName,
        )
    }

    fun deleteDataset(datasetName: String): Map<String, Any> {
        val safeDatasetName = requireSafeDatasetName(datasetName)
        require(safeDatasetName != "demo") { "Cannot delete demo dataset" }

        val deletedFiles = mutableListOf<String>()

        deleteRecursivelyIfExists(dataUploadedDir.resolve(safeDatasetName), deletedFiles)
        deletePathIfExists(outputGraphsDir.resolve("${safeDatasetName}_new.json"), deletedFiles)
        deletePathIfExists(outputChunksDir.resolve("$safeDatasetName.txt"), deletedFiles)
        deletePathIfExists(schemasDir.resolve("$safeDatasetName.json"), deletedFiles)
        deleteRecursivelyIfExists(faissCacheRoot.resolve(safeDatasetName), deletedFiles)

        return mapOf(
            "success" to true,
            "message" to "Dataset '$safeDatasetName' deleted successfully",
            "deleted_files" to deletedFiles,
        )
    }

    private fun ensureDemoSchemaExists() {
        val demoSchemaPath = schemasDir.resolve("demo.json")
        if (demoSchemaPath.exists()) {
            return
        }

        schemasDir.createDirectories()
        mapper.writerWithDefaultPrettyPrinter().writeValue(demoSchemaPath.toFile(), demoSchema)
    }

    private fun generateDatasetName(fileNames: List<String>): String {
        val initialName =
            if (fileNames.size == 1) {
                val rawName = fileNames.single().substringBeforeLast('.', fileNames.single())
                val cleaned =
                    rawName
                        .filter { character ->
                            character.isLetterOrDigit() || character == ' ' || character == '-' || character == '_'
                        }.trim()
                        .replace(' ', '_')

                if (cleaned.isBlank()) {
                    "dataset"
                } else {
                    cleaned
                }
            } else {
                val dateString = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)
                "${fileNames.size}files_$dateString"
            }

        var candidate = initialName
        var counter = 1
        while (dataUploadedDir.resolve(candidate).exists()) {
            candidate = "${initialName}_$counter"
            counter += 1
        }

        return candidate
    }

    private fun sanitizeUploadFileName(
        fileName: String,
        index: Int,
    ): String {
        val withForwardSlashHandled = fileName.substringAfterLast('/')
        val withBackSlashHandled = withForwardSlashHandled.substringAfterLast('\\')
        val cleaned = withBackSlashHandled.trim()

        if (cleaned.isNotEmpty()) {
            return cleaned
        }

        return "uploaded_$index"
    }

    private fun deletePathIfExists(
        path: Path,
        deletedFiles: MutableList<String>,
    ) {
        if (path.deleteIfExists()) {
            deletedFiles.add(path.toString())
        }
    }

    private fun deleteRecursivelyIfExists(
        path: Path,
        deletedFiles: MutableList<String>,
    ) {
        if (!path.exists()) {
            return
        }

        if (path.isDirectory()) {
            path
                .listDirectoryEntries()
                .forEach { child ->
                    deleteRecursivelyIfExists(child, deletedFiles)
                }
        }

        if (path.deleteIfExists()) {
            deletedFiles.add(path.toString())
        }
    }
}
