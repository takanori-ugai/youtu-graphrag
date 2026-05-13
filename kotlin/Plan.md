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
- Status: extraction-parity flow is implemented in Kotlin (LLM payload parsing for `attributes`/`triples`/`entity_types`, agent-mode schema evolution, and token chunking via `jtokkit`).
- Gap:
  - Cross-runtime golden fixtures (Python vs Kotlin constructor outputs on shared corpora) are not fully locked yet.
  - Remaining drift risk is mainly in prompt/output nondeterminism boundaries rather than missing constructor capability.

### C. Retriever
- Python source: `../models/retriever/enhanced_kt_retriever.py`, `../models/retriever/faiss_filter.py`
- Kotlin source: `src/main/kotlin/com/youtu/graphrag/shared/retriever/KTRetriever.kt`
- Status: Kotlin has lexical + vector hybrid retrieval, type filters, recall expansion, chunk merging, ANN via Lucene fallback, and retriever-time NLP abstraction (OpenNLP provider with deterministic regex fallback).
- Gap:
  - Python retriever still has richer strategy internals; Kotlin parity is partial.
  - Full scoring/path behavior parity still needs expanded Python-vs-Kotlin fixture coverage.

### D. Decomposer + IRCoT
- Python source: `../models/retriever/agentic_decomposer.py`, `../main.py`, `../backend.py`
- Kotlin source: `src/main/kotlin/com/youtu/graphrag/shared/decomposer/GraphQ.kt`, `src/main/kotlin/com/youtu/graphrag/server/api/QuestionAnsweringService.kt`
- Status: decomposition + iterative IRCoT loop parity hardening is in place (marker parsing, repeated-query termination normalization, edge-case tests).
- Gap:
  - Prompt routing/templates are not yet parity-locked (Python uses mixed key names and inline IRCoT prompt variants; Kotlin uses JSON `prompts.retrieval.ircot` plus alias routing).
  - End-to-end fixture lock for backend/main variant selection still pending.

### E. TreeComm
- Python source: `utils/tree_comm` usage through constructor path
- Kotlin source: `src/main/kotlin/com/youtu/graphrag/shared/treecomm/FastTreeComm.kt`
- Status: Kotlin implements deterministic connected-component community detection with community metadata and keyword extraction outputs.
- Gap: full algorithm-level parity with Python TreeComm internals is still pending beyond current schema-compatible outputs.

### F. CLI
- Python source: `../main.py`
- Kotlin source: `src/main/kotlin/com/youtu/graphrag/cli/Main.kt`
- Status: CLI deep parity is implemented (evaluation flow, report artifacts, option-matrix behavior).
- Gap: residual risk is primarily downstream parity drift from retriever/prompt fixture gaps, not CLI command coverage itself.

### G. Config format
- Python source: `../main.py`, `../backend.py` config loading path
- Kotlin source: `src/main/kotlin/com/youtu/graphrag/shared/config/ConfigManager.kt`
- Status: Kotlin now reads JSON config directly (default `config/base_config.json`) and YAML support has been removed.
- Gap:
  - External docs/examples still need consistency checks to ensure no stale YAML references remain.

### H. Validation
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
- Remove `embeddings.export_pt_cache` from config model and runtime config.
- Keep NPZ (`NpzEmbeddingCache`) as canonical binary cache format.
- Replace tests asserting `.pt` behavior with NPZ-only coverage.

Exit criteria:
- `rg "\\.pt|export_pt_cache|TorchCacheInterop" src/main/kotlin config/base_config.json` returns no runtime-path hits.
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

Progress update (2026-05-13):
- Added deterministic retrieval fixture harness for parity-locked fields:
  - `triples`
  - `chunk_ids`
  - `chunk_contents`
  - `chunk_retrieval_results` (prefix-locked format)
  - `retrieval_strategy`
- Added initial fixture snapshot covering relation-type filtering behavior as baseline for further Python fixture expansion.

Exit criteria:
- Fixture tests fail on unexpected drift and pass on known-tolerated variance.

## WS-2: IRCoT parity hardening (High Priority)
Status: Completed (2026-05-13)

Tasks:
- Align loop stop/continue semantics with Python for:
  - final-answer marker extraction
  - new-query marker extraction
  - missing-marker fallback
  - repeated-query termination
- Align stage update payload sequencing with backend behavior.
- Add edge-case tests for malformed and multiline reasoning outputs.

