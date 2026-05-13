# Youtu-GraphRAG Kotlin Conversion Plan (Recreated)

Updated: 2026-05-13

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
- Legacy `.pt` handling is treated as migration debt and should be removed from core codepaths.

### Priority directive
CLI migration parity is prioritized on the critical path.

---

## 2) Python vs Kotlin Snapshot (Current)

### A. API/WebSocket layer
- Python source: `../backend.py`
- Kotlin source: `src/main/kotlin/com/youtu/graphrag/server/api/Application.kt`
- Status: routes are present and mostly aligned (`/api/upload`, `/api/construct-graph`, `/api/ask-question`, datasets/schema/reconstruct/graph, WS endpoint).
- Gap:
  - `ask-question` payload/event detail parity is still partial (field-level and sequencing nuances).
  - Contract lock is missing (no strict Python-vs-Kotlin fixture gate).

### B. Constructor
- Python source: `../models/constructor/kt_gen.py`
- Kotlin source: `src/main/kotlin/com/youtu/graphrag/shared/constructor/KTBuilder.kt`
- Status: Kotlin builds graph/chunks and writes expected files.
- Gap:
  - Python uses LLM extraction pipeline with schema-aware attributes/triples/entity types and optional schema evolution.
  - Kotlin constructor is still heuristic/simplified (single-pass synthetic relations/keywords), not extraction-parity.

### C. Retriever
- Python source: `../models/retriever/enhanced_kt_retriever.py`, `../models/retriever/faiss_filter.py`
- Kotlin source: `src/main/kotlin/com/youtu/graphrag/shared/retriever/KTRetriever.kt`
- Status: Kotlin has lexical + vector hybrid retrieval, type filters, recall expansion, chunk merging, ANN via Lucene fallback.
- Gap:
  - Python retriever has substantially richer behavior (dual-path strategy orchestration, query enhancement, extensive caching/index features, more reranking/strategy internals).
  - Output shape was improved recently, but full scoring/path behavior parity still not locked by fixtures.

### D. Decomposer + IRCoT
- Python source: `../models/retriever/agentic_decomposer.py`, `../main.py`, `../backend.py`
- Kotlin source: `src/main/kotlin/com/youtu/graphrag/shared/decomposer/GraphQ.kt`, `src/main/kotlin/com/youtu/graphrag/server/api/QuestionAnsweringService.kt`
- Status: decomposition + iterative loop exists; recent control-flow parity improvements merged.
- Gap:
  - Still needs strict parity for loop semantics, retries, and edge-case stop conditions against Python behavior.
  - Prompt routing/templates are not yet parity-locked (Python uses mixed key names and inline IRCoT prompt variants; Kotlin uses YAML `retrieval.ircot` and different dataset key mapping).

### E. TreeComm
- Python source: `utils/tree_comm` usage through constructor path
- Kotlin source: `src/main/kotlin/com/youtu/graphrag/shared/treecomm/FastTreeComm.kt`
- Status: Kotlin implementation is currently a stub.
- Gap: full algorithm parity not started.

### F. CLI
- Python source: `../main.py`
- Kotlin source: `src/main/kotlin/com/youtu/graphrag/cli/Main.kt`
- Status: constructor/retrieval workflows exist.
- Gap: deeper evaluator and behavior parity with Python matrix remains incomplete.

### G. Validation
- Kotlin has unit/integration tests for major modules.
- Gap: no hard Python-vs-Kotlin golden fixture suite enforcing parity across constructor/retrieval/IRCoT outputs.

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

## 4) Workstreams

## WS-0: Remove `.pt` storage paths (High Priority)
Status: Completed (2026-05-13)

Tasks:
- Remove `.pt` cache filenames and conversion hooks from Kotlin retriever persistence flow.
- Remove `TorchCacheInterop` runtime usage from main retrieval path.
- Remove `embeddings.export_pt_cache` from config model and YAML.
- Keep NPZ (`NpzEmbeddingCache`) as canonical binary cache format.
- Replace tests asserting `.pt` behavior with NPZ-only coverage.

Exit criteria:
- `rg "\\.pt|export_pt_cache|TorchCacheInterop" src/main/kotlin config/base_config.yaml` returns no runtime-path hits.
- Retriever tests pass with NPZ-only caching.

## WS-1: Retrieval parity against Python fixtures (High Priority)
Status: In progress

Tasks:
- Build deterministic fixture harness comparing Python and Kotlin retrieval outputs on same graph/chunk inputs.
- Lock parity fields at minimum:
  - `triples`
  - `chunk_ids`
  - `chunk_contents`
  - `chunk_retrieval_results`
  - strategy metadata
- Document tolerated differences (if any) with explicit rationale.

Exit criteria:
- Fixture tests fail on unexpected drift and pass on known-tolerated variance.

## WS-2: IRCoT parity hardening (High Priority)
Status: In progress

Tasks:
- Align loop stop/continue semantics with Python for:
  - final-answer marker extraction
  - new-query marker extraction
  - missing-marker fallback
  - repeated-query termination
- Align stage update payload sequencing with backend behavior.
- Add edge-case tests for malformed and multiline reasoning outputs.

Exit criteria:
- IRCoT fixture tests pass for at least: direct-answer, iterative-query, and no-marker fallback scenarios.

## WS-3: Constructor extraction parity (Medium Priority)
Status: In progress

Tasks:
- Replace heuristic constructor behavior with schema-aware extraction parity:
  - attributes
  - triples
  - entity types
