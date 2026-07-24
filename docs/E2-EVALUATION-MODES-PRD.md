# E2 — Evaluation Modes: PRD

Status: **design note, implementing in the same turn** (no genuine fork — see section 3
for the one real decision, resolved without needing to stop and ask). Written per this
project's "PRD-first for a real design decision" discipline. Reframed by explicit user
direction from the original `docs/ROADMAP.md` E2 row ("demonstrate both official
evaluators") to the actual value this layer offers that Spring AI's own `Evaluator`
mechanism does not: **the same evaluator can run in two modes — deterministic replay for
CI, or live for drift/quality checks — and this project's existing `VcrMode` system is
already the mechanism for both, with no new code required.**

## 1. Toxicity — checked against real jars, not assumed

**Confirmed: no toxicity evaluator exists anywhere in Spring AI 2.0.0.** Every jar this
project already depends on (`spring-ai-client-chat`, `spring-ai-commons`,
`spring-ai-model`) was searched for `evaluat`/`toxic`/`hallucin` class names. The only
hits, in `org.springframework.ai.chat.evaluation`
(`spring-ai-client-chat`): `RelevancyEvaluator`, `FactCheckingEvaluator`, their
`Builder`s, and `package-info`. Nothing else — no `ToxicityEvaluator`, no generic
content-safety check.

**What this means concretely:**

- **Hallucination check** — already covered. `FactCheckingEvaluator` *is* a
  hallucination/faithfulness check (E1's own framing already established this): "is
  this claim supported by this document" is exactly what a hallucination check asks.
  Nothing new needed here beyond what E1 already proved.
- **Relevance/quality check** — already covered by `RelevancyEvaluator`.
- **Toxicity check** — **does not exist in Spring AI, and is not built here.** It would
  need a bespoke `Evaluator` implementation, built the same way `FactCheckingEvaluator`
  itself is: a `ChatClient.Builder`, a judge prompt template, a yes/no (or
  pass/fail-with-feedback) parse of the response. Confirmed as a real, buildable pattern
  — not a hypothetical — precisely because `FactCheckingEvaluator`'s own source shape
  (confirmed via `javap` for E1) *is* that pattern already. **Not building it now**: no
  demonstrated need for it in this project or its example, and building a speculative
  judge prompt ahead of a real use case is exactly the kind of premature infrastructure
  this project's own roadmap already argues against elsewhere (see `docs/ROADMAP.md`
  section 6, the multimodal/dashboard rejections, for the same reasoning applied to
  other speculative features). If a real toxicity-checking need arises later, the
  pattern to follow is `FactCheckingEvaluator`'s own shape, and R4/A2/E1's Recorder-
  backing applies to it identically, with zero new mechanism, exactly as it already does
  for the two evaluators Spring AI ships.

## 2. The actual differentiator: one evaluator, two modes

Spring AI's `Evaluator` has no concept of "replay" at all — every `evaluate()` call is
just a `ChatClient` call, live, every time, by design (Spring AI has no VCR-equivalent of
its own). This project's contribution is not a new evaluator — it's making the *existing*
ones runnable in either of two modes, on demand, using the mode system Recorder already
has:

| Mode | `VcrMode` | Purpose | Where it belongs |
|---|---|---|---|
| **Deterministic replay** | `REPLAY_ONLY` | The judge's verdict for a known input is read from a committed fixture — no network, no token spend, no flakiness, no drift between runs | Every CI run, every push/PR |
| **Live drift/quality check** | `BYPASS` (ignores any fixture, never reads or writes one) or `RECORD_ALWAYS` (reaches the model and overwrites the fixture, so a diff shows what changed) | "Is the model's judgment on this exact case still what it was when this fixture was committed?" — catches provider-side model drift, a changed judge prompt's real effect, or (for `RECORD_ALWAYS`) deliberately refreshes a stale fixture | A separate, deliberate run: nightly/scheduled CI, `workflow_dispatch`, or a developer running it locally before a release — never the default CI path |

**This needs zero new code.** `RelevancyEvaluator`/`FactCheckingEvaluator` are built from
whatever `ChatClient.Builder` is handed to them — the same builder this project's
`ChatClientBuilderCustomizer` already attaches `DeterministicVcrAdvisor` to. Which mode
that advisor is running in is exactly `spring.ai.test.vcr.mode` (or a per-test
`@Vcr(mode = ...)` override, already built for E1's own `VcrAnnotationEscapeHatchTest`
precedent) — nothing about the evaluator's own construction changes between the two
modes; only the mode the *same* advisor was built with does. Proven, not just argued:
`OllamaEvaluatorEndToEndTests` gains a test building a `RelevancyEvaluator` from a
`BYPASS`-mode advisor pointed at a cache directory that already has a matching fixture,
and confirms every `evaluate()` call still reaches the network — the fixture is never
even consulted, exactly as `DeterministicVcrAdvisor`'s own `BYPASS` branch (returns
before ever computing a hash or touching the store) already guarantees by its own source.

