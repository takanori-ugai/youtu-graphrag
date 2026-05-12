# Youtu-GraphRAG Kotlin Compliance Implementation Plan

## Goal
Build a Kotlin implementation in this `kotlin/` project that is fully compliant with the Python implementation in `../`, including:
- CLI workflow parity (`main.py`)
- Web/API/WebSocket parity (`backend.py`)
- Config/schema/data compatibility (`config/`, `schemas/`, `data/`)
- Knowledge graph construction parity (`models/constructor/kt_gen.py`)
- Retrieval/agentic reasoning parity (`models/retriever/*.py`)
- Output/cache format compatibility (`output/*`, `retriever/faiss_cache_new/*`)

## Definition of "Fully Compliant"
The Kotlin version is considered compliant when all of the following pass:
1. Same API routes, request/response JSON fields, and WebSocket event shapes as Python.
2. Same config keys and runtime override behavior as `config/base_config.yaml`.
3. Same input/output file contracts:
   - `output/graphs/{dataset}_new.json`
   - `output/chunks/{dataset}.txt`
   - `retriever/faiss_cache_new/{dataset}/...`
4. Same execution modes and triggers (`agent`/`noagent`, constructor/retrieve toggles).
5. Retrieval+answering pipeline parity (decomposition -> retrieval -> optional IRCoT).
6. Golden parity tests demonstrate equivalent behavior on `demo` dataset (allowing bounded non-determinism for LLM text).

## Current State (Kotlin)
- `kotlin/` currently has Gradle setup and dependencies but `src/` implementation is missing/empty.
- Project name in `settings.gradle.kts` is still `causalrag`.
- `build.gradle.kts` contains many required libraries (Ktor, LangChain4j, JGraphT, etc.).
- No parity test harness exists against Python baseline.

## Target Kotlin Architecture
Proposed package layout:
- `com.youtu.graphrag.shared.config` -> YAML/JSON config loader + typed config models + override support
- `com.youtu.graphrag.shared.llm` -> LangChain4j-based OpenAI/Azure client
- `com.youtu.graphrag.shared.graph` -> JGraphT models + JSON load/save (NetworkX parity)
- `com.youtu.graphrag.shared.constructor` -> `KTBuilder` equivalent
- `com.youtu.graphrag.shared.treecomm` -> Community detection (Smile + NetworkAnalysis)
- `com.youtu.graphrag.shared.retriever` -> Indexing + Retrieval (FAISS parity via HNSW/Lucene)
- `com.youtu.graphrag.shared.decomposer` -> `GraphQ` equivalent
- `com.youtu.graphrag.shared.ingest` -> Document parsing (Apache Tika + PDFBox)
- `com.youtu.graphrag.server.api` -> Ktor REST + WebSocket parity with `backend.py`
- `com.youtu.graphrag.cli` -> Picocli command entrypoint parity with `main.py`

## Phase Plan

### Phase 0: Baseline Lock + Scaffolding
- Create `src/main/kotlin` and `src/test/kotlin` structure.
- Rename application identity from `causalrag` to `youtu-graphrag` in `settings.gradle.kts` and `build.gradle.kts`.
- Freeze Python baseline snapshots (API schema, sample outputs, cache files) for regression.
- Add parity test utilities (JSON canonicalization, tolerance matchers).

Exit criteria:
- Kotlin project builds with empty module stubs and parity test scaffold.

### Phase 1: Config, Models, and Contracts
- Port `ConfigManager` and typed configs from `config/config_loader.py`.
- Ensure YAML key compatibility with existing `base_config.yaml` (including prompts, retrieval, triggers).
- Implement dataset config resolution and output directory creation.
- Define Kotlin DTOs for all API request/response/WebSocket payloads used by frontend.
- Implement robust decoding with encoding detection (match `decode_bytes_with_detection` in `backend.py`).

Exit criteria:
- Config load + override behavior matches Python for known fixtures.

### Phase 1.5: Config Format Migration (YAML -> JSON)
- Define canonical JSON config contract equivalent to `config/base_config.yaml`.
- Build a one-time converter utility (deterministic key ordering).
- Update config documentation and examples to JSON syntax.

Exit criteria:
- JSON config is the default in Kotlin runtime and CI.

### Phase 2: LLM Client + Prompt Engine
- Port `LLMCompletionCall` behavior using LangChain4j:
  - env vars parity (`LLM_MODEL`, `LLM_BASE_URL`, `LLM_API_KEY`, `OPENAI_PROVIDER`, etc.)
  - OpenAI/Azure endpoint compatibility
  - response cleanup (fence stripping, JSON prefix normalization)
