# Roadmap

Last updated: 2026-07-24

## Renamed: `spring-ai-test-vcr` → `spring-ai-test-tools`

The artifactId, and the Java package root, are now `spring-ai-test-tools` /
`io.github.rifatcakir.springai.testtools` — and the GitHub repo itself was renamed to
match (`rifatcakir/spring-ai-test-vcr` → `rifatcakir/spring-ai-test-tools`). Everything
in this file below the rename is now specifically the roadmap for the **Recorder**
package (`...testtools.recorder`) — the record/replay VCR mechanism this project started
as. See `docs/VISION.md` for the three-layer architecture this sits inside
(Recorder / Assertions / Evaluator) and why Assertions and Evaluator, once built, will
depend on the Recorder to stay CI-safe rather than being independent siblings of it.

## The layered roadmap: Recorder → Assertions → Evaluator

`docs/VISION.md` names the three layers (Recorder / Assertions / Evaluator) and the one
structural rule that has to hold across all of them: **an Evaluator's own judge/embedding
calls must be captured by the Recorder, or "deterministic evaluation" is a contradiction
in terms.** This section turns that into a sized, ordered, dependency-aware plan. Nothing
below is code yet — this is the plan to review before any of it starts.

### 0. Recorder today — done, in production use in this repo's own test suite

Everything below is real, tested, and proven against a live model (Ollama), not just
designed:

- **Record/replay with exact-match SHA-256 fingerprinting.** One JSON file per canonical
  request hash; a prompt that changes by one character produces a new fixture or a loud
  `VcrCacheMissException`, never a "close enough" hit.
- **Cross-platform hashing** (schema `"4"`) — a tool's input schema and an `entity()`
  call's format instructions/JSON schema are pretty-printed with the recording JVM's own
  line separator (`\r\n` on Windows, `\n` on Linux/macOS); both are normalized to `\n`
  before hashing (and in the stored fixture, kept consistent with the hash), so a fixture
  recorded on Windows replays identically on Linux/macOS CI. Found for real, not
  hypothesized: two example-project fixtures recorded on Windows failed on a real Linux
  GitHub Actions runner until this landed. Message/response text is deliberately excluded
  from this normalization — see `VcrCacheKeyGenerator#normalizeLineEndings`'s Javadoc.
- **Four modes** (`RECORD_OR_REPLAY`, `REPLAY_ONLY`, `RECORD_ALWAYS`, `BYPASS`) plus a
  per-test `@Vcr` escape hatch out of a sealed `REPLAY_ONLY` CI run.
- **`VcrPromptNormalizer`** — pre-hash normalization for volatile-but-harmless values
  (dates, UUIDs) so they don't fragment the cache.
- **`VcrFixtureRedactor`** — post-hash, write-path-only redaction of committed fixture
  content (secrets/PII), structurally unable to affect the cache key.
- **Tool/function calling, verified end to end** — `VcrScope.INSIDE_TOOL_LOOP` records one
  fixture per model turn with real `@Tool` methods still executing on replay;
  `OUTSIDE_TOOL_LOOP` records one fixture for the whole round trip. Proven against a real
  Ollama tool-calling conversation, not just unit-tested against mocks.
- **Spring Boot auto-configuration / starter experience** — `spring.ai.test.vcr.*`
  properties, zero required `@Bean`s, `ChatClientBuilderCustomizer`-based attachment so
  application code never has a test-only branch.
- **Sealed CI** — a real GitHub Actions workflow, `REPLAY_ONLY` verified to throw on a miss
  without touching the network, a fixture-drift gate on the whole working tree.

Full detail and the "Bugs found on first compile" history: `docs/STATUS.md`. Full
feature-by-feature history for the items above: the "Feature roadmap" tables later in this
file (kept as the historical record; this section is the forward-looking one).

### 1. Recorder gaps — layer-completion work, before Assertions gets real weight

The tool-calling work taught a concrete lesson worth stating plainly here: **"the design
should support this" is not the same claim as "this works,"** and the gap between those
two turned out to be a real, shipped bug (`Message.getText()` being empty for tool-call
messages, silently colliding different tool calls onto one fixture) that only surfaced
once an actual Ollama test was written. The same discipline applies to every item below —
each one starts with a diagnosis against a real model, the same way tool-calling did, not
with an assumption that the design already covers it.