**Honest warning, stated as plainly as the rest of this document's caveats:** the live
path reintroduces every problem Recorder exists to eliminate — network flakiness, token
cost, and the judge model's own non-determinism (the same judge prompt can produce a
different verdict on different runs, already observed directly while recording
`EvaluatorRecordReplayTest`'s fixture in the example project: "No.", "NO.", and once
"YES" for the identical input). **Never run the live path in default CI.** It belongs in
a deliberately separate, opt-in job — the same discipline `docs/VISION.md` already states
for Evaluator generally, made concrete here for the specific "should I re-verify this
judge's opinion" question.

## 3. Drift-detection demonstration — the one real decision

Two ways to demonstrate this, weighed against "don't fake a green thing," "use the
existing Testcontainers/Ollama pattern," and "no new model":

- **Main library:** already has a real, working nightly/`workflow_dispatch` job
  (`.github/workflows/ci.yml`'s `e2e` job) — cron-scheduled, real Testcontainers-managed
  Ollama, currently running only `OllamaEndToEndTests`. **Decision: extend this
  existing job to also run `OllamaEvaluatorEndToEndTests`.** Zero new CI infrastructure,
  uses the exact established pattern, genuinely re-verifies the evaluator mechanism
  (including the new BYPASS-always-live test above) against a freshly pulled Ollama
  container on a real schedule.
- **Example project:** has **no** scheduled/`workflow_dispatch` job at all today, and its
  own stated identity is "needs no Docker, no Ollama, and no network" for its default
  test run — its one existing integration-tagged test
  (`VcrAnnotationEscapeHatchTest`) is not run in CI at all, only locally via
  `mvn test -Pintegration`. **Decision: do not add new scheduled CI infrastructure to
  this repo.** Adding a real Docker+Ollama nightly job to a small public demo project is
  a materially bigger, more consequential commitment (recurring CI cost and maintenance)
  than anything else built for this repo so far, and would contradict its own documented
  design principle. Instead: a new **integration-tagged** test,
  `EvaluatorLiveDriftCheckTest`, demonstrates the exact same two-mode story using this
  project's own `@Vcr(mode = VcrMode.BYPASS)` escape hatch — already-existing library
  API, no new mechanism — pointed at the *same* cache directory and *same*
  `EvaluationRequest` as the already-committed `EvaluatorRecordReplayTest` fixture, so
  the contrast is as sharp as possible: same evaluator, same fixture sitting right
  there, but `BYPASS` reaches the model anyway. Run manually
  (`mvn test -Pintegration`), never in this repo's CI, exactly like its sibling
  `VcrAnnotationEscapeHatchTest` already is. This is the honest, sustainable choice: a
  real, runnable demonstration without inventing new CI cost this project doesn't
  currently carry anywhere else.

This was resolved directly, not left as an open fork needing a stop-and-ask: both
options were genuinely available, but only one is consistent with each repo's own
already-established design, so there was a clear answer once the two repos' existing
conventions were actually checked (not assumed).

## 4. What ships

- `OllamaEvaluatorEndToEndTests` (main library): one new test proving `BYPASS` always
  reaches the model regardless of a matching fixture's presence.
- `.github/workflows/ci.yml` (main library): nightly/`workflow_dispatch` `e2e` job now
  also runs `OllamaEvaluatorEndToEndTests`.
- `EvaluatorLiveDriftCheckTest` (example project): integration-tagged, demonstrates the
  live path via `@Vcr(mode = VcrMode.BYPASS)` against the same fixture
  `EvaluatorRecordReplayTest` already commits.
- README/`docs/VISION.md`/`docs/ROADMAP.md` (main library) and the example project's
  README: the headline differentiator stated plainly — "run Spring AI's own evaluators
  in two modes: deterministic replay for CI, or live for drift/quality checks — Spring
  AI gives you the evaluators, this project gives you the choice."
- No new production code beyond what E1/R4 already shipped — this entire layer is a
  documented, demonstrated, and now doubly-tested *usage pattern* of the existing
  `VcrMode` system, not a new mechanism.