Progress update (2026-05-13):
- Hardened IRCoT marker parsing in `QuestionAnsweringService`:
  - `So the answer is:` extraction now trims multiline answers and ignores trailing `The new query is:` segments.
  - new-query extraction keeps multiline compatibility and robust trimming.
  - repeated-query termination now normalizes case/whitespace/punctuation to prevent loop churn.
- Added IRCoT parity tests for:
  - direct-answer marker flow,
  - iterative-query continuation across multiple IRCoT steps,
  - repeated-query termination after normalization,
  - multiline malformed marker content handling.
- Re-validated WS event progress mapping parity via `ApplicationWsParityTest`.

Exit criteria:
- IRCoT fixture tests pass for at least: direct-answer, iterative-query, and no-marker fallback scenarios.

## WS-3: Constructor extraction parity (Medium Priority)
Status: Completed (2026-05-13)

Tasks:
- Replace heuristic constructor behavior with schema-aware extraction parity:
  - attributes
  - triples
  - entity types
- Add agent-mode schema evolution behavior equivalent to Python config path.
- Ensure chunking semantics and output formatting match fixture expectations.

Progress update (2026-05-13):
- Replaced heuristic constructor flow with prompt-driven extraction parsing:
  - `attributes`
  - `triples`
  - `entity_types`
  - optional `new_schema_types`
- Added constructor prompt alias handling for Python-compatible key variants across dataset/mode combinations.
- Added agent-mode schema evolution persistence for node/relation/attribute schema expansion.
- Switched constructor chunking to `jtokkit` token-based overlap chunking with deterministic fallback.
- Added constructor-focused test coverage (`KTBuilderTest`) for:
  - extraction payload mapping to graph edges,
  - prompt alias behavior,
  - agent schema evolution updates,
  - chunk output formatting parity safeguards.

Exit criteria:
- Constructor fixture output compatibility passes for demo corpus snapshots.

## WS-9: Retriever NLP parity (spaCy replacement) (High Priority)
Status: In progress

Background:
- Python retriever uses spaCy (`en_core_web_lg`) for:
  - query enhancement (NER + POS)
  - keyword extraction (NER + POS + stopword filtering)
- Kotlin retriever now uses `QueryNlp` with OpenNLP provider support and deterministic regex fallback.
- Remaining gap is fixture-level parity lock versus Python outputs (enhanced query and keyword/entity sets).

Remaining tasks:
- Expand parity fixtures comparing Python vs Kotlin outputs for:
  - query enhancement
  - extracted keyword sets
  - representative direct/multi-hop questions across target datasets.
- Calibrate normalization/tolerance rules for entity and key-term formatting drift.
- Decide and document legacy-key policy for `nlp.spacy_model` in JSON config (ignore vs remove) without affecting runtime behavior.
- Add regression gates ensuring OpenNLP and regex-fallback paths remain deterministic.

Progress update (2026-05-13):
- Added `QueryNlp` abstraction with pluggable providers and deterministic output shape (`entities`, `keyTerms`, `keywords`).
- Implemented OpenNLP provider wiring (`nlp.provider=opennlp`) with model-path config keys and automatic fallback to regex provider when models are unavailable.
- Implemented regex fallback provider with stopword filtering + entity/key-term extraction used for both retrieval keyword scoring and optional query enhancement formatting.
- Wired retriever runtime to:
  - use extracted keywords for lexical scoring,
  - use enhanced query text for vector search when `retrieval.enable_query_enhancement=true`,
  - expose `enhanced_question`/`query_keywords`/`query_entities` in retrieval metadata.
- Added tests for:
  - query enhancement formatting parity (`Entities: ...`, `Key terms: ...`),
  - keyword extraction behavior with stopword filtering,
  - enhancement toggle behavior,
  - OpenNLP->regex fallback behavior.

Exit criteria:
- Kotlin retriever NLP output (enhanced query + keywords) matches Python fixture baselines within documented tolerances.
- Retriever runtime no longer depends on spaCy-specific config semantics.
- Fallback behavior is deterministic and covered by tests.

## WS-8: Prompt Routing/Template Parity (High Priority)
Status: In progress

Prompt audit findings (2026-05-13):
- Python decomposition/retrieval key variants (`anony_chs`/`novel`, `novel_chs`/`novel`) are now alias-routed in Kotlin, but full snapshot lock is still pending.
- Python IRCoT prompt text is inline in `main.py` and `backend.py` (two variants); Kotlin currently resolves from JSON `prompts.retrieval.ircot`.
- Constructor prompt-driven extraction is implemented in Kotlin; cross-runtime rendered prompt snapshots are not fully locked yet.

