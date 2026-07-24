# Project status

Last updated: 2026-07-24 (R3 streaming) · Version `0.1.0`

## Rename: `spring-ai-test-vcr` → `spring-ai-test-tools`

The Maven `artifactId` is now `spring-ai-test-tools` (groupId unchanged:
`io.github.rifatcakir`); the Java package root is now
`io.github.rifatcakir.springai.testtools`, with every class that used to live directly
under `...springai.vcr` now under `...springai.testtools.recorder` instead — no class was
renamed, only the package path. The GitHub repository was **also** renamed to match, from
`rifatcakir/spring-ai-test-vcr` to `rifatcakir/spring-ai-test-tools` (GitHub redirects the
old name automatically); `pom.xml`'s `url`/`scm` and the local `origin` remote both point
at the new name directly.

This rename exists because the project is no longer scoped to just record/replay. See
`docs/VISION.md` for the three-layer architecture this is now the foundation of: most of
this codebase, everything described below except the "A1"/"A2"/"E1" notes further down,
is the **Recorder** layer. `Assertions` now has real code for both its planned items
(A1: tool-call/JSON/finish-reason assertions; A2: embedding-backed semantic similarity).
`Evaluator` needed no new code at all — Spring AI's own `RelevancyEvaluator`/
`FactCheckingEvaluator` already work once wired through a Recorder-backed
`ChatClient.Builder` (E1, see below) — see `docs/VISION.md` for why the Recorder has to
exist first for anything built on top of it to be usable in CI at all.

## Current state

Core architecture scaffolded and now proven end-to-end. **`mvn test` is green (179/179)**,
plus sixteen real Testcontainers + Ollama integration tests (excluded from the default run,
verified separately via `mvn test -Pintegration-test`) that prove the library's actual
reason to exist: record on a real cache miss, replay on a hit, zero additional network
calls on the hit — one for a plain call, one for a two-turn tool-calling round trip, one
for structured output (`entity()`), one for a second `ChatModel` implementation, one for
`EmbeddingModel`, five for Spring AI's own `RelevancyEvaluator`/`FactCheckingEvaluator`
(record/replay for each, a changed-input test for each proving a stale verdict is
never replayed, and one proving `BYPASS` mode always reaches the model even when a
matching fixture exists — E2, see below), two for A2's semantic-similarity assertions
(a genuine paraphrase passes and an unrelated sentence doesn't, replay makes zero additional requests — see
below), and two for R3's streaming record/replay (a plain-text stream and a real streamed
tool-calling round trip, both asserted chunk-for-chunk, not just on the aggregated text —
see below). Three real bugs were found and fixed getting the unit tests
green — see "Bugs found on first compile" below. The rest of "Known risks" (the
unverified specifics list) still applies except where superseded by the e2e tests above.

**"Provider independent" is now empirically proven at the implementation level, not just
claimed.** Every e2e test until now ran against `OllamaChatModel` only.
`OpenAiViaOllamaEndToEndTests` exercises `OpenAiChatModel` instead — built in Spring AI
2.0 on the official OpenAI Java SDK (`com.openai.client.OpenAIClient`, an OkHttp-based
stack), architecturally distinct from `OllamaChatModel`'s `RestClient`-based `OllamaApi`,
pointed at Ollama's own OpenAI-compatible endpoint so no paid API key or new model was
needed. Two things proven: record/replay works correctly through this second
implementation on its own; and, the critical proof, a fixture recorded through the
*native* Ollama client replays byte-for-byte identical through the *OpenAI-SDK* client, at
zero network cost. This confirms `VcrCacheKeyGenerator` genuinely does not encode which
`ChatModel` implementation or wire protocol is in use (it only ever reads `ChatOptions`
interface getters, never `instanceof OllamaChatOptions`/`OpenAiChatOptions`) — the correct
behavior, reasoned through before writing any code: which Java class or transport reaches
a model cannot change what that model computes given the same model name and parameters,
so it must not be able to bust the cache. **No production code changed** to make this
pass. Honest scope limit, not overclaimed: both provider paths in this test still talk to
the same underlying `llama3.2:1b` weights via the same Ollama instance — this proves
implementation/transport independence, not independence from an actually different model
vendor (a real OpenAI/Anthropic/Bedrock account remains unverified) — see `docs/VISION.md`
for the full caveat.

**A real cross-platform hash-instability bug was found and fixed, discovered by actually
pushing to a real Linux CI runner, not hypothesized.** Setting up CI for the sibling
`spring-ai-test-tools-example` project surfaced it: two of that project's committed
fixtures (the tool-calling one and the structured-output one) failed to replay on a Linux
GitHub Actions runner with a genuine `VcrCacheMissException`, even though nothing about the
request had changed. Root cause: a tool's `inputSchema` and an `entity()` call's format
instructions/JSON schema are rendered by Jackson's pretty printer, which embeds the
*recording* JVM's own `System.lineSeparator()` — `\r\n` on Windows (where those fixtures
were originally recorded), `\n` on Linux/macOS. `VcrCacheKeyGenerator`'s own escaping logic
was never wrong — it faithfully hashed whatever text it was given — but that input text
itself was silently platform-dependent, so the same logical schema hashed differently
depending on which OS recorded it. Confirmed precisely: the committed fixture's
`canonicalRequest` contained a literal escaped `\r\n`; the CI failure log showed the
Linux-regenerated canonical request using `\n` only for the identical schema.