- Add agent-mode schema evolution behavior equivalent to Python config path.
- Ensure chunking semantics and output formatting match fixture expectations.

Exit criteria:
- Constructor fixture output compatibility passes for demo corpus snapshots.

## WS-8: Prompt Routing/Template Parity (High Priority)
Status: In progress

Prompt audit findings (2026-05-13):
- Python decomposition prompt lookup uses `decomposition.anony_chs`; Kotlin uses `decomposition.novel` for `anony_chs`.
- Python retrieval prompt lookup uses `retrieval.novel_chs` for dataset `novel`; Kotlin uses `retrieval.novel` and routes `anony_chs`/`novel` to that key.
- Python IRCoT prompt text is inline in `main.py` and `backend.py` (two variants); Kotlin uses YAML `prompts.retrieval.ircot`.
- Python constructor is prompt-driven (`construction.*`); Kotlin constructor currently bypasses prompt templates.

Tasks:
- Build a prompt parity matrix (Python vs Kotlin) for:
  - `construction`, `decomposition`, `retrieval`, `retrieval.ircot`
  - datasets: `demo`, `anony_chs`, `anony_eng` (and `novel` aliases where present)
  - modes: `noagent`, `agent`
- Add explicit prompt-key alias compatibility in Kotlin for Python key variants (`anony_chs` / `novel`, `novel_chs` / `novel`) while keeping canonical keys documented.
- Add snapshot tests for rendered prompts (variable substitution included) in CLI/API paths.
- Decide and document IRCoT source-of-truth policy:
  - either lock to YAML template parity and move Python inline variants behind same config keys, or
  - emulate backend/main inline variant selection in Kotlin for strict fixture parity.
- Fold constructor prompt parity completion into WS-3 implementation and validate against Python prompt snapshots.

Exit criteria:
- Rendered prompt snapshots match Python baselines for target datasets/modes.
- No unresolved prompt-key mismatches between Python and Kotlin runtime paths.
- IRCoT prompt variant selection is deterministic and fixture-tested.

## WS-4: API/WS contract lock (Medium Priority)
Status: In progress

Tasks:
- Add contract tests for endpoint payload shapes and WS event series.
- Verify progress cadence + event payloads for upload/construct/reconstruct/ask-question.
- Validate frontend compatibility with unmodified Python frontend assets.

Exit criteria:
- Contract tests cover all public routes and core WS events.

## WS-5: TreeComm parity (Medium/Low Priority)
Status: Not started

Tasks:
- Implement actual community detection flow (replace stub).
- Match Python schema expectations for community/keyword artifacts.

Exit criteria:
- TreeComm outputs satisfy schema and fixture-level parity checks.

## WS-6: CLI deep parity (High Priority)
Status: In progress

Tasks:
- Align QA evaluation/reporting behavior with Python CLI.
- Validate command-matrix compatibility (`--config`, `--datasets`, `--override`).

Progress update (2026-05-13):
- Added Python-style CLI evaluator prompt flow (`"1"`/`"0"` via LLM) with heuristic fallback.
- Extended CLI artifacts with per-question timing/eval method and dataset-level QA summary JSON.
- Added CLI option-matrix tests for valid and invalid `--override` handling with multi-dataset input.

Exit criteria:
- Kotlin CLI reproduces Python workflow outputs for fixture runs.

## WS-7: Config Format Migration (YAML -> JSON) (Medium Priority)
Status: Not started

Tasks:
- Define canonical JSON config schema equivalent to `config/base_config.yaml` semantics.
- Add a deterministic one-time converter from YAML config to JSON (stable key ordering).
- Update `ConfigManager` defaults and docs/examples to prefer JSON config path.
- Keep temporary backward compatibility for YAML loading during migration window.
- Add tests validating that YAML and JSON configs produce identical runtime `AppConfig`.

Exit criteria:
- JSON config is the default runtime format for Kotlin.
- YAML compatibility is either explicitly deprecated or removed per release policy.
- CI includes config-parity tests across YAML and JSON fixtures.

---

## 5) Immediate Execution Order

1. Complete WS-6 (CLI deep parity) first.
2. Execute WS-8 prompt routing/template parity lock.
3. Start WS-7 config migration scaffolding (YAML->JSON converter + parity tests).
4. Lock WS-1 retrieval fixtures (NPZ-only cache assumptions).
5. Finish WS-2 IRCoT edge-case parity tests.
6. Move to WS-3 constructor extraction parity.
7. Finalize WS-4 contract locks; then WS-5.

---

## 6) Verification Gates

Per PR/change-set:
- Unit tests for touched modules
- Focused parity fixture tests for impacted flow
- Full Kotlin test run before merge

Release gate:
- Parity fixture suite green
- API/WS contract tests green
- No `.pt` storage path in runtime code/config

---

## 7) Risks and Mitigations

1. LLM nondeterminism can mask parity regressions.
- Mitigation: mock/frozen LLM fixture mode for parity tests.

2. Python retriever complexity exceeds current Kotlin abstraction.
- Mitigation: introduce fixture-driven incremental parity slices instead of full rewrite in one pass.

3. Historical `.pt` compatibility code can reintroduce accidental coupling.
- Mitigation: delete `.pt` config + runtime hooks; keep one-way migration docs only if needed.

---

## 8) Tracking Notes

- Current Kotlin repository already contains meaningful progress in API, retrieval, and IRCoT scaffolding.
- This recreated plan supersedes older progress notes where they conflict with the `.pt` storage decision.