Tasks:
- Build a prompt parity matrix (Python vs Kotlin) for:
  - `construction`, `decomposition`, `retrieval`, `retrieval.ircot`
  - datasets: `demo`, `anony_chs`, `anony_eng` (and `novel` aliases where present)
  - modes: `noagent`, `agent`
- Expand snapshot tests for rendered prompts (variable substitution included) in CLI/API paths.
- Decide and document IRCoT source-of-truth policy:
  - either lock to JSON template parity and move Python inline variants behind same config keys, or
  - emulate backend/main inline variant selection in Kotlin for strict fixture parity.
- Validate constructor prompt rendering/output snapshots against Python baselines.

Progress update (2026-05-13):
- Added decomposition prompt alias compatibility for Python key variants (`decomposition.anony_chs` <-> `decomposition.novel`) with deterministic candidate resolution.
- Added retrieval prompt alias compatibility for Chinese novel key variants (`retrieval.novel_chs` <-> `retrieval.novel`) with deterministic candidate resolution.
- Added tests covering alias routing and rendered prompt substitution paths for decomposition/retrieval prompt generation.

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

Progress update (2026-05-13):
- Added explicit API contract serialization tests (`ApiContractsTest`) validating snake_case payload compatibility and WS envelope fields.
- Re-validated QA-stage progress mapping parity via `ApplicationWsParityTest`.
- Ran API/service contract-focused suites:
  - `DatasetFileServiceTest`
  - `GraphConstructionServiceTest`
  - `QuestionAnsweringServiceTest`
  - `ApplicationWsParityTest`
  - `ApiContractsTest`

Exit criteria:
- Contract tests cover all public routes and core WS events.

## WS-5: TreeComm parity (High Priority)
Status: In progress

Tasks:
- Implement actual community detection flow (replace stub).
- Match Python schema expectations for community/keyword artifacts.

Progress update (2026-05-13):
- Replaced TreeComm stub with deterministic connected-component community detection.
- Added community metadata output fields:
  - `community_id`
  - `node_count`
  - `edge_count`
  - `nodes`
  - `keywords` (frequency-ranked token extraction)
- Added/updated `FastTreeCommTest` coverage for:
  - deterministic community metadata on connected graphs,
  - disconnected component separation,
  - keyword extraction behavior.

Exit criteria:
- TreeComm outputs satisfy schema and fixture-level parity checks.

## WS-6: CLI deep parity (High Priority)
Status: Completed (2026-05-13)

Tasks:
- Align QA evaluation/reporting behavior with Python CLI.
- Validate command-matrix compatibility (`--config`, `--datasets`, `--override`).

Progress update (2026-05-13):
- Added Python-style CLI evaluator prompt flow (`"1"`/`"0"` via LLM) with heuristic fallback.
- Extended CLI artifacts with per-question timing/eval method and dataset-level QA summary JSON.
- Added CLI option-matrix tests for valid and invalid `--override` handling with multi-dataset input.

Exit criteria:
- Kotlin CLI reproduces Python workflow outputs for fixture runs.

## WS-7: Config Format Consolidation (JSON-only) (Medium Priority)
Status: Completed (2026-05-13)

Tasks:
- Make `config/base_config.json` the canonical and default runtime config.
- Enforce JSON-only config loading/saving in `ConfigManager`.
- Remove YAML config support and YAML-specific conversion/runtime paths.
- Update CLI/API defaults and tests to use JSON config paths.

Progress update (2026-05-13):
- Switched runtime default config path to `config/base_config.json`.
- Enforced JSON-only load/save path checks in `ConfigManager`.
- Removed YAML config artifact from runtime path (`config/base_config.yaml` deleted).
- Removed YAML parser dependency and YAML conversion utility from Kotlin runtime code.
- Updated config-focused tests and command paths to JSON-first behavior.

Exit criteria:
- Kotlin runtime accepts JSON config files only.
- No YAML config loading/conversion path remains in Kotlin runtime.
- Default app startup path resolves `config/base_config.json`.

---

## 5) Immediate Execution Order

1. Finish WS-5 TreeComm parity against Python artifact expectations.
2. Execute WS-8 prompt routing/template parity lock (including IRCoT variant source-of-truth).
3. Lock WS-1 retrieval fixtures against Python baselines (shape + ranking + strategy metadata).
4. Complete WS-9 retriever NLP parity fixture pass (OpenNLP/regex outputs vs Python spaCy-era baselines with documented tolerances).
5. Finalize WS-4 API/WS contract locks with strict endpoint + WS event-sequence coverage.
6. Run and gate the full parity suite (constructor/retriever/IRCoT/API) before closing remaining workstreams.

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