Fixed by bumping `VcrTrack.CURRENT_SCHEMA_VERSION` to `"4"` — additive in the sense that
mattered (no field added or removed, so a `"3"` fixture still deserializes unchanged), but
still a real bump because the canonicalization *formula* changed, which is exactly what
this version number exists to signal. Both `VcrCacheKeyGenerator` (for the hash) and
`VcrTrackMapper` (for the stored, human-reviewable `tools[].inputSchema`/
`structuredOutput.{format,jsonSchema}` fields, kept consistent with what was actually
hashed) now collapse `CRLF`/lone `CR` to `LF` in schema/format text before either uses it.
Deliberately scoped to schema/format text only — message text and the model's own response
content are untouched, since those are not generated by this library's own toolchain and a
line-ending difference in them is not provably meaningless the way it is in an
auto-rendered JSON schema; see `VcrCacheKeyGenerator#normalizeLineEndings`'s Javadoc for
the full reasoning. New golden hash tests pin that a schema pretty-printed with CRLF vs. LF
now hashes identically; every prior golden hash test (none of which involve embedded line
breaks) is unchanged, verified rather than assumed. The two affected example-project
fixtures were re-recorded against real Ollama and now contain `\n` only — confirmed by
reading the raw bytes, not assumed — and the example project's CI is green on Linux as a
result (not merely locally on Windows).