- Port prompt selection/format behavior for construction/decomposition/retrieval modes.
- Implement `json_repair` equivalent for robust LLM output parsing.

Exit criteria:
- Prompt generation snapshots match Python templates.
- Integration test proves LLM call path with mock server.

### Phase 3: Graph I/O + Constructor Parity
- Port `KTBuilder`:
  - Token-based chunking with overlap (using JTokkit)
  - Entity/attribute/triple extraction pipeline
  - Graph levels (attribute/entity/keyword/community)
  - Schema evolution in agent mode (updates `schemas/{dataset}.json`)
  - Graph serialization format parity (JGraphT -> Relationship JSON)
- Port `DocumentParser` using Apache Tika/PDFBox (support PDF, DOCX, DOC, RTF).

Exit criteria:
- On deterministic mocked LLM outputs, graph JSON and chunk file match Python format.

### Phase 4: TreeComm Parity
- Port `FastTreeComm` using Smile/NetworkAnalysis:
  - Semantic + structural similarity blending
  - KMeans clustering/refinement strategy
  - Community naming/summaries via LLM batch prompts
  - Keyword node generation and link rules

Exit criteria:
- Community + keyword node/edge structures match Python schema expectations.

### Phase 5: Retriever + Indexing Parity
- Port `DualFAISSRetriever` + `KTRetriever` retrieval pipeline:
  - Node/relation/triple/community indexing
  - Dual-path retrieval (triples + communities)
  - Type-filtered retrieval
  - Keyword search, path expansion, triple reranking
  - Chunk embedding retrieval + reranking
- **Technical spike required**: Implement local ANN parity for FAISS using `hnswlib-jna` or `Lucene Vector Search` via LangChain4j. Ensure serialization format is compatible or documented if diverging.

Exit criteria:
- Retrieval result shape and scoring pipeline pass parity tests on demo fixtures.

### Phase 6: Agentic Decomposer + IRCoT Loop
- Port `GraphQ.decompose`.
- Port no-agent and agent flows from `main.py` / `backend.py`:
  - Sub-question decomposition
  - Parallel sub-question retrieval (using Coroutines)
  - Iterative reasoning (IRCoT: thoughts -> new query or final answer)
  - Context assembly and final answer generation

Exit criteria:
- End-to-end QA flow works in both modes with expected intermediate artifacts.

### Phase 7: Web Backend/API + WebSocket Parity
- Implement Ktor endpoints matching Python:
  - `POST /api/upload`, `POST /api/construct-graph`, `POST /api/ask-question`, etc.
  - Preserve `qa_update`, `progress`, `qa_complete` WebSocket event shapes.
- Serve `frontend/` and `assets/` static content.

Exit criteria:
- Existing frontend works unmodified against Kotlin backend.

### Phase 8: CLI Parity
- Implement Picocli CLI args and behavior matching `main.py`:
  - `--config`, `--datasets`, `--override`
- Support constructor-only, retriever-only, and combined workflows.

Exit criteria:
- CLI command matrix works with Kotlin entrypoint.

### Phase 9: Verification and Compliance Gates
- Contract tests for API/WS.
- Snapshot tests for graph/chunk outputs.
- Parity tests (Python-vs-Kotlin) on `demo` dataset.
- Quality gates: `ktlint`, `detekt`, `test`.

Exit criteria:
- All compliance checks green.

### Phase 10: Packaging and Docs
- Update README for Kotlin modes.
- Add Kotlin `start.sh` equivalents.

## Technical Choices & Dependencies (Build Alignment)
- **Runtime**: Kotlin 2.3.20 (JVM)
- **Web**: Ktor 3.4.1 (Netty)
- **Graph**: JGraphT 1.5.2 + NetworkAnalysis 1.3.0
- **LLM**: LangChain4j 1.12.2
- **Tokenizer**: JTokkit 1.1.0
- **Math/ML**: Smile 4.4.2
- **CLI**: Picocli 4.7.6
- **Config**: Jackson YAML/JSON
- **Ingest**: Apache PDFBox 3.0.7 (consider adding Apache Tika)

## Risks and Mitigations
1. **FAISS Parity**: FAISS is native-heavy.
   - Mitigation: Use a robust JVM ANN (HNSW) and ensure the logic matches Python's dual-path retrieval.
2. **LLM Output Variance**: LLM might return different JSON.
   - Mitigation: Use strict prompts and `json_repair` equivalent.
3. **Graph Algorithm Delta**: NetworkX and JGraphT might differ in some default behaviors.
   - Mitigation: Use explicit JGraphT implementations for required algorithms (shortest path, neighbors).
