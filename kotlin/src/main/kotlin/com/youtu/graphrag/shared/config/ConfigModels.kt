package com.youtu.graphrag.shared.config

data class DatasetConfig(
    val corpusPath: String = "",
    val qaPath: String = "",
    val schemaPath: String = "",
    val graphOutput: String = "",
)

data class TriggersConfig(
    val constructorTrigger: Boolean = true,
    val retrieveTrigger: Boolean = true,
    val mode: String = "agent",
)

data class TreeCommConfig(
    val embeddingModel: String = "all-MiniLM-L6-v2",
    val structWeight: Double = 0.3,
    val enableFastMode: Boolean = true,
    val maxTotalCommunities: Int = 100,
)

data class ConstructionConfig(
    val mode: String = "agent",
    val maxWorkers: Int = 32,
    val datasetsNoChunk: List<String> =
        listOf(
            "hotpot",
            "2wiki",
            "musique",
            "graphrag-bench",
            "anony_chs",
            "anony_eng",
        ),
    val chunkSize: Int = 1000,
    val overlap: Int = 200,
    val treeComm: TreeCommConfig = TreeCommConfig(),
)

data class FaissConfig(
    val searchK: Int = 50,
    val maxWorkers: Int = 4,
    val device: String = "cpu",
)

data class AgentConfig(
    val maxSteps: Int = 5,
    val enableIrcot: Boolean = true,
    val enableParallelSubquestions: Boolean = true,
)

data class RetrievalConfig(
    val topK: Int = 5,
    val recallPaths: Int = 2,
    val topKFilter: Int = 20,
    val similarityThreshold: Double = 0.3,
    val enableQueryEnhancement: Boolean = true,
    val enableReranking: Boolean = true,
    val enableHighRecall: Boolean = true,
    val enableCaching: Boolean = true,
    val cacheDir: String = "retriever/faiss_cache_new",
    val faiss: FaissConfig = FaissConfig(),
    val agent: AgentConfig = AgentConfig(),
)

data class EmbeddingsConfig(
    val modelName: String = "all-MiniLM-L6-v2",
    val device: String = "cpu",
    val batchSize: Int = 32,
    val maxLength: Int = 512,
)

data class NlpConfig(
    val spacyModel: String = "en_core_web_lg",
)

data class OutputConfig(
    val baseDir: String = "output",
    val graphsDir: String = "output/graphs",
    val chunksDir: String = "output/chunks",
    val logsDir: String = "output/logs",
    val saveIntermediateResults: Boolean = true,
    val saveChunkDetails: Boolean = true,
)

data class PerformanceConfig(
    val parallelProcessing: Boolean = true,
    val maxWorkers: Int = 32,
    val batchSize: Int = 16,
    val memoryOptimization: Boolean = true,
)

data class EvaluationConfig(
    val enableEvaluation: Boolean = true,
    val metrics: List<String> = listOf("accuracy", "precision", "recall", "f1"),
    val saveDetailedResults: Boolean = true,
)

data class AppConfig(
    val datasets: Map<String, DatasetConfig> = emptyMap(),
    val triggers: TriggersConfig = TriggersConfig(),
    val construction: ConstructionConfig = ConstructionConfig(),
    val retrieval: RetrievalConfig = RetrievalConfig(),
    val embeddings: EmbeddingsConfig = EmbeddingsConfig(),
    val nlp: NlpConfig = NlpConfig(),
    val prompts: Map<String, Map<String, String>> = emptyMap(),
    val output: OutputConfig = OutputConfig(),
    val performance: PerformanceConfig = PerformanceConfig(),
    val evaluation: EvaluationConfig = EvaluationConfig(),
)