**A1 (Assertions layer) is built: `io.github.rifatcakir.springai.testtools.assertions`,
the first code in a layer that had none before.** `VcrAssertions.assertThat(...)` gives
fluent, AssertJ-idiomatic, purely deterministic checks against a `ChatClientResponse`/
`ChatResponse` — no model call is ever made to evaluate an assertion, and Recorder needed
no changes to support it. Three decisions were made explicitly by the project owner
before implementation, documented in full in `docs/A1-ASSERTIONS-PRD.md`: (1) JSON
"schema conformance" ships in v1 as Jackson-tree-based `hasJsonField`/`hasJsonFieldOfType`
(RFC 6901 JSON Pointer, no new dependency) rather than pulling in a JSON Schema validator
— a deliberate choice to not add a new transitive dependency to every consumer's
classpath before a real need for full schema validation is demonstrated; (2) two entry
points (`assertThat(ChatClientResponse)`, `assertThat(ChatResponse)`) with both
exact-match (`Map<String,Object>`) and partial/custom (`Consumer<Map<String,Object>>`)
tool-call-argument matching; (3) `hasToolCall(...)`'s real scope limit is documented, not
worked around: confirmed via `ToolCallingAdvisor`'s bytecode that it fully resolves and
consumes a tool call internally before returning a response, so this assertion is
meaningful against a raw `ChatModel#call(Prompt)` result or a pre-loop response, not
against the final answer of a normal `ChatClient.tools(...).call()` — the exact reason
`ToolCallingRecordReplayTest` (below) reads fixture files directly instead of asserting
on the response object. 39 new tests (`ChatResponseAssertTests`, 28;
`ChatClientResponseAssertTests`, 11), each assertion type covered by both a passing case
and a failing case whose message is checked for actual content — proving, for example,
that `hasToolCall(name, Map)` correctly treats two differently-serialized-but-equal
argument JSON strings as equal, which a naive substring check never reliably did.
Showcased in the example project's `AssertionsShowcaseTest` against two already-committed
fixtures (the tool-calling fixture's first turn, and the structured-output fixture) —
zero new recordings, zero Ollama/Docker involvement.

**R4 (`EmbeddingModel` interception) is built:
`io.github.rifatcakir.springai.testtools.recorder.embedding`, the item A2 (semantic/
embedding assertions) was blocked on.** Confirmed via bytecode, not assumed, that
`EmbeddingModel` has no advisor chain at all — unlike `ChatClient`, there is nothing to
attach a `CallAdvisor` to. Interception is instead a `BeanPostProcessor`
(`VcrEmbeddingModelBeanPostProcessor`) that wraps the context's `EmbeddingModel` bean
directly in `VcrEmbeddingModel`, gated by its own `spring.ai.test.vcr.embedding.enabled`
flag, independent of the chat `spring.ai.test.vcr.enabled` flag in both directions
(explicitly tested). A new, independent fixture type, `VcrEmbeddingTrack` (schema version
`"1"`, its own counter, unrelated to `VcrTrack`'s), rather than forcing an embedding
request/response shape onto the chat fixture. One real decision needed explicit sign-off
before implementation: whether the fixture stores the full vector or a hash of it —
resolved as "must be the full vector" (a hash is one-way and could never replay a usable
vector, defeating the entire reason R4 exists), with the *rendering* question (risking a
multi-thousand-line file, one float per line) decided in favor of Jackson's own default
pretty-printer rather than a custom compact serializer. That prediction of a
multi-thousand-line file turned out to be wrong once a real fixture was recorded: Jackson
3 renders a `float[]` on a single compact line, not one element per line the way it would
a `List`, so the committed fixture is 22 lines, not thousands — corrected in
`docs/R4-EMBEDDING-INTERCEPTION.md` and this library's own Javadoc once observed, rather
than left as a stale prediction. Verified end to end against real `llama3.2:1b`
(`OllamaEmbeddingEndToEndTests`) — the only model already available in this environment,
confirmed beforehand (via a real `/api/embed` call) to answer embedding requests despite
not being a dedicated embedding model, so no new model was pulled: record on a miss,
replay on a hit with zero additional HTTP requests, and the replayed vector proven
bit-for-bit exact via `float[]` array equality, not "same length." Showcased in the
example project's `EmbeddingRecordReplayTest` — Docker-free like every other fixture in
that project once recorded.

**Spring AI's own official `Evaluator` mechanism — `RelevancyEvaluator` and
`FactCheckingEvaluator` — is now confirmed, not just argued, to be Recorder-backed with
zero new code.** Researched before writing anything: `mvn dependency:tree` confirmed
`org.springframework.ai.evaluation.Evaluator` (`spring-ai-commons`) and
`RelevancyEvaluator`/`FactCheckingEvaluator` (`spring-ai-client-chat`) are both already
transitive dependencies of this project at the same `2.0.0` this project is pinned to —
no new dependency, no version conflict. `javap -c` bytecode disassembly of both
evaluators' `evaluate()` methods confirmed they do nothing more internally than
`chatClientBuilder.build().prompt().user(...).call().content()` — an entirely ordinary
`ChatClient` call, structurally identical to any other this library already intercepts.
That analysis was then verified for real, not left as a bytecode argument:
`OllamaEvaluatorEndToEndTests` builds a `RelevancyEvaluator` and a `FactCheckingEvaluator`
from the same `ChatClient.Builder` this library's `ChatClientBuilderCustomizer` already
attaches `DeterministicVcrAdvisor` to, against real `llama3.2:1b`, and confirms: the first
`evaluate()` call reaches the real model and records a fixture, the identical second call
makes zero additional HTTP requests, and the replayed `EvaluationResponse`'s verdict
(`isPass()`, `getScore()`) is exactly what was recorded. Two further tests close the
`docs/BRAINSTORM.md` "recursion" worry for this mechanism specifically — a judge's
verdict must not be frozen forever against a response that has since changed: changing
the judged response (`RelevancyEvaluator`) or claim (`FactCheckingEvaluator`), same query
and context otherwise, is confirmed to reach the model again and write a second, separate
fixture, not replay the first verdict — proven by counting real HTTP requests and real
fixture files, not by reading the prompt template's source and assuming it embeds the
judged output. **No production code changed or was added to this library to make this
true** — `Evaluator` is Spring AI's own interface,
and wiring it through an already-customized builder is a usage pattern this project's
existing design already supported, not a new capability that had to be built. This
reframes the Evaluator layer (E1/E2 in `docs/ROADMAP.md`) from "build an LLM-as-judge
mechanism from scratch" to "prove, document, and showcase that Spring AI's own official
evaluators already work this way" — see `docs/VISION.md`'s Layer 3 section for the full
reasoning.

**A2 (embedding/semantic assertions) is built: `usingEmbeddingModel(EmbeddingModel)`
plus `isSemanticallySimilarTo(String[, double])`/`isSemanticallySimilarToAnyOf(...)` on
`VcrAssertions`.** Checked, not assumed, before writing anything: no Spring AI jar
already on this project's classpath has a cosine-similarity helper (only
`VectorStoreSimilarityMetric`, an enum of metric *names*, and `EmbeddingUtils`, which
only converts array/list types) — so cosine similarity is computed directly, roughly ten
lines, no new dependency. The entry-point design question (where does the
`EmbeddingModel` come from, since `assertThat(response)` only carries the response) was
resolved as a fluent configuring method, `usingEmbeddingModel(...)` returning `this`,
mirroring AssertJ's own `usingComparator(...)` idiom, rather than a second `assertThat`
overload or global mutable configuration — see `docs/A2-SEMANTIC-ASSERTIONS-PRD.md`
section 2 for the trade-off table. `usingEmbeddingModel(...)` warns (does not refuse)
when given an `EmbeddingModel` that is not a `VcrEmbeddingModel` (R4), naming the
determinism risk without blocking a deliberate live/integration use. Verified end to end
against real `llama3.2:1b`, not just unit-tested against a hand-rolled stub: a genuine
paraphrase passes, an unrelated sentence does not, and a second identical assertion makes
zero additional HTTP requests — both the response text's embedding and the expected
text's are independently Recorder-backed. **A real, unplanned finding surfaced while
writing that e2e test, not merely designed against in the abstract:** direct `/api/embed`
calls against several sentence pairs showed `llama3.2:1b`'s embeddings compress real
paraphrase similarity (0.93–0.95) and unrelated-sentence similarity (0.66–0.72) into a
narrow, uniformly-high range — high enough that the library's own `0.7` default
threshold does not reliably separate them for this specific model (a known property of
embeddings extracted from an LLM's hidden states rather than a model purpose-trained for
embedding separation). The default is kept as a reasonable middle point for a dedicated
embedding model, but this is documented plainly in the README/PRD, and the e2e test
itself needed an empirically-calibrated `0.85` to demonstrate the distinction cleanly —
exactly the "read reality, don't assume" discipline this project applies everywhere else.

**E2 (evaluation modes) is built: `docs/E2-EVALUATION-MODES-PRD.md`.** Reframed from
the original "demonstrate both official evaluators" plan (already covered by E1) to the
actual differentiator this layer offers over Spring AI's own bare `Evaluator`, which has
no replay concept at all: **the same `RelevancyEvaluator`/`FactCheckingEvaluator` runs in
either of two modes, with zero new mechanism, purely by which `VcrMode` its
`ChatClient.Builder` was built with** — `REPLAY_ONLY` for deterministic CI, or
`BYPASS`/`RECORD_ALWAYS` for a deliberate, separate live drift/quality check. Toxicity
was checked, not assumed, to be genuinely absent from Spring AI 2.0.0 (every jar this
project depends on searched for `evaluat`/`toxic`/`hallucin` class names — only
`RelevancyEvaluator`/`FactCheckingEvaluator` exist) and documented as a buildable,
unbuilt pattern (`FactCheckingEvaluator`'s own shape) rather than built speculatively.
`OllamaEvaluatorEndToEndTests` gains a test proving `BYPASS` reaches the real model on
every call even when a matching fixture already exists on disk — confirmed, not argued
from `DeterministicVcrAdvisor`'s source alone, though that source (the `BYPASS` branch
returns before ever computing a hash or touching the store) already guaranteed it. The
one real decision (`docs/E2-EVALUATION-MODES-PRD.md` section 3): the main library's
existing nightly/`workflow_dispatch` `e2e` CI job now also runs this test class (zero new
CI infrastructure, same established real-Ollama pattern); the example project, whose own
design deliberately needs no Docker/Ollama for its default run and has no scheduled job
at all, gets an integration-tagged demonstration instead (`@Vcr(mode = VcrMode.BYPASS)`,
its own existing escape hatch) rather than new scheduled CI infrastructure that would
contradict that project's own stated identity.

**R3 (streaming record/replay) is built: `docs/R3-STREAMING-PRD.md`.** `.stream()` used
to pass straight through live, uncached — now `DeterministicVcrAdvisor` implements
`StreamAdvisor` alongside `CallAdvisor` on the same class (mirroring `ToolCallingAdvisor`'s
own precedent of one class, multiple advisor interfaces, one shared `getOrder()`), backed
by a new, independent fixture type, `VcrStreamTrack`, that stores the raw chunk sequence a
live stream produced as the actual replay source of truth — required so a consumer's own
chunk-boundary handling sees the same input live or replayed — plus a computed,
review-only `aggregateText` (and aggregate finish-reason/tool-call summary), mirroring how
`VcrTrack.canonicalRequest` is already stored for human review only, never read back for
replay. `VcrCacheKeyGenerator.generateForStream(...)` reuses the exact same
canonicalization formula as the blocking path, differing only in one header line, so a
`.call()` and a `.stream()` for the identical prompt can never collide on one fixture file
— confirmed by all 19 pre-existing golden hash tests passing **unchanged**, plus 3 new
tests proving the stream/call hash separation itself. The hardest open design question —
whether a real provider ever fragments a streamed tool call's `arguments` across multiple
chunks — was resolved empirically before any fixture-format code was written: five
separate observations of raw `curl` against Ollama's native `/api/chat` streaming endpoint
(`llama3.2:1b`, a real `tools` array) showed every genuine tool call arriving whole — id,
name, and the complete `arguments` JSON object — in exactly one NDJSON line, never split
across lines. So streamed tool-call support shipped in v1 (not deferred to a v2, per the
maintainer's decision once the diagnosis confirmed it was safe) with zero fragment-merging
logic, since storing raw chunks verbatim already handles a whole-per-chunk tool call with
no special code. No artificial inter-chunk delay on replay (`Flux.fromIterable`, per this
project's "deterministic tests must not depend on timing" philosophy). Auto-configuration
wires streaming automatically whenever `spring.ai.test.vcr.enabled=true` — no separate
toggle, since `.call()` and `.stream()` are two facets of one advisor. Verified against a
real model, not just designed: `OllamaStreamingEndToEndTests` proves both a plain-text
stream and a genuine streamed tool-calling round trip record and replay chunk-for-chunk
with zero additional HTTP requests — counted at the `WebClient` layer specifically, since
Ollama's streaming path does not go through the same `RestClient` the blocking path uses,
a real gap the first version of this e2e test caught and fixed.

**Structured output (`ChatClient...entity(Class)`) is now verified against a real model,
and a real cache-key blind spot was found and fixed, same discipline as tool calling.**
The single-DTO round trip already worked with zero new code (POJO conversion is pure
client-side text parsing, independent of Recorder). But `entity()`'s format
instructions/JSON schema are spliced into the prompt by Spring AI's own terminal advisor,
`ChatModelCallAdvisor` (`getOrder() == Integer.MAX_VALUE`), strictly *after*
`DeterministicVcrAdvisor` already computed the hash from the un-augmented `Prompt` —
confirmed by reading both classes' bytecode, not guessed. So two structurally different
`entity()` target types sharing identical prompt text used to hash identically and
silently replay each other's fixture; reproduced end to end against a real model before
the fix (`OllamaStructuredOutputEndToEndTests`), then confirmed fixed after it. Fixed by
bumping `VcrTrack.CURRENT_SCHEMA_VERSION` to `"3"`: `VcrCacheKeyGenerator` and
`VcrTrackMapper` gained a `(Prompt, Map<String,Object>)` overload that also canonicalizes/
captures `ChatClientAttributes.OUTPUT_FORMAT`/`STRUCTURED_OUTPUT_SCHEMA` from
`ChatClientRequest.context()` — conditionally, so a request that never called `entity()`
adds nothing to the canonical form and keeps its exact pre-existing hash. Every prior
golden hash test is unchanged (verified, not assumed); new golden hash tests pin the
structured-output canonicalization specifically, and a fast, Docker-free test
(`DeterministicVcrAdvisorStructuredOutputTests`) proves the same fix against a real
`ChatClient`/`BeanOutputConverter` pipeline wired to a fake `ChatModel`.

**Tool/function-call support is now verified against a real model, not just designed.**
Building `OllamaToolCallingEndToEndTests` surfaced a real gap: `Message.getText()` is
empty both for an assistant's tool-calls-only turn and for any `ToolResponseMessage`, and
`VcrCacheKeyGenerator`/`VcrTrackMapper` relied on `getText()` alone — so two different
tool calls, or two different tool results, canonicalized identically and could replay the
wrong turn's fixture under `VcrScope.INSIDE_TOOL_LOOP` (the default `OUTSIDE_TOOL_LOOP`
never sees these message types at all, so was never at risk). Fixed by bumping
`VcrTrack.CURRENT_SCHEMA_VERSION` to `"2"` (additive — a `"1"` fixture still deserialises;
verified with a dedicated backward-compatibility test) and adding `ToolCallSnapshot`/
`ToolResponseSnapshot` capture to a message's own tool calls/responses, plus explicit
canonicalization of both in the hash. Existing golden hash tests (ordinary, non-tool-call
prompts) were confirmed unaffected; new golden hash tests pin the tool-call and
tool-response canonicalization specifically, and a unit test proves two different tool
calls now hash differently without needing a real model for that specific assertion.

A second SPI, `VcrFixtureRedactor`, now exists alongside `VcrPromptNormalizer` — see
"Redacting fixture content" in the README and `VcrFixtureRedactor`'s Javadoc. It redacts
what gets written to a committed fixture without ever being able to change the cache key
a request resolves to; the guarantee is enforced in code (`DeterministicVcrAdvisor`
always re-applies the originally-computed `hash`/`schemaVersion` after every redactor
runs, ignoring whatever a redactor returns for those two fields), not just documented.

A `@Vcr(mode = ...)` JUnit 5 annotation now exists too — see "Escaping REPLAY_ONLY for one
test" in the README. It lets a single test override the effective `VcrMode` (typically to
`BYPASS`, to reach a real model) via a `ThreadLocal` cleared automatically after every
test, without weakening `REPLAY_ONLY` for the rest of a CI run.

A GitHub Actions workflow now exists at `.github/workflows/ci.yml` and is **live and
green** at <https://github.com/rifatcakir/spring-ai-test-tools/actions> — the repo is
public, pushed, and both the per-PR unit test job and the scheduled Ollama e2e job have
completed successfully on a real hosted runner. See "Next tasks" item 7 for details.

The build side of publishing is prepared — `LICENSE`, Central-required `pom.xml`
metadata (including a confirmed repo URL and a listed contact email), a `release` Maven
profile — and `mvn -Prelease package` was verified to actually produce a clean javadoc
jar. **Nothing has been published or credentialed**; see `docs/PUBLISHING.md` for exactly
what the maintainer still has to do by hand.

## Bugs found on first compile (fixed)

1. `VcrTrackStore.shortHash(String)` was package-private but called from
   `advisor.DeterministicVcrAdvisor` — compile error. Made `public static`.
2. `VcrCacheKeyGenerator.canonicalize()` built `stops` as `List.of()` (immutable) when
   options or stop sequences were null, then called `.sort()` on it unconditionally —
   `UnsupportedOperationException` on every prompt without options. This broke 21 of 26
   tests. Fixed by using `new ArrayList<>()` in the null branch too.
3. `VcrCacheKeyGenerator.NULL_TOKEN` contained a literal embedded NUL byte instead of a
   space in the source file — `" \0null"` rather than `" null"`. Almost certainly an
   artifact of how the file was originally authored/saved, not a typo. Fixed by
   rewriting the byte. Worth a `grep -RP '\x00'` sanity check on any other file authored
   the same way before trusting it blindly.

No Spring AI 2.0.0 API signature mismatches turned up — every type/method name in
`CLAUDE.md`'s verified-API table held. All three bugs above were internal, not against
the real jars, so the table needed no correction.

## What exists

```
pom.xml                                   Maven, Java 21, Boot 4.0.0 + Spring AI 2.0.0 BOMs
CLAUDE.md                                 agent instructions + verified API reference table
README.md                                 user-facing docs
docs/STATUS.md                            this file

src/main/java/io/github/rifatcakir/springai/testtools/recorder/
  VcrMode.java                            RECORD_OR_REPLAY | REPLAY_ONLY | RECORD_ALWAYS | BYPASS
  VcrScope.java                           OUTSIDE_TOOL_LOOP | INSIDE_TOOL_LOOP
  VcrPromptNormalizer.java                SPI — the VCR.py "request matcher" equivalent
  RegexPromptNormalizer.java              ISO_DATE, ISO_DATE_TIME, UUID, EPOCH_MILLIS, of()
  VcrCacheMissException.java              verbose, actionable REPLAY_ONLY failure
  key/VcrCacheKey.java                    record(hash, canonicalRequest)
  key/VcrCacheKeyGenerator.java           hand-assembled canonical form + SHA-256
  track/VcrTrack.java                     the on-disk fixture DTO
  track/VcrTrackMapper.java               ChatResponse <-> VcrTrack, both directions
  track/VcrTrackStore.java                Jackson 3 file IO, atomic writes
  advisor/DeterministicVcrAdvisor.java    implements CallAdvisor, short-circuits the chain
  autoconfigure/VcrProperties.java        spring.ai.test.vcr.*
  autoconfigure/SpringAiVcrAutoConfiguration.java

src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

src/test/java/.../key/VcrCacheKeyGeneratorTests.java        10 tests
src/test/java/.../advisor/DeterministicVcrAdvisorTests.java 10 tests
src/test/java/.../track/VcrTrackStoreRoundTripTests.java     6 tests
```

## Design decisions already made

| Decision | Choice | Rationale |
|---|---|---|
| Advisor base type | `CallAdvisor`, implemented directly | `BaseAdvisor` always calls the chain; VCR must be able to not call it |
| Advisor placement | Configurable, default `OUTSIDE_TOOL_LOOP` | outside = one fixture per interaction, fastest, but `@Tool` methods don't run on replay |
| Cache layout | one JSON file per SHA-256 | O(1) filename lookup; no cassette scanning |
| Volatile prompts | `VcrPromptNormalizer` applied pre-hash | inverted VCR.py matcher: canonicalise the request, let the hash match |
| Serialisation | `VcrTrack` DTO, not MixIns | MixIns would still be coupled to Spring AI's internal shape across versions |
| Streaming | `StreamAdvisor` on the same advisor class, `VcrStreamTrack` fixture | see R3 above; raw chunks are the replay source of truth, never a single-chunk fake |
| Build | Maven | user preference |

## Known risks — read before doing anything

### 1. The code has never been compiled

The environment it was authored in had no Maven and only JDK 11. Every type, method and
Maven coordinate referenced was verified by unpacking the published 2.0.0 jars and
grepping the class constant pools, and two real errors were caught and fixed that way
(`ToolCallingChatOptions.getToolNames()` removal, `ChatResponseMetadata.get(String)`
absence). But **constant-pool presence does not prove generic signatures, parameter order,
or nullability**. Expect a handful of compile errors on first build. This is the first
task.

### 2. Unverified specifics, ranked by likelihood of breaking

1. `ChatGenerationMetadata.builder().finishReason(String)` — `finishReason` confirmed on
   the Builder class, exact parameter type not confirmed.
2. `ChatResponseMetadata.Builder.keyValue(String, Object)` — confirmed present via
   javadoc, not exercised.
3. `ChatResponse.builder().generations(...)` when the generations list is empty.
4. ~~`ChatClientBuilderCustomizer`'s functional method shape~~ **Confirmed** — exercised
   for real against a live `ChatClient.Builder` in `OllamaEndToEndTests`; the assumed
   `(ChatClient.Builder) -> void` shape is correct.
5. Whether `spring-boot-dependencies:4.0.0` actually manages
   `tools.jackson.core:jackson-databind`. If not, pin an explicit version in `pom.xml`.
6. `ToolDefinition` is confirmed to have `inputSchema` and `description`; `name` was not
   separately confirmed (it is almost certainly `name()`).
7. `spring-boot-dependencies:4.0.0` manages `testcontainers.version=2.0.2` via an
   imported `testcontainers-bom`, but that version does not exist as a published,
   downloadable artifact (checked directly against Maven Central: the newest real
   `org.testcontainers` release is `1.21.3`). `pom.xml` pins
   `org.testcontainers:{testcontainers,junit-jupiter,ollama}` to `1.21.3` explicitly for
   this reason — remove that override once the BOM-managed version is real.

### 3. ~~Streaming is deliberately absent~~ Resolved — R3, see "Current state" above

`.stream()` calls now record and replay chunk-for-chunk via `VcrStreamTrack`, a dedicated
fixture type — not a bolt-on to `VcrTrack`, and not a single-chunk fake standing in for a
real stream. Chunk boundaries and streamed tool-call fragmentation were both resolved
empirically (see "Current state" above and `docs/R3-STREAMING-PRD.md`); inter-chunk timing
was a deliberate design decision, not solved: replay never reproduces the original
wall-clock delay between chunks, by design, per this project's "deterministic tests must
not depend on timing" philosophy.

### 4. Scope limits

`ChatClient` calls and `EmbeddingModel` calls are cached (the latter via R4, opt-in
separately — see "Current state" above). Image, audio and moderation models do not pass
through either mechanism and are not cached. `EmbeddingModel#embed(Document)`
specifically is also not cached even though the interface is wrapped — a deliberate v1
scope limit, documented in `VcrEmbeddingModel`'s own Javadoc, since it is a RAG/
vector-store ingestion concern with its own id/metadata shape, not the "embed this text
and assert something" scenario R4 exists for.

## Next tasks, in order

See `docs/ROADMAP.md` for the full prioritized, sized, prior-art-informed version of
this list — kept here in short form so this file stays a quick "what's true right now"
read.

1. ~~**Make it compile and go green.** `mvn clean test`.~~ **Done** — see "Bugs found on
   first compile" above.
2. ~~**Auto-configuration slice test.**~~ **Done** — `SpringAiVcrAutoConfigurationTests`
   (`ApplicationContextRunner`): absent when disabled, all four collaborator beans
   present and correctly ordered when enabled, explicit `order` overrides scope,
   `@ConditionalOnMissingBean` verified for all four bean types, and registered
   `VcrPromptNormalizer` beans confirmed to reach the generated `VcrCacheKeyGenerator`.
3. ~~**`additional-spring-configuration-metadata.json`**~~ **Done** — every
   `spring.ai.test.vcr.*` property now has a description and default value in IDE
   completion.
4. ~~**Real end-to-end test.**~~ **Done** — `OllamaEndToEndTests`
   (`@Tag("integration")`, run via `mvn test -Pintegration-test`): a real Testcontainers-
   managed Ollama container, real `llama3.2:1b` model (the smallest already available in
   this environment), first call is a genuine cache miss that records one fixture,
   identical second call is a hit — and an HTTP request counter wired into the
   `RestClient` underneath `OllamaApi` proves zero additional network requests on the
   hit, rather than assuming it from the response text alone. Docker Desktop needed to be
   started first; once it was, this was unblocked and completed the same session.
5. ~~**Separate the cache-key normalizer from fixture redaction.**~~ **Done** —
   `VcrFixtureRedactor` (new SPI, `src/main/java/.../VcrFixtureRedactor.java`): applied
   in `DeterministicVcrAdvisor` only on the write path, after the hash is already
   computed; `hash()`/`schemaVersion()` on whatever a redactor returns are ignored and
   re-applied from the original track, enforced in code rather than merely documented.
   Collected the same way `VcrPromptNormalizer` already is (auto-configured
   `List<VcrFixtureRedactor>`, `Ordered` sequence). Five behavioural tests in
   `VcrFixtureRedactorTests` cover: bit-identical fixtures with no redactor registered
   (new constructor path vs. the pre-existing one), redaction never reaching a live
   response or a replay, a redactor unable to forge a different cache key even trying
   to, multiple redactors composing in registration order, and a throwing redactor
   propagating with nothing written. A characterisation test
   (`VcrCacheKeyGeneratorTests#hashIsPinnedForKnownInputs`) now pins literal SHA-256
   values for known inputs as an additional regression guard, independent of this
   feature. Documented in the README under "Redacting fixture content" — the
   normalizer/redactor distinction (merges vs. never merges, hash vs. no-hash) was
   flagged as easy to confuse, so it gets a table and a worked example there.
6. ~~**Decide the `REPLAY_ONLY` escape hatch.**~~ **Done** — `@Vcr(mode = ...)`
   (`io.github.rifatcakir.springai.testtools.recorder.junit`), a JUnit 5 annotation + extension.
   Compared against the other three options from `DISPATCH_PROMPT.md` Task 4 in
   `docs/ROADMAP.md`'s design note; the `AdvisorParams`-style per-request override was
   rejected specifically because it would require the *application* code under test to
   opt in, which contradicts `SpringAiVcrAutoConfiguration`'s own "no test-only
   conditional in production code" principle. The override is a `ThreadLocal`
   (`VcrModeOverride`), consulted once at the top of `adviseCall` and cleared
   unconditionally in the extension's `afterEach`. Five tests in
   `VcrModeExtensionTests` prove: the override works, an unannotated test running right
   after an annotated one sees default (sealed) behavior (ordered explicitly with
   `@TestMethodOrder` to make this a real proof rather than an assumption), an override
   still runs the full record path including a registered redactor, and class-level vs.
   method-level precedence. Required one new *main*-scope (optional) dependency,
   `junit-jupiter-api` — see the design note for why that's necessary rather than a
   test-scope leak.
7. ~~**CI workflow.**~~ **Done, verified running green on a real GitHub Actions runner** —
   `.github/workflows/ci.yml`, live at
   <https://github.com/rifatcakir/spring-ai-test-tools/actions>. Two jobs: `test` (JDK 21,
   every push to `main` and every PR — `mvn test`, no Docker, ~30s on a hosted
   `ubuntu-latest` runner) fails if the working tree is dirty after the run (a whole-tree
   `git status --porcelain` check, not one scoped to a specific fixture path — see the
   file's comments for why that's deliberate given this repo currently commits zero
   fixtures of its own); `e2e` (the real Testcontainers + Ollama proof) runs only on a
   nightly schedule or `workflow_dispatch`, not on every PR, because pulling the model
   into a fresh container costs real time on a GitHub-hosted runner with no persistent
   Docker cache and would slow down the PR feedback loop this workflow exists to keep
   fast. Both the `test` job and the scheduled `e2e` job have completed successfully on
   the actual GitHub-hosted runner — this is no longer a local-only dry run; every
   previously-unverified item below has now been confirmed on real infrastructure.