| # | Gap | Why it matters | Size | Notes |
|---|---|---|---|---|
| R1 | ~~**Structured output round-trip**~~ **Done** (`.entity(MyDto.class)` / `BeanOutputConverter`) | Diagnosis found two separate things, not one: (1) the single-DTO round trip genuinely already worked with zero new code — POJO conversion is pure client-side text parsing, verified in `OllamaStructuredOutputEndToEndTests#structuredOutputRecordsOnceAndReplaysTheSamePojo`; (2) a real, confirmed cache-key blind spot — `entity()`'s format instructions/JSON schema are spliced into the message text by Spring AI's terminal `ChatModelCallAdvisor` (`getOrder() == Integer.MAX_VALUE`), strictly *after* `DeterministicVcrAdvisor` already computed the hash from the un-augmented `Prompt`, so two structurally different `entity()` target types sharing identical prompt text collided on one fixture — reproduced end to end against a real model before the fix, confirmed fixed after it. Fixed by `VcrTrack` schema version `"3"`: `VcrCacheKeyGenerator`/`VcrTrackMapper` gained a `(Prompt, Map)` overload that also canonicalizes `ChatClientAttributes.OUTPUT_FORMAT`/`STRUCTURED_OUTPUT_SCHEMA` from `ChatClientRequest.context()`, conditionally — silent when absent, so every fixture recorded before this existed keeps its exact hash (verified: all prior golden hash tests unchanged). New golden hash tests pin the structured-output canonicalization specifically; a fast, Docker-free unit test (`DeterministicVcrAdvisorStructuredOutputTests`) proves the same fix against a real `ChatClient`/`BeanOutputConverter` pipeline with a fake `ChatModel`. | **S**, as predicted | Diagnosed and fixed in the same session it started — see README "Structured output" |
| R2 | ~~**Multi-provider empirical validation**~~ **Done** | Diagnosed the exact question before writing any code: does the same prompt through two different `ChatModel` implementations resolve to the same fixture, and should it? Answer, reasoned through and then confirmed empirically: **yes**, and that's correct — `VcrCacheKeyGenerator` reads only `ChatOptions` interface getters (model name, temperature, etc.), never `instanceof OllamaChatOptions`/`OpenAiChatOptions`, because which Java class or wire protocol reaches a model is a transport detail that cannot change what the model computes given the same model name and parameters. `OpenAiViaOllamaEndToEndTests` proves it two ways against a real model: (1) `OpenAiChatModel` — built in Spring AI 2.0 on the official OpenAI Java SDK, an OkHttp-based stack architecturally distinct from `OllamaChatModel`'s `RestClient`-based `OllamaApi` — records and replays correctly on its own; (2) **the critical proof**: a fixture recorded through the *native* Ollama client replays byte-for-byte identical through the *OpenAI-SDK* client, at zero network cost, confirming the cache key doesn't encode provider identity. **No production code changed** — `DeterministicVcrAdvisor`/`VcrCacheKeyGenerator`/`VcrTrackMapper` needed nothing new, confirming the advisor-layer design was already this provider-agnostic. See `docs/VISION.md`'s updated caveat for the honest scope limit: this proves implementation/transport independence (two different Spring AI classes, two different HTTP stacks), not independence from an actually different model vendor — both paths in the test still talk to the same underlying `llama3.2:1b` weights via the same Ollama instance. | **S–M**, as estimated | Used Ollama's own OpenAI-compatible endpoint (`/v1/chat/completions`) rather than a real OpenAI account — no paid API key, no new model pulled. A real OpenAI/Anthropic/Bedrock run against an actually different model remains a good, separate follow-up whenever credentials/budget for that is decided |
| R3 | ~~**Streaming record/replay**~~ **Done** (`Flux<ChatResponse>`) | `DeterministicVcrAdvisor` now implements `StreamAdvisor` alongside `CallAdvisor` on one class (mirrors `ToolCallingAdvisor`'s own precedent), backed by a new, independent `VcrStreamTrack` fixture type that stores the raw chunk sequence — the actual replay source of truth, required so chunk-boundary behavior is faithfully reproduced, not hidden by a single-chunk fake — plus a computed, review-only aggregate, mirroring how `VcrTrack.canonicalRequest` is stored for humans only. `VcrCacheKeyGenerator.generateForStream(...)` reuses the same canonicalization formula with only a different header line, so a `.call()` and a `.stream()` for the identical prompt never collide on one fixture, verified by all 19 pre-existing golden hash tests passing **unchanged**. The hardest open question — whether a real provider ever fragments a tool call's `arguments` across chunks — was resolved empirically, not assumed: five real observations against `llama3.2:1b`'s native streaming endpoint showed every genuine tool call arriving whole in one chunk, so streamed tool-call support shipped in v1 with no fragment-merging logic at all | **L**, as estimated | No artificial inter-chunk delay on replay (`Flux.fromIterable`), wired into auto-configuration with no separate toggle, and proven against a real model via `OllamaStreamingEndToEndTests` (plain-text stream + a real streamed tool-calling round trip), both asserting exact chunk-for-chunk equality, not aggregate-only. See `docs/R3-STREAMING-PRD.md` for the full design note and diagnosis |
| R4 | ~~**`EmbeddingModel` interception**~~ **Done** — `io.github.rifatcakir.springai.testtools.recorder.embedding`. Confirmed via bytecode (not assumed) that `EmbeddingModel` has no advisor chain at all, so interception is a `BeanPostProcessor` wrapping the `EmbeddingModel` bean itself with `VcrEmbeddingModel`, gated by its own independent `spring.ai.test.vcr.embedding.enabled` flag. New, independent fixture type (`VcrEmbeddingTrack`, schema version `"1"`, its own counter unrelated to `VcrTrack`'s) rather than forcing embedding shapes onto the chat fixture. One real design decision needed explicit sign-off: whether to store the full vector or a hash of it in the fixture — resolved as "must be the full vector" (a hash can never replay a usable one, defeating R4's purpose), with the rendering question (one line vs. one-element-per-line) decided in favor of Jackson's own default, which turned out to already render a `float[]` on a single compact line, not the multi-thousand-line file the design note initially predicted before a real fixture was recorded | **M**, as estimated | Needed *before* A2, not before A1 — and A1 was already done first, unblocked. Verified end to end against real `llama3.2:1b` (`OllamaEmbeddingEndToEndTests`): record on a miss, replay on a hit with zero additional HTTP requests, and the replayed vector proven bit-for-bit exact via `float[]` array equality, not "same length." See `docs/R4-EMBEDDING-INTERCEPTION.md` for the full design note |

### 2. Assertions layer — structured, deterministic correctness checks

**A1 — done.** `io.github.rifatcakir.springai.testtools.assertions` — see below. A2/E1
remain not started; both operate on the same `ChatClientResponse`/`ChatResponse` shape
A1 already covers, regardless of whether it came from a live call or a Recorder replay —
Assertions doesn't need to know which, by design (see `docs/VISION.md` Layer 2).

A1's detailed PRD — API design, real Spring AI shapes confirmed via `javap` (not
guessed), the three v1 decisions (no new JSON Schema dependency, two entry points with
exact/partial tool-argument matching, and the documented-not-worked-around tool-loop
scope limit), and how it was tested/showcased — is written up in
`docs/A1-ASSERTIONS-PRD.md`.

