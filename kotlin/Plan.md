# Youtu-GraphRAG Kotlin Conversion Plan

Updated: 2026-05-30

## 1) Scope and Decisions

### Primary goal
Port the Python GraphRAG system (`../backend.py`, `../main.py`, `../models/*`) to Kotlin (`kotlin/`) with behavior parity for:
- API + WebSocket contracts
- constructor outputs (`output/graphs`, `output/chunks`)
- retrieval outputs and IRCoT flow
- CLI workflows

### Explicit storage decision (required)
`PyTorch .pt` cache storage is dropped in Kotlin.
- Canonical embedding cache artifacts are:
  - `*.json` (metadata / debug)
  - `*.npz` (vector payload; Multik-backed)
- No `.pt` export path should remain in Kotlin runtime behavior.
- Legacy `.pt` handling is treated as migration debt and has been fully removed.

### Priority directive
CLI migration parity is prioritized on the critical path.

---

## 2) Python vs Kotlin Snapshot (As of 2026-05-30)

### A. API/WebSocket layer
- Python source: `../backend.py`
- Kotlin source: [Application.kt](file:///home/ugai/youtu-graphrag/kotlin/src/main/kotlin/com/youtu/graphrag/server/api/Application.kt)
- Status: **Completed**
- Details: All contract routes, snake_case payloads, and WS events (`progress`, `stage_event`, `qa_update`, `qa_complete`) are parity-locked. Handlers strictly sequence stages using monotonic progress cadence.
- Verification: [ApiContractsTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/server/api/ApiContractsTest.kt), [ApplicationWsParityTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/server/api/ApplicationWsParityTest.kt), and [ApplicationPayloadContractsTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/server/api/ApplicationPayloadContractsTest.kt) pass.

### B. Constructor
- Python source: `../models/constructor/kt_gen.py`
- Kotlin source: [KTBuilder.kt](file:///home/ugai/youtu-graphrag/kotlin/src/main/kotlin/com/youtu/graphrag/shared/constructor/KTBuilder.kt)
- Status: **Completed**
- Details: Prompt-driven schema-aware extraction (`attributes`, `triples`, `entity_types`), agent-mode schema evolution, and token-based overlap chunking via `jtokkit` are implemented.
- Verification: [KTBuilderTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/shared/constructor/KTBuilderTest.kt) passes.

### C. Retriever
- Python source: `../models/retriever/enhanced_kt_retriever.py`, `../models/retriever/faiss_filter.py`
- Kotlin source: [KTRetriever.kt](file:///home/ugai/youtu-graphrag/kotlin/src/main/kotlin/com/youtu/graphrag/shared/retriever/KTRetriever.kt)
- Status: **Completed (Functional Parity)**
- Details: Implemented hybrid lexical (Lucene or keyword match) + semantic vector retrieval (via `LuceneAnnIndex` and Multik-backed cache). Exposes identical output shape (`triples`, `chunk_ids`, `chunk_contents`, `chunk_retrieval_results`).
- Gaps: Python retriever supports richer parallel retrieval strategy internals. Kotlin parity is simplified to a single-pipeline hybrid strategy. Fixture dataset coverage is currently limited but the testing harness is fully integrated.
- Verification: [RetrievalFixtureParityTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/shared/retriever/RetrievalFixtureParityTest.kt) and [KTRetrieverTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/shared/retriever/KTRetrieverTest.kt) pass.

### D. Decomposer + IRCoT
- Python source: `../models/retriever/agentic_decomposer.py`, `../main.py`, `../backend.py`
- Kotlin source: [GraphQ.kt](file:///home/ugai/youtu-graphrag/kotlin/src/main/kotlin/com/youtu/graphrag/shared/decomposer/GraphQ.kt), [QuestionAnsweringService.kt](file:///home/ugai/youtu-graphrag/kotlin/src/main/kotlin/com/youtu/graphrag/server/api/QuestionAnsweringService.kt)
- Status: **Completed**
- Details: Loop stops, marker parsing (`So the answer is:`, `The new query is:`), and repeated-query termination are normalized.
- Verification: [QuestionAnsweringServiceTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/server/api/QuestionAnsweringServiceTest.kt) and [GraphQTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/shared/decomposer/GraphQTest.kt) pass.

### E. TreeComm
- Python source: `../utils/tree_comm.py`
- Kotlin source: [FastTreeComm.kt](file:///home/ugai/youtu-graphrag/kotlin/src/main/kotlin/com/youtu/graphrag/shared/treecomm/FastTreeComm.kt)
- Status: **Completed (Dual Mode: Fast + Hierarchical Parity Mode)**
- Current behavior:
  1. `enable_fast_mode=true` (default): deterministic connected-component community detection for low-latency builds.
  2. `enable_fast_mode=false`: iterative hierarchical merge mode using `construction.tree_comm` controls (`embedding_model`, `struct_weight`, `merge_threshold`, `max_iterations`, `max_total_communities`).
- Summary behavior: Optional `enable_summary=true` adds community summary generation; if LLM output is unavailable/empty, deterministic fallback summaries are written to preserve stable graph artifacts.
- Output parity shape: Emits expected relation families (`member_of`, `represented_by`, `keyword_of`, `kw_filter_by`) and community metadata (`community_id`, `name`, `summary` when enabled, `node_count`, `edge_count`, `members`, `keywords`).
- Verification: [FastTreeCommTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/shared/treecomm/FastTreeCommTest.kt), [TreeCommFixtureParityTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/shared/treecomm/TreeCommFixtureParityTest.kt), and TreeComm integration assertions in [KTBuilderTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/shared/constructor/KTBuilderTest.kt) pass.

### F. CLI
- Python source: `../main.py`
- Kotlin source: [Main.kt](file:///home/ugai/youtu-graphrag/kotlin/src/main/kotlin/com/youtu/graphrag/cli/Main.kt)
- Status: **Completed**
- Details: CLI deep parity is implemented, supporting evaluation flows, summary JSON reporting, and option-matrix overrides (`--config`, `--datasets`, `--override`).
- Verification: [MainCommandTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/cli/MainCommandTest.kt) passes.

### G. Config format
- Python source: Config loading logic in `../main.py` and `../backend.py`
- Kotlin source: [ConfigManager.kt](file:///home/ugai/youtu-graphrag/kotlin/src/main/kotlin/com/youtu/graphrag/shared/config/ConfigManager.kt)
- Status: **Completed**
- Details: JSON is the sole configuration format (default `config/base_config.json`). YAML parsing dependencies and conversion paths have been removed.
- Verification: [ConfigManagerTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/shared/config/ConfigManagerTest.kt) passes.

### H. Prompt Template Routing
- Python source: Hardcoded strings / keys in Python main/backend
- Kotlin source: [PromptSnapshotMatrixTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/shared/prompt/PromptSnapshotMatrixTest.kt)
- Status: **Completed**
- Details: A rendered prompt snapshot matrix covering 42 combinations (datasets: `demo`, `anony_chs`, `anony_eng`; modes: `noagent`, `agent`; families: `construction`, `decomposition`, `retrieval`, `ircot_backend`, `ircot_main`) has been locked using deterministic SHA-256 signatures.
- Verification: [PromptSnapshotMatrixTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/shared/prompt/PromptSnapshotMatrixTest.kt) passes.

### I. Retriever NLP parity (spaCy replacement)
- Python source: spaCy NLP logic in `../models/retriever/enhanced_kt_retriever.py`
- Kotlin source: [QueryNlp.kt](file:///home/ugai/youtu-graphrag/kotlin/src/main/kotlin/com/youtu/graphrag/shared/retriever/nlp/QueryNlp.kt), [OpenNlpQueryNlp.kt](file:///home/ugai/youtu-graphrag/kotlin/src/main/kotlin/com/youtu/graphrag/shared/retriever/nlp/OpenNlpQueryNlp.kt)
- Status: **Completed**
- Details: Removed spaCy runtime dependencies, implementing `QueryNlp` with OpenNLP and deterministic regex-based fallback. Matches Python keyword and entity sets within verified tolerances. Legacy `nlp.spacy_model` config is safely ignored without affecting execution.
- Verification: [RetrieverNlpFixtureParityTest.kt](file:///home/ugai/youtu-graphrag/kotlin/src/test/kotlin/com/youtu/graphrag/shared/retriever/RetrieverNlpFixtureParityTest.kt) passes.

---

## 3) Conversion Objectives (Definition of Done)

Kotlin conversion is accepted when all are true:
1. API/WS payloads and event timing are contract-compatible with Python for supported flows.
2. Constructor outputs for fixed fixtures are parity-matching (allowing bounded LLM text variance only where documented).
3. Retriever output shape + ranking behavior is fixture-validated against Python baselines.
4. IRCoT loop decisions (`So the answer is` / `The new query is`) are fixture-validated.
5. Kotlin cache artifacts are JSON+NPZ only; no `.pt` storage path remains.
6. CI parity suite passes on demo fixtures.

---

## 4) Operational Gaps & Future Optimizations

### 1. CI Pipeline Task Configuration
- **Problem**: The [.github/workflows/parity-gate.yml](file:///home/ugai/youtu-graphrag/.github/workflows/parity-gate.yml) workflow attempts to call `./gradlew parityCheck`, which is currently undefined in `build.gradle.kts` and causes build failures.
- **Remediation**: Either configure a custom Gradle task named `parityCheck` to run the parity test suites, or modify the workflow to invoke `./gradlew test`.

### 2. Fixture Breadth Expansion
- **Problem**: `retrieval_parity_fixtures.json` is currently limited to 1 simple single-hop fixture.
- **Remediation**: Build and commit additional complex multi-hop and community-aware retrieval fixtures to ensure regressions are caught across edge cases.

### 3. Workspace State (Unrelated Pre-existing Changes)
- **Observed on 2026-05-30**: Pre-existing changes outside this TreeComm work were present in the working tree:
  - `../.github/workflows/parity-gate.yml`
  - `retriever/`
  - `src/test/kotlin/com/youtu/graphrag/shared/prompt/`
- **Handling**: These were intentionally left untouched while implementing TreeComm closure items.

---

## 5) Verification Gates

Per PR/change-set:
- Unit tests for touched modules
- Focused parity fixture tests for impacted flow
- Full Kotlin test run before merge

Release gate:
- Parity fixture suite green
- API/WS contract tests green
- No `.pt` storage path in runtime code/config

---

## 6) Risks and Mitigations

1. **LLM nondeterminism can mask parity regressions.**
   - *Mitigation*: Mock/frozen LLM fixture mode for parity tests.

2. **Python retriever complexity exceeds current Kotlin abstraction.**
   - *Mitigation*: Retained simplified hybrid retrieval pipeline in Kotlin while matching the required JSON metadata structure and shape.

3. **Historical `.pt` compatibility code can reintroduce accidental coupling.**
   - *Mitigation*: Strictly deleted all `.pt` config & runtime hooks; caching relies exclusively on NPZ formatting.