8. ~~**Publishing.**~~ **Build side prepared, nothing published.** `LICENSE` (Apache-2.0
   — confirmed with the maintainer before adding; it was already what README/ROADMAP
   referenced, but license choice is hard to reverse so it wasn't picked unasked).
   `pom.xml` gained the Central-required metadata (`url`, `licenses`, `developers`,
   `scm`) and a `release` Maven profile (`-Prelease`) wiring
   `maven-source-plugin`/`maven-javadoc-plugin`/`maven-gpg-plugin`/
   `central-publishing-maven-plugin` (OSSRH's classic staging-repo flow is retired; this
   is its Sonatype-documented successor — versions confirmed against real Maven Central,
   not guessed). Isolated in its own profile: an ordinary `mvn test`/`mvn install` never
   needs a GPG key. **Verified**: `mvn -Prelease package -DskipTests` actually produces
   `-sources.jar` and `-javadoc.jar` with zero Javadoc errors (66 cosmetic "no comment"
   warnings, no failures) — the step most projects discover is broken only when they try
   to actually release. Full walkthrough, including exactly what the maintainer still
   has to do by hand (namespace verification, GPG key generation, Sonatype token,
   `settings.xml` credentials, the real release commands), in `docs/PUBLISHING.md`.
   **Not done, deliberately**: no GPG key generated, no credentials created or written
   anywhere, no real `mvn deploy` — all of that is the maintainer's own action, not
   something to do on their behalf. `CONTRIBUTING.md` also not started; folded into a
   future task rather than blocking this one.

## Resolved questions (kept for history)

- **Package name / groupId, confirmed.** Briefly shipped as `io.github.rifatcakira` /
  `io.github.rifatcakira.springai.vcr` — wrong from the start, a one-letter mismatch
  against the maintainer's actual GitHub account (`rifatcakir`, no trailing "a"). Caught
  before any publish, so the fix was a plain rename rather than a breaking change:
  every package, the groupId, `pom.xml`'s `url`/`scm`/`developers`, and every doc
  reference now consistently say `rifatcakir`. The GitHub repo lives at
  <https://github.com/rifatcakir/spring-ai-test-tools>, matching.

## Open questions for the maintainer

- Should `VcrTrack` record the *raw* prompt alongside the normalized one? It would make
  fixture review richer but reintroduces the secret-leak risk that normalizers exist to
  prevent. Currently: normalized only.
- Multi-module split (`spring-ai-test-vcr` core + `-spring-boot-starter`) or stay single
  module? Single module is right until there is a second consumer.