| # | Feature | Why | Size | Depends on |
|---|---|---|---|---|
| A1 | ~~**JSON / structured assertions**~~ **Done** — `VcrAssertions.assertThat(...)`: tool-call-shape assertions (`hasToolCall`, exact-`Map`/partial-`Consumer` argument matching, `hasNoToolCalls`, `hasToolCallCount`), `hasFinishReason`, `extractingText()` (bridges into ordinary AssertJ string assertions), and Jackson-tree-based `hasJsonField`/`hasJsonFieldOfType` (RFC 6901 JSON Pointer, no new external dependency — see the PRD's resolved decision) | Deterministic and cheap: no LLM call needed to *check* the assertion, only to produce the response being checked (which Recorder already makes free on replay). Highest value-per-effort of anything in this layer, and the natural **first Assertions item** | **S–M**, as estimated | Recorder only (already exists) — confirmed, no Recorder change was needed. 39 new tests (104/104 total), both pass and fail-message cases for every assertion type; showcased in the example project against already-committed fixtures, no new recording |
| A2 | ~~**Embedding / semantic assertions**~~ **Done** — `usingEmbeddingModel(EmbeddingModel).isSemanticallySimilarTo(expected[, threshold])` / `isSemanticallySimilarToAnyOf(candidates, threshold)`, cosine similarity computed directly (confirmed no Spring AI helper exists — no new dependency). Both embedding calls (response text, expected text) go through the caller-supplied model exactly like any other use, so a `VcrEmbeddingModel` (R4) makes the whole assertion Recorder-backed with zero new fixture type | The obviously-useful "is this answer close enough in meaning" check that a plain string/JSON assertion can't do. **Critical dependency, not an implementation detail:** the embedding call this assertion makes must itself be a Recorder-backed call (R4), or every CI run makes a live, non-deterministic embedding call — exactly the problem this whole project exists to eliminate, one layer up. Confirmed end to end against real `llama3.2:1b`, not assumed: a genuine paraphrase passes, an unrelated sentence does not, a second identical assertion makes zero additional HTTP requests. **Real finding, not just a design note:** `llama3.2:1b`'s embeddings (extracted from an LLM's hidden states, not a purpose-trained embedding model) compressed real paraphrase/unrelated-sentence similarity into 0.93–0.95 vs. 0.66–0.72 — high enough that the 0.7 default doesn't reliably separate them for this model; kept as the library default (reasonable for a dedicated embedding model) but documented plainly, with the e2e test itself needing an empirically-calibrated 0.85 to demonstrate the distinction cleanly | **Done**, roughly **M** as estimated | R4 (done) — hard dependency, satisfied |
| A3 | ~~**Fluent API shape**~~ **Folded into A1, not a separate step** — `VcrAssertions.assertThat(...).hasToolCall(...).hasFinishReason(...)` etc. already is the AssertJ-idiomatic fluent surface this item described; A1 shipped it directly rather than building a shell first and assertions second | Ergonomics: makes A1/A2 pleasant to use, mirrors AssertJ's shape (which this project's own tests already use, so it's a familiar idiom to this codebase's own conventions) | **M** — turned out to cost nothing extra once A1 existed | A1 (needs at least one real assertion type to build a fluent surface around — building the fluent shell before any assertion exists risks designing against a guess). Confirmed: implemented *together with* A1, not as a strictly separate step |

**A2 (embedding-cosine) vs. E1 (Spring AI's `RelevancyEvaluator`) — two different tools
for a similar-sounding question, not a redundant pair.** Both can answer some version of
"is this response semantically acceptable," but they trade off differently, and the
right one depends on what's being checked:

| | A2 (embedding-cosine similarity, on R4) | E1 (`RelevancyEvaluator`/`FactCheckingEvaluator`, on Recorder) |
|---|---|---|
| What it actually measures | Vector-space distance between two pieces of text | A model's own yes/no judgment against a rendered prompt |
| Cost per check | One embedding call (small, fast, cheap even live) | One full chat-model call (bigger, slower, same cost class as the response under test) |
| Judgment quality | Purely geometric — "close in embedding space," not "correct" or "relevant" in any semantic sense a human would defend | A real judgment, in the same sense the response under test is a real judgment — can catch things cosine distance structurally cannot (a factually wrong answer can be embedding-close to a right one) |
| Determinism story | Deterministic once R4 caches the embedding call — no model variability in the *comparison* itself, only in whichever response is being compared | Deterministic once E1 confirms the judge call is Recorder-backed — but the judge model's own non-determinism/version drift is a real property of *what's being cached*, not eliminated by caching it (same caveat `docs/VISION.md`'s Evaluator section already names) |
| Best fit | Cheap, high-volume "is this answer roughly the same meaning as the reference" checks, or as a fast pre-filter | Higher-stakes or more nuanced checks — relevancy to a retrieved context (RAG), factual support against a document — where "close in embedding space" isn't actually the property under test |

Not a decision to make once and apply everywhere: a test suite can use both, for
different assertions, exactly as their cost/quality tradeoff suggests.

### 3. Evaluator layer — non-deterministic judgment, made CI-safe by Recorder

**E1/E2 done, confirmed with real code and a real model, not just reframed in the
abstract**: Spring AI 2.0.0 already ships an official `Evaluator` mechanism —
`org.springframework.ai.evaluation.Evaluator`/`EvaluationRequest`/`EvaluationResponse`
(in `spring-ai-commons`, already a transitive dependency of this project via
`spring-ai-client-chat` → `spring-ai-model` → `spring-ai-commons` — confirmed via `mvn
dependency:tree`, no new dependency needed) plus two ready-made implementations,
`RelevancyEvaluator` and `FactCheckingEvaluator` (in `org.springframework.ai.chat.evaluation`,
inside `spring-ai-client-chat` — a dependency this project already declares directly).
**Both are constructed from a plain `ChatClient.Builder`, and both were confirmed via
`javap -c` bytecode disassembly — not assumed from their names — to internally do
exactly `chatClientBuilder.build().prompt().user(...).call().content()`: an entirely
ordinary `ChatClient` call, structurally identical to any other call this library
already intercepts.** This means Evaluator's job is no longer "design and build an
LLM-as-judge mechanism from scratch" — it is **"prove that Spring AI's own official
evaluators, when built from a VCR-wired `ChatClient.Builder`, get cached and replayed by
the Recorder this project already has, with zero new mechanism and zero new fixture
type."** That claim is no longer just a bytecode argument: `OllamaEvaluatorEndToEndTests`
confirms it against a real model — first `evaluate()` call records, identical second
call replays with zero additional HTTP requests, verdict exactly matches. See
`docs/VISION.md`'s Layer 3 section for the updated positioning this finding justifies:
not "we invented judge-call determinism," but "we make Spring AI's own official
evaluators deterministic and free to run in CI, for free."

| # | Feature | Why | Size | Depends on |
|---|---|---|---|---|
| E1 | ~~**Prove Spring AI's own `RelevancyEvaluator`/`FactCheckingEvaluator` are Recorder-backed for free**~~ **Done** — `OllamaEvaluatorEndToEndTests`: both evaluators, built from the same `ChatClient.Builder` this library's `ChatClientBuilderCustomizer` already attaches `DeterministicVcrAdvisor` to, confirmed end to end against real `llama3.2:1b` — first `evaluate()` call reaches the model and records, identical second call makes zero additional HTTP requests, replayed `EvaluationResponse` verdict exactly matches what was recorded. **The `docs/BRAINSTORM.md` "recursion" worry — a verdict frozen forever against a response that has since changed — is also confirmed closed, not assumed:** two more tests prove that changing the judged response/claim (same query, same context, different answer) produces a genuinely different canonical request, reaches the model again, and writes a second, separate fixture — proven by counting real HTTP requests and real fixture files, not by reading the prompt template's source and assuming it embeds the output | Foundational, but smaller than originally scoped: the mechanism already existed (Spring AI's), the fixture format already existed (this library's `VcrTrack`), and the wiring already existed (`ChatClientBuilderCustomizer`) — what was missing was the *proof*, not a new advisor or judge-call abstraction | **S**, as re-estimated once reframed — confirmed, no new mechanism was needed | Recorder only, already existed. **No production code added to this library** — `Evaluator` is Spring AI's own interface; the "feature" is a proven, documented usage pattern, not new library code |
| E2 | ~~**Toxicity/hallucination/relevance checks**~~ **Done — reframed around the actual differentiator: one evaluator, two modes.** Hallucination (`FactCheckingEvaluator`) and relevance (`RelevancyEvaluator`) were already proven in E1. Toxicity: **confirmed absent from Spring AI 2.0.0** (checked every jar on this project's classpath — only `RelevancyEvaluator`/`FactCheckingEvaluator` exist); documented as a buildable-but-unbuilt pattern (same shape as `FactCheckingEvaluator`), not built speculatively without a real need. The actual E2 deliverable: `OllamaEvaluatorEndToEndTests` gains a test proving `VcrMode.BYPASS` always reaches the model even when a matching fixture exists — the exact same evaluator construction, live drift/quality-check mode instead of deterministic-replay mode, with **zero new mechanism**, purely a `VcrMode` choice on the same advisor. The main library's existing nightly/`workflow_dispatch` `e2e` CI job now also runs this test class, on the same real-Ollama cadence as the rest of that job — no new CI infrastructure | **S** — no new mechanism, same reason E1 was small | E1 |
| E3 | **Batch verification across a test run** (`docs/BRAINSTORM.md` idea: one LLM call judging many tests' outputs together instead of one call per test) | Unchanged by the Spring AI evaluator finding — batching is a separate concern from which evaluator mechanism is used underneath. Real appeal (fewer round trips, centralized judge prompt) but real unresolved risks documented in `docs/BRAINSTORM.md` — context contamination between batched cases, traceability of which test actually failed, and a genuinely open question about whether/how the batch call itself gets cached. **Do not start without first validating E1's single-call pattern in production use** — batching an unproven mechanism compounds the risk instead of isolating it | **M–L**, and **needs its own design note before any code**, same bar as streaming (R3) | E1 proven first. Still an open question whether this belongs in this library at all vs. a separate sibling tool that merely uses Recorder — see `docs/BRAINSTORM.md` |

**CI or a separate nightly evaluation pipeline?** Resolved, not open: since every
Evaluator judge call is itself a normal Recorder-backed `ChatClient` call (E1's design),
it replays exactly like any other fixture in the existing sealed `REPLAY_ONLY` CI run —
**no separate pipeline is required for determinism reasons**, because Recorder is what
already makes it deterministic. What a separate, optional nightly/`workflow_dispatch` job
*is* good for — mirroring the existing `e2e` job's pattern — is **intentional drift
detection**: periodically deleting recorded judge fixtures and re-verifying against the
live judge model to catch a case where the underlying provider model quietly changed
behavior between the same-hashed inputs. That's a maintenance job, not a CI requirement,
and it's the same category of concern `docs/VISION.md`'s "provider independence is
unproven" caveat already names for Recorder itself.

### 4. Suggested order

1. **Done** — R1 (structured output round-trip). Diagnosis found both things it was
   supposed to be able to find: the single-DTO case already worked, and a real,
   scoped cache-key blind spot existed for different `entity()` types sharing prompt
   text. Fixed the same session, `VcrTrack` schema version `"3"`, all prior golden
   hash tests unchanged, both a real-model and a fast Docker-free test prove the fix.
2. **Done** — R2 (multi-provider validation), via Ollama's OpenAI-compatible endpoint —
   no new paid API dependency needed. `OpenAiChatModel` (a genuinely different Spring AI
   `ChatModel` implementation, built on the official OpenAI Java SDK rather than
   `OllamaChatModel`'s `RestClient`-based client) records and replays correctly, and —
   the critical proof — a fixture recorded through the native Ollama client replays
   identically through the OpenAI-SDK client, confirming the cache key does not encode
   provider identity. No production code changed.
3. **Done** — A1 (JSON/structured assertions), `io.github.rifatcakir.springai.testtools.assertions`.
   First real Assertions-layer code; unblocked by R1/R2 without hard-depending on either.
   A3 (fluent shell) folded into A1 rather than built separately — A1's `VcrAssertions`/
   `ChatResponseAssert`/`ChatClientResponseAssert` already are the fluent AssertJ-idiomatic
   surface A3 would have added; see `docs/A1-ASSERTIONS-PRD.md`.
4. **Done** — R4 (`EmbeddingModel` interception), `io.github.rifatcakir.springai.testtools.recorder.embedding`.
   A `BeanPostProcessor`-based decorator (`VcrEmbeddingModel`), since `EmbeddingModel` has
   no advisor chain to attach to. Own fixture type (`VcrEmbeddingTrack`), own independent
   `spring.ai.test.vcr.embedding.*` properties. Verified end to end against real
   `llama3.2:1b`: record on a miss, zero additional HTTP requests on a hit, replayed
   vector proven bit-for-bit exact. See `docs/R4-EMBEDDING-INTERCEPTION.md`.
5. **Done** — A2 (embedding/semantic assertions), `usingEmbeddingModel(...).isSemanticallySimilarTo(...)`
   on top of R4. Confirmed end to end against real `llama3.2:1b`, including the
   empirical threshold-calibration finding — see `docs/A2-SEMANTIC-ASSERTIONS-PRD.md`.
6. **Done** — E1 (prove Spring AI's own `RelevancyEvaluator`/`FactCheckingEvaluator` are
   Recorder-backed for free), `OllamaEvaluatorEndToEndTests`, end to end against real
   `llama3.2:1b` — reframed from "build an LLM-as-judge mechanism" once bytecode
   confirmed both evaluators already make an ordinary `ChatClient` call internally, then
   confirmed for real, not left as a bytecode argument; see section 3 above.
7. **Done** — E2 (evaluation modes, `docs/E2-EVALUATION-MODES-PRD.md`): toxicity confirmed
   absent from Spring AI 2.0.0 and documented as a buildable-but-unbuilt pattern; the real
   deliverable is proving the same evaluator runs deterministically (`REPLAY_ONLY`, every
   CI run) or live (`BYPASS`, a drift/quality check) purely by which `VcrMode` its advisor
   was built with — zero new mechanism. The main library's nightly `e2e` job now also
   runs `OllamaEvaluatorEndToEndTests`; the example project demonstrates the live path via
   its existing `@Vcr(mode = BYPASS)` escape hatch, integration-tagged, never in its own CI.
8. **E3 — Batch verification**, only after E1 has real production mileage, and only after
   its own dedicated design note resolves the open questions `docs/BRAINSTORM.md` already
   raised.
9. **Done** — R3 (streaming replay, `docs/R3-STREAMING-PRD.md`): `DeterministicVcrAdvisor`
   now implements `StreamAdvisor` alongside `CallAdvisor` on the same class, backed by a
   new `VcrStreamTrack` fixture type that stores the raw chunk sequence (the actual replay
   source of truth) plus a review-only aggregate, mirroring how `canonicalRequest` is
   stored for humans only. An empirical diagnosis against real `llama3.2:1b` (raw `curl`
   against Ollama's native streaming endpoint, five observations) confirmed tool calls
   arrive chunk-complete, never fragmented — so streamed tool-call support shipped in v1
   with no fragment-merging logic needed. Wired into auto-configuration automatically,
   no separate toggle; proven against a real model via `OllamaStreamingEndToEndTests`,
   now also in the main library's nightly `e2e` job, with chunk-for-chunk exact-match
   assertions, not aggregate-only ones.

Items 7–8 (diagnostics) and item 9 (publishing follow-through) from the existing "Feature
roadmap" tables below continue independently of this list — they're small and
opportunistic, not sequenced against the layered plan.

### 5. Distinguishing this from what's already rejected

`CLAUDE.md`'s "Do not" list and the "Explicitly rejected" table below reject **semantic
*matching*** — using embedding similarity to decide whether an *incoming request* resolves
to a previously-recorded fixture. That rejection is unconditional and permanent, and
nothing in this section reopens it: the cache key is, and remains, an exact SHA-256 match,
always.

**A2 (semantic *assertions*) is a different operation, not a softened version of the
rejected one.** It runs strictly after Recorder has already resolved the cache key by
exact match and returned a response (live or replayed, deterministically, either way) — it
never influences which fixture gets served or whether a request counts as a hit. It is a
correctness check a test performs *on* an already-deterministic response, using the same
category of tool (an embedding call) for a completely different purpose (comparing two
already-fixed pieces of text) than the rejected feature (deciding whether two *requests*
are "close enough"). Worth stating this explicitly and permanently in this document so a
future reader doesn't mistake A2 for a backdoor reopening of the rejected feature.

### 6. Considered and deferred — not on this roadmap

A few ideas (multimodal support, a fixture diff viewer, an HTML coverage/hit-miss
dashboard) surface naturally when thinking about where a project like this could go next.
Each was weighed and set aside, with a reason, rather than silently dropped:

| Idea | Why it's not on this roadmap |
|---|---|
| **Multimodal (image/audio input) caching** | Would require embedding raw binary content (or a reference to it) into a fixture. Directly in tension with design rule #5 (fixtures are pretty-printed, committed, and reviewed in PRs) — a fixture holding base64 image bytes is neither readably diffable nor a citizen a reviewer can reasonably eyeball, and bloats repo size in a way text fixtures don't. Not rejected forever, but needs its own design pass (probably: store a content hash/reference, not raw bytes) before it's more than an idea — the same bar this file's own "Future" table already holds sequenced responses to |
| **A dedicated fixture diff viewer** | The problem it would solve is already solved: design rule #5 chose pretty-printed JSON specifically *so that* an ordinary `git diff` in a PR review is the diff viewer — "a readable diff is worth more than a small file" is the rule's own stated reasoning. Building bespoke tooling to re-solve an already-solved problem adds real surface area (a CLI or UI project, maintained indefinitely) for a marginal readability gain over what reviewers already get for free |
| **An HTML hit/miss/coverage dashboard** | A heavier version of the already-planned item #7 (a lightweight hit/miss counter/listener). A full dashboard implies a report-generation module, a rendering surface, and ongoing design/styling maintenance — disproportionate investment for a `0.1.0` library with no confirmed second consumer yet. Consistent with this project's existing bias (see the multi-module and pluggable-storage-backend open questions already in this file) toward not building speculative infrastructure ahead of real, demonstrated demand. Revisit only if adoption actually creates pull for it |
| **Problem-oriented documentation site (MapStruct-style)** | A dedicated docs site on GitHub Pages (`mkdocs-material` or similar), with a landing page that leads "here's the problem, here's the fix" — showing concretely why a Spring AI integration test is hard to write deterministically, then walking through this library's answer step by step, in the spirit of MapStruct's own documentation site. Explicitly requested by the user as a future idea, not started now: this is a post-publish/post-adoption investment (a project with no confirmed external consumer yet doesn't need a marketing-grade docs site before it needs users), and the README/`docs/` split already carries the documentation load at this stage. Revisit once the library is published and has real adopters to write for |

---

## Why this file exists

There was no single roadmap before this. Planning was split across two documents that
each cover part of the picture:

- **`docs/STATUS.md`** — "Next tasks, in order" (7 items) plus an "Open questions for the
  maintainer" list. Sequential and compile/test-focused, written for whoever picks up the
  repo next.
- **`docs/DISPATCH_PROMPT.md`** — four copy-paste prompts for a dispatched coding agent
  (compile-and-green, e2e Testcontainers test, auto-config slice tests, and a design-only
  task for the `REPLAY_ONLY` escape hatch). Task 1 is now done (see `STATUS.md`).

Neither document ranks work by value, sizes it, or looks outward at how mature prior art
(VCR.py, Ruby VCR, WireMock, Polly.js) solved adjacent problems. That's what this file
adds. It does not replace `STATUS.md` — that stays the terse "what's true right now"
ledger. This file is the "what should we build, in what order, and why" view, and it
folds in everything `STATUS.md`'s task list and `DISPATCH_PROMPT.md` already committed
to, so you only need to read one thing to plan work.

## Current state (as of this writing)

`mvn test` green (55/55), plus two real Testcontainers + Ollama end-to-end tests
(`OllamaEndToEndTests` and `OllamaToolCallingEndToEndTests`, both excluded from the
default run, verified via `mvn test -Pintegration-test`) proving record → replay → zero
additional network requests on the hit against a real model, not a mock — the second one
specifically for a two-turn tool-calling round trip under `INSIDE_TOOL_LOOP`, including
the real `@Tool` method re-running on replay. `VcrTrack` schema version `"2"` fixed a
real gap found while building that test: a message's own tool calls/responses were
silently invisible to both the hash and the fixture, because `Message.getText()` is
empty for both an assistant's tool-calls-only turn and any tool-response message — two
different tool calls or two different tool results used to collide on the same fixture
hash under `INSIDE_TOOL_LOOP`. Golden hash tests for ordinary (non-tool-call) prompts
were verified unaffected; new golden hash tests pin the tool-call/tool-response
canonicalization specifically. `VcrFixtureRedactor` now exists
alongside `VcrPromptNormalizer` for redacting committed-fixture content without ever
being able to change a request's cache key, and `@Vcr(mode = ...)` now exists as a
per-test escape hatch out of a sealed `REPLAY_ONLY` CI run. A GitHub Actions workflow
(`.github/workflows/ci.yml`) is live and green at
<https://github.com/rifatcakir/spring-ai-test-tools/actions> — the repo is public and
pushed, and both the per-PR unit test job and the scheduled Ollama e2e job have run
successfully on a real hosted runner. The build side of publishing (`LICENSE`, Central
metadata, a `release` profile) is done and `mvn -Prelease package` verified to actually
work; nothing has been published, and everything credential-related is left for the
maintainer per `docs/PUBLISHING.md`. See `STATUS.md` for the full detail and the bugs
fixed to get here.

---

## What prior art gets right (and what doesn't transfer)

| Tool | Model | Ideas worth stealing | Ideas that don't fit here |
|---|---|---|---|
| **[VCR.py](https://vcrpy.readthedocs.io/)** | HTTP-layer interception, one cassette (YAML/JSON) holds an ordered list of request/response pairs | Record modes (`once` / `new_episodes` / `none` / `all`), `before_record_request`/`filter_headers` as a **separate** hook from the request matcher, per-test cassette activation (`@pytest.mark.vcr`) | Fuzzy/partial request matching (`match_on` combinators) — this project's whole reason to exist is refusing exactly that; cassette-as-multi-interaction-file — this project already made the opposite call (one file per hash, O(1) lookup, see `STATUS.md` decision table) |
| **Ruby VCR** | Same lineage as VCR.py, more mature hook system | `allow_playback_repeats`, pluggable request matchers as first-class objects, `ignore_request` | Same fuzzy-matching caveat as VCR.py |
| **WireMock** (record-playback) | Server-side stub journal, supports **scenarios**: the same request returns different canned responses across ordered calls (simulate retry-then-success, rate-limit-then-ok) | The scenario/sequenced-response idea — useful for testing retry and backoff logic against an LLM client without a live flaky provider | Its stub-priority/proxy model doesn't map cleanly onto an in-process advisor with no HTTP layer |
| **Polly.js** | Interception adapters decoupled from storage "persisters" (fs, REST, localStorage) | Decoupling capture from storage *in principle* | Pluggable storage backends directly **contradict** this project's rule #5 (fixtures are committed, pretty-printed, reviewed in PRs) — adopting this would mean fixtures could live somewhere un-reviewable. Only worth revisiting if a second consumer with a real need shows up (see `STATUS.md`'s multi-module open question) |
| **nock** | Fixture "modes" (record / dryrun / wild / lockdown) similar in spirit to this project's `VcrMode` | Naming/semantics cross-check only — `VcrMode` already covers the same ground | Nothing new |

Two gaps below were found by making this comparison, not carried over from either
existing planning doc — they're flagged as such.

---

## Feature roadmap

### Must-have (blocking a credible v0.1.0)

| # | Feature | Why | Size | Source |
|---|---|---|---|---|
| 1 | ~~**Separate the cache-key normalizer from fixture redaction**~~ **Done** | `VcrFixtureRedactor` (new SPI in the root `vcr` package, alongside `VcrPromptNormalizer`): applied only on the write path, after the hash is already computed from the un-redacted request; `hash()`/`schemaVersion()` on a redactor's return value are ignored and re-applied from the original track by `DeterministicVcrAdvisor`, enforced in code (belt-and-suspenders — a redactor is structurally unable to change the cache key, not merely asked not to). Collected the same way normalizers already are (`List<VcrFixtureRedactor>`, `Ordered` sequence). Five tests in `VcrFixtureRedactorTests` cover bit-identical no-redactor behavior, redaction never reaching a live response or replay, a forged-hash attempt being ignored, ordering across multiple redactors, and a throwing redactor propagating with nothing written. README gained a "Redacting fixture content" section with a comparison table, specifically because a normalizer *merges* requests and a redactor *never* does — confusing the two silently causes cache collisions on real PII, which is exactly the failure mode this item exists to close | M (1–2 days) | New |
| 2 | ~~**Auto-configuration slice tests + `additional-spring-configuration-metadata.json`**~~ **Done** | `SpringAiVcrAutoConfigurationTests` (9 tests): absence/presence by `enabled`, scope-derived vs. explicit order, `@ConditionalOnMissingBean` for all four bean types, and registered `VcrPromptNormalizer` beans confirmed reaching the generated `VcrCacheKeyGenerator`. Metadata file merges cleanly (verified against the built `spring-configuration-metadata.json`) | S–M (1 day) | `STATUS.md` #3/#4, `DISPATCH_PROMPT.md` Task 3 |
| 3 | ~~**`REPLAY_ONLY` escape hatch**~~ **Done** — `@Vcr(mode = ...)` JUnit 5 annotation + extension. See the design note below for the comparison and rationale | CI runs sealed (`REPLAY_ONLY`); there is now a sanctioned, narrowly-scoped way for a single test to make a live call without weakening the whole suite's guarantee | Design: hours. Impl: M (1 day) | `STATUS.md` #6, `DISPATCH_PROMPT.md` Task 4 |
| 4 | ~~**Real end-to-end proof**~~ **Core proof done** — `OllamaEndToEndTests` (`@Tag("integration")`, `mvn test -Pintegration-test`): real Testcontainers-managed Ollama container, real `llama3.2:1b`, genuine cache miss → record → hit → replay, with an HTTP request counter wired into the `RestClient` underneath `OllamaApi` proving zero additional network requests on the hit — not inferred from response text alone. **Not yet covered** by this test (narrower, can be added incrementally rather than re-blocking anything): `REPLAY_ONLY` throwing on a miss without touching the container, and the `INSIDE_TOOL_LOOP` vs `OUTSIDE_TOOL_LOOP` distinction via a counting `@Tool`. Docker Desktop needed starting first; once started, this was unblocked the same session | Core: done. Remaining two scenarios: S (a few hours, same test class) | `STATUS.md` #2, `DISPATCH_PROMPT.md` Task 2 |
| 5 | ~~**CI workflow with a fixture-drift gate**~~ **Done, verified green on a real runner** — `.github/workflows/ci.yml`, live at <https://github.com/rifatcakir/spring-ai-test-tools/actions>: a `test` job (every push/PR, `mvn test`, no Docker) with a hard-fail `git status --porcelain` check after the run, and a separate `e2e` job (nightly + `workflow_dispatch` only, not per-PR) for the real Testcontainers proof. See the design note below for why `-Dspring.ai.test.vcr.mode=REPLAY_ONLY` was deliberately *not* added, and why the drift check is whole-tree rather than fixture-path-scoped | Turns "a fixture change in CI means someone bypassed review" from a stated intent into an enforced check. Both jobs have completed successfully on GitHub's own `ubuntu-latest` runners — no longer just a local dry run | S (a few hours) | `STATUS.md` #7 |
| 6 | **Document the non-determinism caveat** — a fixture recorded at `temperature > 0` freezes one sample from a distribution; replay will make a flaky-in-production prompt look deterministically stable in tests, and that's a property of testing, not of the model | Near-zero cost, prevents a confused bug report down the line ("why does this always pass in CI but the model is clearly non-deterministic in prod") | XS (docs only) | New, but small enough to just do — **done**, see README "Limitations" |

#### How item 1 was actually resolved

The design sketch below is kept for the record; each open question it raised was settled
as follows when `VcrFixtureRedactor` was built:

- **Request-side, response-side, or both?** One interface, one method, taking the whole
  `VcrTrack` (`VcrTrack redact(VcrTrack track)`) rather than a split
  `VcrRequestRedactor`/`VcrResponseRedactor` pair. A `canonicalRequest` string field also
  turned out to need redacting — it holds the same message text a `RequestSnapshot`
  redactor would already be scrubbing, just in a second place in the same fixture, so
  splitting by request/response wouldn't even have been enough; a redactor needs to see
  everything that gets written, in one pass.
- **Does redaction ever run on the replay path?** No — enforced structurally, not just
  documented. `DeterministicVcrAdvisor.applyRedactors()` is only ever called from the
  record path (`recordAndReturn()`); there is no code path that invokes a redactor
  during a hit, so a replay is physically incapable of being touched by one.
- **Naming clash risk with `VcrPromptNormalizer`.** Resolved with documentation, not a
  combined interface: `VcrFixtureRedactor`'s Javadoc and the README's new "Redacting
  fixture content" section lead with a direct comparison table (does it affect the hash?
  does it affect what a hit returns?) precisely because the two are easy to reach for
  interchangeably and only one of them is safe on real PII.
- **Does this replace the "raw prompt alongside normalized" open question?** Left open
  below, unchanged — a redactor makes storing the raw prompt *safer* if someone chooses
  to do it later, but doesn't by itself decide whether the project should.

One thing the original sketch didn't anticipate: the hash/schemaVersion enforcement
needed to be defense-in-depth, not just "the advisor doesn't call redactors on a hit."
A redactor's `redact()` method still receives and returns a full `VcrTrack`, meaning
nothing stops an implementation from returning a track with a different `hash()` —
so `applyRedactors()` explicitly discards whatever a redactor returns for `hash()` and
`schemaVersion()` and re-applies the original values after every redactor in the chain,
rather than trusting well-behaved implementations to leave those two fields alone.

#### Design note for item 3 — the `REPLAY_ONLY` escape hatch

The problem, precisely: CI runs with `spring.ai.test.vcr.mode=REPLAY_ONLY` so the whole
suite is sealed — a miss throws `VcrCacheMissException` rather than reaching a real
model. `VcrCacheMissException` itself already carries everything `CLAUDE.md`'s own
Javadoc conventions ask for (the hash, the expected file path, the full canonical request,
and the exact command to re-record — see `VcrCacheMissException`, unchanged by this item).
That part was never broken; what's missing is a way for *one* test to legitimately need a
live call — a smoke test against a real provider, or an assertion on something `VcrTrack`
deliberately drops (native usage objects, for instance) — without weakening the sealed
guarantee for the other few thousand tests in the same CI run.

Evaluated against three questions from `DISPATCH_PROMPT.md` Task 4 — does it keep CI
sealed *by default*, can it be abused to silently re-enable network calls for the *whole*
suite, and how much surface area does it add:

| Option | Sealed by default? | Whole-suite abuse risk | Surface area added |
|---|---|---|---|
| **a) Per-request override via an `AdvisorParams`-style mechanism** | Yes, in principle | Low in isolation, but only works if application code cooperates | Requires threading a param through the *production* `ChatClient.prompt()...advisors(...)` call in app code under test — directly at odds with `SpringAiVcrAutoConfiguration`'s own stated goal that "no test-only conditional appears anywhere" in production code |
| **b) `@Vcr(mode = ...)` JUnit 5 extension** | Yes — inert unless a test is annotated | Low — scoped to one test method/class, thread-local, automatically cleared after every test regardless of outcome | One annotation, one extension, one `ThreadLocal` holder; an optional `junit-jupiter-api` compile dependency (see below) |
| **c) A property listing exempt test classes** | Yes, if the list starts empty | Higher — a single shared list is exactly the kind of config that silently grows over time and is easy to forget to prune; unlike (b), nothing restores it automatically once a test no longer needs it | A class-name-matching mechanism wired into advisor construction, plus a process reminder to prune the list |
| **d) Do nothing — separate source set with the advisor absent entirely** | Yes, architecturally: no code path exists to bypass | None | A second source set/module, duplicated Spring wiring, and an argument that in-suite ergonomics for this case aren't worth it |

**Recommendation: (b).** (a) turns out worse than it first looks: it requires the
*application* code under test to explicitly opt in to being overridable, which means a
test can't unilaterally request a live call without also reshaping how production code
calls `ChatClient` — the opposite of this library's whole design principle of attaching
via `ChatClientBuilderCustomizer` so application code never has test-only branches. (c) is
the "blast radius creeps quietly" option — a shared list has no automatic cleanup the way
(b)'s `afterEach` does. (d) is the architecturally purest option, and was seriously
considered, but the concrete examples this item exists for (one smoke test, one
lossy-round-trip assertion) are individual test *methods*, not whole modules' worth of
work — a second source set is a heavier tool than the problem calls for. (b) matches the
scoping of (d) (opt-in, cannot leak beyond where it's applied) while keeping the
ergonomics of annotating just the one test that needs it, consistent with how
`VcrPromptNormalizer` and `VcrFixtureRedactor` are already small, additive, opt-in SPIs
rather than central configuration.

**One thing not anticipated before actually building it:** shipping `@Vcr` means shipping
a JUnit 5 dependency from *main* sources, not test sources — because `@Vcr` needs to be
usable from a *consuming* project's own tests, and this library's main sources are what
gets published as that project's `<scope>test</scope>` dependency. `pom.xml` gained
`org.junit.jupiter:junit-jupiter-api` as an `optional` compile dependency for this reason
— present so this module compiles, not force-propagated onto consumers, who already have
`junit-jupiter-api` on their test classpath via `spring-boot-starter-test` (or equivalent)
in any project that could use this library at all. This mirrors why
`spring-boot-autoconfigure` is already `optional` in this same `pom.xml`.

**Known limitation, documented rather than solved:** the override is a `ThreadLocal`, so
it only applies to whichever thread actually invokes the advisor. Synchronous, blocking
`ChatClient.call()` usage — everything this advisor currently supports, since `.stream()`
already passes straight through by design — satisfies this. Code that switches threads
(an async executor, a reactive chain) before reaching the advisor will not see the
override; that is a pre-existing constraint of this advisor being `CallAdvisor`-only, not
something this feature introduces.

#### Design note for item 5 — the CI workflow

Two findings, made by actually checking this repository rather than assuming, changed
the shape of this from what `STATUS.md`'s original task description asked for:

- **This repository commits zero fixtures of its own.** Every existing test —
  `DeterministicVcrAdvisorTests`, `VcrFixtureRedactorTests`, `SpringAiVcrAutoConfigurationTests`,
  `VcrModeExtensionTests`, `OllamaEndToEndTests` — uses `@TempDir` for its cache
  directory. So "fail if any *committed* fixture changed" has nothing to point at yet in
  this repo specifically. The gate is written as a whole-working-tree
  `git status --porcelain` check instead of one scoped to `src/test/resources/llm-cache/`
  — harmless today (that path doesn't exist), and it starts doing its intended job
  automatically the moment any fixture is committed here or in a project copying this
  workflow, with no logic change required.
- **`-Dspring.ai.test.vcr.mode=REPLAY_ONLY` would be decorative, not functional, for this
  repo's own suite.** None of the tests above read that property from the ambient
  environment — they either construct `DeterministicVcrAdvisor` directly in Java with an
  explicit `VcrMode`, or set the property themselves via `ApplicationContextRunner`'s
  `withPropertyValues(...)`, which in Spring's property-source precedence wins over
  anything passed as a JVM `-D` flag regardless. Adding the flag anyway would look like a
  meaningful CI safeguard while doing nothing — worse than not adding it. What actually,
  rigorously verifies `REPLAY_ONLY`'s sealed behavior is already in the suite that
  `mvn test` runs on every commit: `DeterministicVcrAdvisorTests#replayOnlyThrowsOnMiss`,
  `#replayOnlyServesHits`, and all of `VcrModeExtensionTests`. That is the actual
  verification this item asked for — it already existed before this item started, and CI
  running the full suite on every push is what keeps it continuously checked, not a flag
  bolted onto the `mvn test` invocation.

**Why the `e2e` job is nightly + `workflow_dispatch`, not per-PR:** measured locally, a
cold run (fresh container, model pulled and baked into a tagged image) took ~185s; a warm
rerun reusing that tag took ~50s. A GitHub-hosted runner starts a fresh VM per job with no
persistent Docker image cache between runs, so *every* invocation there pays roughly the
cold-run cost, not the warm one measured here — there is no way to get the fast path
without a custom image-layer caching step (saving/loading `ollama/ollama` layers via
`actions/cache`), which was left as a possible future optimization rather than built now,
since it adds real complexity for a job that doesn't need to run on every PR anyway.

**What was verified, and when:**

- ✅ The YAML parses (checked with PyYAML, before the first push).
- ✅ The drift-check shell logic: staged the workflow file, ran `mvn test` locally,
  confirmed `git status --porcelain` showed no changes beyond what was already staged —
  a healthy run really does leave the tree clean, so the gate isn't trivially broken by
  its own presence.
- ✅ **The workflow has actually run, repeatedly, on real GitHub Actions
  infrastructure**, at <https://github.com/rifatcakir/spring-ai-test-tools/actions>. The
  `test` job has completed successfully on `push` (~30s per run). The `e2e` job has
  completed successfully both via the `schedule` trigger (confirming the default-branch
  cron-activation caveat below is now moot — it fired) and was exercised manually too.
- ✅ A real `ubuntu-latest` runner does resolve `spring-boot-dependencies:4.0.0` /
  `spring-ai-bom:2.0.0` / `spring-ai-ollama:2.0.0` correctly — the `test` job passing is
  direct evidence of this, not an inference.
- ✅ Testcontainers/Ryuk works on a hosted runner's Docker setup, and the real Ollama
  container + model pull completes within the job — the scheduled `e2e` runs succeeded
  (~1–2 minutes each), which is only possible if all of that worked end to end.
- ✅ The `schedule` (cron) trigger does fire once the workflow file is on the default
  branch — confirmed by observing actual `schedule`-triggered runs complete, not just
  inferred from GitHub's documented behavior.
- Not specifically inspected: whether `actions/setup-java`'s `cache: maven` step is
  actually hitting a warm cache versus downloading fresh every run — the jobs succeed
  either way, so this affects run time, not correctness, and wasn't dug into further.

### Nice-to-have (valuable, doesn't block a v0.1.0 tag)

| # | Feature | Why | Size | Source |
|---|---|---|---|---|
| 7 | **Hit/miss diagnostics** — a lightweight counter or listener (e.g. `VcrDiagnostics`) exposing hits/misses/records per test run | `CLAUDE.md`'s own testing convention demands "assert on chain invocation counts, not just response payloads." Right now consumers have no supported way to do the equivalent for their own tests short of re-implementing chain-count assertions by hand each time | S (half a day) | New |
| 8 | **`allow_playback_repeats`-style explicit opt-in for identical repeated calls** | Same request hash hit multiple times in one test today just replays every time (this already works, since it's file-based and stateless) — but there's no way to *assert* "this fixture was meant to be used exactly N times," which matters once diagnostics (item 7) exist | XS once item 7 lands | Ruby VCR |
| 9 | ~~**Publishing infrastructure**~~ **Build side done, nothing published** — `LICENSE`, Central-required `pom.xml` metadata, `release` profile (`maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`, `central-publishing-maven-plugin` — Sonatype's documented OSSRH successor, versions confirmed against real Central rather than guessed), `docs/PUBLISHING.md` walkthrough. `CONTRIBUTING.md` not started | Needed before a real `0.1.0` release. `mvn -Prelease package -DskipTests` verified to produce a clean `-javadoc.jar`/`-sources.jar` with zero Javadoc errors — the step most projects find broken only when they try to release for real. What's left (namespace verification, a GPG key, Sonatype token, `settings.xml` credentials, an actual `mvn deploy`) is all maintainer-only by design — an agent generating a key or writing credentials would mean secrets in a transcript and an irreversible public release happening without the maintainer directly in the loop | M | `STATUS.md` #8 |

### Future — needs its own design pass before any code is written

| # | Feature | Why it's parked | Source |
|---|---|---|---|
| 10 | ~~**Streaming replay**~~ **Done** — see **R3** in "The layered roadmap" section above and `docs/R3-STREAMING-PRD.md` for the full design note, diagnosis, and implementation | Was: `.stream()` passed straight through live, uncached. Fixed with its own fixture schema (`VcrStreamTrack`, raw chunk sequence as the replay source of truth), not a bolt-on to `VcrTrack` | `STATUS.md` "Known risks" #3 |
| 11 | **Sequenced/scenario responses per hash** (WireMock- and VCR.py-style: same request, different response on the 2nd/3rd call — useful for testing retry and backoff logic) | Directly in tension with design rule #1 ("exact match only... never a close-enough hit") and the one-file-per-hash layout. Would need an explicit, clearly-opt-in fixture variant (e.g. a `sequence` array) so it can never silently change single-answer semantics for everyone else. Don't start without a design note weighing this against just writing N separate tests with N distinct prompts instead | New (from WireMock comparison) |
| 12 | **VCR support for non-chat models** (embedding, image, audio, moderation) | Explicitly out of scope today because none of these pass through the `ChatClient` advisor chain — each would need its own interception point and its own investigation into whether an equivalent advisor/interceptor API even exists in Spring AI 2.0 for that model type. The embedding case specifically is no longer purely speculative: it's now tracked as **R4** in "The layered roadmap" section above, scheduled as a hard prerequisite for the Assertions layer's semantic-assertion feature (A2), not just a someday item. Image/audio/moderation remain unscheduled | `STATUS.md` "Scope limits" |
| 13 | **Pluggable fixture storage backend** (Polly.js "persister" style) | Contradicts design rule #5 (fixtures are pretty-printed, committed, and reviewed in PRs) — a pluggable backend invites un-reviewable fixtures. Only reconsider if a second real consumer needs it, which is the same trigger condition already named in `STATUS.md`'s multi-module open question | New (from Polly.js comparison), `STATUS.md` open question |

### Explicitly rejected — do not re-litigate

These are already decided in `CLAUDE.md`'s "Do not" list and are repeated here only so
this roadmap doesn't accidentally re-open them:

- Semantic / vector / similarity matching, at any threshold.
- TTL or time-based fixture invalidation.
- `ChatClientCustomizer`, `CallAroundAdvisor`, `AbstractAdvisor`, or
  `com.fasterxml.jackson.databind.ObjectMapper`.
- Auto-enabling the advisor by default (`spring.ai.test.vcr.enabled` must stay opt-in).

---

## LLM-specific concerns — where each one is actually handled

Called out separately because it's easy to plan a "VCR for LLMs" library by analogy to
HTTP VCR tools and quietly miss the parts that only exist because the thing being cached
is a model call, not a REST response.

| Concern | Current state | Gap, if any |
|---|---|---|
| **Streaming responses** | Recorded and replayed chunk-for-chunk (R3, `VcrStreamTrack`), tool calls included | None currently known |
| **Token/usage accounting** | `VcrTrack.ResponseSnapshot.usage` (`UsageSnapshot`: prompt/completion/total tokens) already captured and round-tripped | None currently known; provider-native usage objects are deliberately dropped (lossy by design, documented in README) |
| **Non-deterministic output** | This is the library's entire value proposition (freeze one sample, replay it) | Needs the caveat documented — item 6 above |
| **Embeddings** | Out of scope; embedding calls don't pass through `ChatClient`'s advisor chain | Item 12 above |
| **Tool / function calls** | **Verified against a real model.** `ToolDefinitionSnapshot` feeds the hash; `ToolCallSnapshot`/`ToolResponseSnapshot` (new in schema `"2"`) round-trip a message's own tool calls and tool results, not just the final response's; `VcrCacheKeyGenerator` now hashes a message's tool calls/responses explicitly instead of relying on `Message.getText()`, which is empty for both — the exact gap `OllamaToolCallingEndToEndTests` was written to close. `VcrScope`'s `INSIDE_TOOL_LOOP` vs `OUTSIDE_TOOL_LOOP` distinction is proven end to end: two model turns, two fixtures, zero network on replay, real `@Tool` method still re-invoked on replay | None currently known |
| **Structured output (`entity()`)** | **Verified against a real model.** The single-DTO round trip already worked (POJO conversion is client-side, post-advisor-chain). A real cache-key blind spot was found and fixed (schema `"3"`): `entity()`'s format instructions/JSON schema are spliced into the prompt by Spring AI's terminal `ChatModelCallAdvisor`, strictly after this library's advisor already hashed the un-augmented prompt, so two different target types sharing prompt text used to collide on one fixture. `VcrCacheKeyGenerator`/`VcrTrackMapper` now also canonicalize/capture `ChatClientAttributes.OUTPUT_FORMAT`/`STRUCTURED_OUTPUT_SCHEMA` from the request context, conditionally (silent when absent, so no prior fixture's hash changed) | None currently known |
| **PII / secrets in prompt text** | Transport secrets (API keys, bearer tokens) never reach a fixture because interception is above HTTP — genuinely solved, better than VCR.py's manual `sk-...` scrubbing. Secrets/PII *inside the message text itself* now have a safe redaction path too: `VcrFixtureRedactor` (item 1, done) | None currently known |

---

## Suggested order

Re-sequenced once Docker Desktop was started and the e2e proof became unblocked.

1. **Done** — Item 6 (document non-determinism caveat, in README "Limitations").
2. **Done** — Item 2 (`ApplicationContextRunner` auto-configuration slice tests +
   config metadata).
3. **Done (core)** — Item 4 (e2e proof): `OllamaEndToEndTests` against a real
   Testcontainers-managed Ollama container proves record → replay → zero additional
   network requests on the hit. Chosen to run before the redactor/normalizer work
   (reversing the previous plan) precisely because it's the safety net for that next
   change: once it exists, any future change to the hashing/fixture-write path can be
   checked against a real model, not just mocks. Two narrower scenarios from the
   original scope (`REPLAY_ONLY` miss without touching the container, and the
   `INSIDE_TOOL_LOOP`/`OUTSIDE_TOOL_LOOP` counting-`@Tool` proof) are not yet added —
   tracked as a small follow-up in the same test class, not a blocker.
4. **Done** — Item 1 (`VcrFixtureRedactor`). Hard constraints from the acceptance bar were
   met and tested, not just asserted: `VcrFixtureRedactorTests#noRedactorMeansUnchangedFixture`
   proves the pre-existing constructor path and the new redactor-aware one (given an
   empty list) produce identical fixtures, and a companion characterisation test
   (`VcrCacheKeyGeneratorTests#hashIsPinnedForKnownInputs`) pins literal hash values
   independent of this feature. The hook is opt-in (default `List.of()`) and
   structurally unable to change the hash (enforced in `DeterministicVcrAdvisor`, not
   left to trust).
5. **Done** — Item 3 (`@Vcr(mode = ...)` escape hatch). Design note compared all four
   `DISPATCH_PROMPT.md` options against three questions (sealed by default? whole-suite
   abuse risk? surface area?) and recommended the JUnit 5 extension; implementation
   matched the recommendation. `VcrModeExtensionTests` proves the override works, that
   it does not survive into a later unannotated test (ordered explicitly so this is a
   real proof), that it still runs the full record path including a registered
   redactor, and class-level vs. method-level precedence.
6. **Done, verified on a real runner** — Item 5 (CI workflow). `.github/workflows/ci.yml`
   is live at <https://github.com/rifatcakir/spring-ai-test-tools/actions>; both the `test`
   job (every push/PR) and the scheduled `e2e` job have completed successfully on
   GitHub-hosted infrastructure, not just a local dry run.
7. **Build side done** — Item 9 (publishing). What's left is entirely the maintainer's
   own action (namespace verification, GPG key, Sonatype token, the real release
   commands) per `docs/PUBLISHING.md` — not a further design or code item.
8. Items 7–8 (diagnostics) — opportunistic, whenever convenient.
9. Items 11–13 — not started without a dedicated design note each, per the table above.
   Item 10 (streaming) is now **done**, shipped as R3; item 12's embedding case shipped
   as R4 — both tracked in "The layered roadmap" section above rather than left
   open-endedly parked.

This list predates "The layered roadmap" section above and only covers Recorder-layer
work — it's kept as the historical record of how v0.1.0's Recorder feature set actually
got built. For anything beyond Recorder (Assertions, Evaluator), "The layered roadmap"
section is the current plan.

---

## Brainstorm

One rougher idea the maintainer wants to keep thinking through out loud — a lambda-based
callback/hook system, broader than the single `VcrFixtureRedactor` SPI that shipped from
it. No decision yet; written up separately so it doesn't get mistaken for a committed
roadmap item. See `docs/BRAINSTORM.md`.

The batch-verification idea that used to live alongside it in `docs/BRAINSTORM.md` is no
longer just a brainstorm — it's tracked as **E3** in "The layered roadmap" section above,
scheduled after E1 proves out the single-judge-call pattern. `docs/BRAINSTORM.md`'s
analysis of its risks (context contamination, traceability, the recursion/caching
question) still stands and should be read before E3 starts; only its status changed, from
undecided to "scheduled, but blocked on E1 and its own design note."
