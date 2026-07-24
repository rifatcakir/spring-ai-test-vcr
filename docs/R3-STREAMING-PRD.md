# R3 — Streaming Record/Replay: PRD

Status: **implemented and shipped.** Sized **L** in `docs/ROADMAP.md` for exactly this
reason — a real fixture-schema decision, not a mechanical extension of an existing
pattern the way R4/A2/E1/E2 turned out to be. Every Spring AI type below was confirmed
via `javap` against the jars already resolved on this project's classpath, not guessed.
**Both forks below (sections 3 and 6) were decided by the project owner, the section 6
empirical prerequisite was then run against a real model, and implementation followed —
see "Where this stands now" for the outcome.**

1. **Fixture schema (section 3): both.** `VcrStreamTrack` stores the raw chunk list as
   the actual replay source of truth, plus a computed, review-only `aggregateText` field
   — the recommended option, now confirmed rather than merely proposed.
2. **Tool-call fragment scope (section 6): included in v1, not deferred to a v2.** This
   is the opposite of what this PRD recommended, and it comes with a real prerequisite
   this PRD could not satisfy on its own: whether any provider (Ollama included) ever
   splits a tool call's `arguments` across multiple stream chunks was **not empirically
   confirmed** here — doing so would have required writing and running a real streaming
   tool-call test, out of scope for a design-only pass. **Before any fixture-format or
   merging code is written, that empirical diagnosis has to happen first** — the same
   "diagnose against a real model before touching the design" discipline every other
   capability in this project has already followed. This PRD is updated to describe what
   v1 must now cover, but the diagnosis step itself is new work, not yet done, and not
   started without a separate go-ahead (see the closing section).

## 1. The real streaming API shape, confirmed via bytecode

```
StreamingModel<TReq, TResChunk>            (spring-ai-model)
    Flux<TResChunk> stream(TReq)

StreamingChatModel extends StreamingModel<Prompt, ChatResponse>   (spring-ai-model)
    Flux<ChatResponse> stream(Prompt)       -- the one abstract method; every other
                                                stream(...) overload on this interface
                                                is a default routing through it

ChatClient.StreamResponseSpec              (spring-ai-client-chat)
    Flux<ChatClientResponse> chatClientResponse()
    Flux<ChatResponse>       chatResponse()
    Flux<String>             content()
```

**Each emission on the `Flux<ChatResponse>` is a full, independent `ChatResponse` —
not a lightweight "delta" type.** Confirmed against `OllamaChatModel.stream(Prompt)`:
each raw Ollama NDJSON stream line (`OllamaApi$ChatResponse`) maps 1:1 to one Spring AI
`ChatResponse` chunk. In practice a chunk's `AssistantMessage.getText()` carries a small
text delta (a few tokens, per Ollama's own streaming protocol — confirmed via direct
`/api/chat` stream inspection earlier in this project's own diagnostic work, not assumed
here), but nothing in the type system distinguishes "delta" from "full text" — a
consumer is expected to *aggregate* chunks to get the complete answer.

**Aggregation is Spring AI's own concern, not something a stream fixture needs to
replicate.** `org.springframework.ai.chat.model.MessageAggregator.aggregate(Flux<ChatResponse>,
Consumer<ChatResponse>)` (backing `ChatClientMessageAggregator`, which `ToolCallingAdvisor`
itself uses for its streaming path) does the accumulation: concatenates
`getText()` across chunks into a `StringBuilder` (with a separate `StringBuilder` for
"thinking"/reasoning chunks — `AssistantMessage.getMetadata().get("isThought")`, a
Spring AI 2.0 reasoning-model feature confirmed via bytecode, worth knowing about but
orthogonal to this PRD), sums usage tokens, and — the fact that matters most for section
6 below — **merges tool calls across chunks via a plain `List.addAll(...)`, not
index/id-aware fragment merging.** This means Spring AI's own aggregator does not
reconstruct a tool call whose `arguments` string arrives split across multiple chunks;
it only concatenates whatever complete-or-partial `ToolCall` objects each chunk's
`getToolCalls()` already contains. **This library's job is to record and replay the exact
chunk sequence a live call would have produced — not to re-implement or improve on
Spring AI's own aggregation** — so whatever downstream code a consumer already has
(`MessageAggregator`, `ToolCallingAdvisor`, or their own) sees the same input whether the
chunks are live or replayed, and behaves identically either way.

## 2. Interception point — a genuinely separate advisor interface, confirmed

`DeterministicVcrAdvisor` implements only `CallAdvisor` today, and `.stream()` passes
through live by design — this is a real, confirmed gap, not an assumption:

```
Advisor                                     (marker interface: getName(), getOrder())
    CallAdvisor extends Advisor
        ChatClientResponse adviseCall(ChatClientRequest, CallAdvisorChain)
    StreamAdvisor extends Advisor
        Flux<ChatClientResponse> adviseStream(ChatClientRequest, StreamAdvisorChain)

StreamAdvisorChain extends AdvisorChain
    Flux<ChatClientResponse> nextStream(ChatClientRequest)
    List<StreamAdvisor> getStreamAdvisors()
    StreamAdvisorChain copy(StreamAdvisor)
```

**`StreamAdvisor` is a separate interface with its own chain type — intercepting
streaming requires implementing it, it does not come for free from `CallAdvisor`.**
Confirmed this is exactly the shape Spring AI's own advisors use for both: `ToolCallingAdvisor`
implements `CallAdvisor`, `StreamAdvisor`, *and* `ToolAdvisor` on one class, sharing one
`getOrder()` — the same numeric order value governs its position in both chains. The
terminal stream advisor, `ChatModelStreamAdvisor` (confirmed via bytecode: `getOrder()
== Integer.MAX_VALUE`, `adviseStream(...)` calls `chatModel.stream(request.prompt())`
directly), is the streaming analogue of `ChatModelCallAdvisor` — a VCR stream advisor
must sit before it in the chain to short-circuit, exactly the existing ordering
discipline `DeterministicVcrAdvisor` already applies relative to `ChatModelCallAdvisor`.

**`ChatClientRequest` is the same type on both chains** (`adviseCall`/`adviseStream` both
take it) — confirmed, not assumed, by reading both interface signatures directly. This
matters for section 5.

**Recommendation, not a fork:** implement `StreamAdvisor` on the *same*
`DeterministicVcrAdvisor` class rather than a new sibling class — mirrors
`ToolCallingAdvisor`'s own precedent (one class, multiple advisor interfaces, one shared
`getOrder()`/mode configuration), and mode/redactor handling (`BYPASS`/`RECORD_ALWAYS`/
`REPLAY_ONLY`/`RECORD_OR_REPLAY`, `VcrModeOverride`, `VcrFixtureRedactor`) is genuinely
shared logic between the two, not something that benefits from duplication. The new
`adviseStream()` method would read/write through a *new* stream-specific store (section
3), not the existing `VcrTrackStore`.

## 3. Fixture schema — decided: both raw chunks and a review-only aggregate

A stream is an ordered sequence of chunks; `VcrTrack` models exactly one response. Two
questions, not one, and both are real forks:

### 3a. New type, or extend `VcrTrack`?

**Recommendation: a new, independent type, `VcrStreamTrack`** — not a field bolted onto
`VcrTrack`. Same reasoning R4's `VcrEmbeddingTrack` already established as this project's
house style: a stream fixture's shape (an ordered list of response chunks) shares no
structural overlap with `VcrTrack`'s (one request, one response) the way an embedding
request/response didn't either. `VcrStreamTrack.CURRENT_SCHEMA_VERSION` would be its own
independent counter starting at `"1"`, exactly like `VcrEmbeddingTrack`'s — **zero impact
on `VcrTrack.CURRENT_SCHEMA_VERSION` or any existing fixture**, since nothing about an
existing type changes. This part is low-risk and precedented; flagged here for
completeness, not because it's genuinely contested.

### 3b. Raw chunks, aggregate, or both? — decided: both

- **Raw chunks only.** Store the exact ordered `List<ChatResponseChunkSnapshot>` a live
  stream produced. Required for faithful replay (a consumer's own aggregation logic,
  whatever it is, must see the same chunk boundaries live or replayed) and the only
  option that doesn't risk the fixture silently drifting from what a live call would
  actually produce. **Downside:** a committed fixture's reviewer sees N small chunk
  fragments, not the final answer at a glance — worse PR reviewability than every other
  fixture type in this project (design rule #5).
- **Aggregate only.** Store just the final, combined answer (text + tool calls +
  metadata) as one `ResponseSnapshot`, replay it as a single-chunk `Flux`. Simple,
  reviewable — but **not a faithful stream replay at all**: a test asserting on
  chunk-by-chunk behavior (a UI's incremental rendering, backpressure handling, or a
  mid-stream tool-call boundary) would pass against a fixture that structurally cannot
  exhibit the behavior being tested. This is precisely the failure mode `docs/ROADMAP.md`
  already named for R3: "faking a token stream with a single-chunk `Flux` would make
  streaming tests pass while hiding exactly the chunk-boundary bugs they exist to catch."
- **Both** (my recommendation): store the raw chunk list as the fixture's actual,
  replayed source of truth, *plus* a computed, human-readable `aggregateText` (and
  aggregate finish-reason/tool-call summary) field for reviewability only — written at
  record time by running the chunks through the same aggregation a consumer would, never
  read back for replay. This mirrors an existing pattern in this project: `VcrTrack`
  already stores `canonicalRequest` purely for human review and the miss-exception
  message, never re-parsed to drive replay. The cost is a few redundant lines per
  fixture, not a new redaction/consistency risk, since the aggregate is derived, not
  independently editable.

**Decided: the third option.** `VcrStreamTrack` stores the raw chunk list (the replay
source of truth) plus a computed `aggregateText` (and aggregate finish-reason/tool-call
summary) written once at record time and never read back for replay — reviewability
without sacrificing chunk-level fidelity.

## 4. Timing semantics

**Recommendation, not a fork: no artificial inter-chunk delay by default.**
`Flux.fromIterable(chunks)` on replay, emitted as fast as Reactor's scheduler processes
them — deterministic tests should never depend on wall-clock timing, and this project's
whole design philosophy (design rule #1, exact-match only, no fuzzy anything) argues
against introducing a timing dimension a fixture would then also need to encode
faithfully. An opt-in "replay with the originally recorded inter-chunk delays" mode is
imaginable (useful for testing a UI's typing-indicator behavior specifically), but
building that flexibility now, with no demonstrated need for it, would be exactly the
kind of speculative infrastructure `docs/ROADMAP.md` section 6 already argues against
elsewhere. Not building it; can be reconsidered if a real use case appears.

## 5. Hash/matching — confirmed unchanged, no new key generator needed

**The existing `VcrCacheKeyGenerator.generate(Prompt, Map)` applies to streaming
unchanged.** Confirmed, not assumed: `StreamAdvisor.adviseStream(ChatClientRequest, ...)`
takes the *same* `ChatClientRequest` type `CallAdvisor.adviseCall(...)` does — same
`prompt()`, same `context()` (so the same structured-output/tool-schema canonicalization
already applies identically). Streaming only changes what gets *stored and replayed*
(a chunk sequence instead of one response), never what gets *hashed*. This also means
the cross-platform line-ending normalization already in `VcrCacheKeyGenerator`
(`docs/STATUS.md`'s schema `"4"` fix) already covers a streaming call's request side with
no new code — only `VcrStreamTrackMapper`'s own rendering of any schema/format text for
human review (mirroring `VcrTrackMapper`'s existing `normalizeLineEndings` duplication)
would need the same treatment applied, the same way R4's mapper already did.

## 6. Partial tool-call fragments — decided: in scope for v1, with a prerequisite

Spring AI's own `MessageAggregator` merges tool calls across chunks via `List.addAll(...)`
— a naive concatenation, not id/index-aware argument-fragment reassembly (section 1).
Whether a chunk ever carries a *partial* tool call (e.g., an `arguments` JSON string
split across multiple chunks, correlated by a shared `id`) is a genuinely open,
provider-specific question this PRD could not resolve without writing and running a real
streaming tool-call test — which was out of scope for a design-only pass, per the
original instruction for this document. What was checked: `spring-ai-ollama` has no
dedicated chunk-merging/buffering class for tool calls (unlike, plausibly, an
OpenAI-style delta-based function-call stream might need) — suggestive that Ollama's own
streaming tool calls arrive as complete objects per chunk rather than
character-fragmented deltas, but **this was circumstantial, not confirmed empirically**,
and no other provider was checked at all.

**Decided: streamed tool-call support is in scope for v1, not deferred to a v2.** This
is the opposite of this PRD's own recommendation (plain-text-only v1, fragments deferred),
made explicitly by the project owner. **This decision does not remove the empirical
prerequisite section 6's diagnosis already named — it makes it load-bearing for v1
instead of optional for a future v2.** Concretely, before `VcrStreamTrack`'s
`ToolCallChunkSnapshot` shape (or whatever the actual per-chunk tool-call representation
turns out to need) can be designed, the following has to be confirmed against a real
model, the same "diagnose first" discipline this project has applied to every other
capability (tool-calling for `.call()`, structured output, cross-platform hashing, all
found their real shape this way, not by assumption):

- Does `OllamaChatModel.stream(...)` ever emit a tool call whose `arguments` string is
  incomplete/partial on one chunk and continued on a later chunk with the same
  correlating `id`? Or does a real streaming tool-calling turn against `llama3.2:1b`
  always deliver one complete `ToolCall` object in a single chunk, with only the
  *surrounding* plain-text chunks being genuinely incremental?
- If fragments do occur: are they correlated by `id`, by index, or by chunk-arrival
  order alone? (`AssistantMessage.ToolCall` has no explicit "is this a fragment"
  marker — confirmed via its own record shape, `id`/`type`/`name`/`arguments` only — so
  fragment detection would have to be a structural inference: e.g., a
  chunk whose `ToolCall.name()` is blank/null but `arguments()` is non-empty, continuing
  a previous chunk's `id`.)
- Does this hold the same way at `VcrScope.OUTSIDE_TOOL_LOOP` (the built-in tool loop
  resolves everything before the VCR stream advisor sees a chunk) versus
  `INSIDE_TOOL_LOOP` (the VCR stream advisor sees each raw model turn, including a
  pending, unresolved tool call) — the same INSIDE/OUTSIDE distinction that already
  mattered for `.call()`'s tool-calling support (`docs/STATUS.md`'s "Bugs found on first
  compile" history) plausibly matters here too, and has not been checked for streaming
  specifically.

**This diagnosis requires running real code against a real model — it is not
achievable by further bytecode reading alone**, and is therefore new work beyond this
PRD's own "no code" scope. It has not been started. See the closing section for what
happens next.

## 7. Cross-platform — no new decision, already covered

Per section 5, `VcrCacheKeyGenerator` is reused unchanged, so the hash side of a
streaming request already gets the existing `\r\n`→`\n` normalization for schema/format
text with zero new code. The only new surface is `VcrStreamTrackMapper`'s own
human-readable rendering of that same schema/format text (if a streaming structured-output
or tool-schema case stores it for review) — which needs the identical
`normalizeLineEndings()` duplication `VcrTrackMapper`/`VcrEmbeddingTrackMapper` already
each carry, for the same reason (keep the stored, reviewable text consistent with what
was actually hashed). Message/response chunk text itself is not normalized, for the same
"could be a real, model-visible difference" reasoning already established for calls.

## 8. Test strategy and example project showcase (once approved)

- **Deterministic unit tests**, no model: a stubbed `ChatModel`/`StreamAdvisorChain`
  emitting a hand-built `Flux<ChatResponse>` of known chunks (mirroring
  `DeterministicVcrAdvisorStructuredOutputTests`'s `FakeChatModel` pattern) — first
  subscription records, exactly matching the recorded chunk *list*, not just a summary;
  second, identical subscription replays the *exact same chunk sequence*, asserted
  chunk-by-chunk (`assertThat(replayedChunks).containsExactlyElementsOf(originalChunks)`
  or equivalent), not just "the aggregate text matches" — the whole point of storing raw
  chunks is that chunk-level fidelity is what's being proven. Explicit negative/edge
  cases: empty stream, single-chunk stream, a chunk with `null`/empty text.
- **Real e2e test**, `@Tag("integration")`, real `llama3.2:1b`, no new model: record a
  real streaming call, verify the recorded chunk count/content is non-trivial, replay,
  assert **zero additional HTTP requests** (same technique every other e2e test in this
  suite already uses) and **exact chunk-by-chunk equality** between the live and
  replayed sequences — not just that the aggregated text matches, which could pass even
  if chunk boundaries were reshuffled or merged.
- **Example project showcase** (once implemented): a small streaming demo mirroring
  `RecordReplayBasicsTest`'s shape, applied to `.stream()` instead of `.call()` — record
  once, review the fixture (now readable thanks to section 3's aggregate-for-review
  field), `REPLAY_ONLY`, green with Docker stopped.

## Where this stands now

**Section 6's diagnosis is done, and its conclusion held.** Ran raw `curl` against real
Ollama's native `/api/chat` streaming endpoint (`"stream":true`, a real `tools` array,
`llama3.2:1b`) across five separate observations spanning two distinct tool/prompt
combinations. In every observation where the model produced a genuine tool call, the
**entire call — id, name, and the complete `arguments` JSON object — arrived in exactly
one `done:false` NDJSON line, never fragmented across multiple lines.** (One of the five
observations showed the model emitting fake JSON-as-text instead of a real tool call — a
known, already-documented `llama3.2:1b` unreliability from earlier in this project, not
evidence of fragmentation, and excluded from the conclusion above.) This was then
re-confirmed end-to-end against real Java code and a real model — see
`OllamaStreamingEndToEndTests.streamedToolCallingRoundTripRecordsAndReplays`, which
records and replays a genuine streamed tool-calling round trip chunk-for-chunk.

**Conclusion: tool calls arrive chunk-complete for this provider.** Per the decision
tree this PRD laid out, that means **v1 correctly includes streamed tool-call support**
— and because the schema decision (section 3b) already stores raw chunks verbatim with
no special per-chunk merging logic, supporting this required zero extra implementation
complexity beyond what plain-text streaming already needed. `VcrStreamTrack.ChunkSnapshot`
carries a `toolCalls` list with no fragment/partial-argument handling at all, exactly as
section 3b's Javadoc anticipated.

**Implemented:**
- `DeterministicVcrAdvisor` now implements both `CallAdvisor` and `StreamAdvisor` on one
  class (section 2's recommendation), with two new backward-compatible constructor
  overloads taking an optional `VcrStreamTrackStore`/`VcrStreamTrackMapper` pair — `null`
  on either one makes `adviseStream` a pure passthrough, so every advisor built via any
  of the four pre-existing constructors keeps working unchanged.
- `VcrStreamTrackStore`, `VcrStreamTrackMapper`, and `VcrStreamTrack` (section 3) —
  the latter reusing `VcrTrack.RequestSnapshot` by composition rather than duplicating
  request-side fields, exactly as planned.
- `VcrCacheKeyGenerator.generateForStream(...)` (section 5) — same canonicalization
  formula, a different header line only, so a `.call()` and a `.stream()` for the
  identical prompt never collide on one fixture file. All 19 pre-existing golden hash
  tests pass **unchanged** — verified, not assumed — plus 3 new tests proving the
  stream/call hash separation itself.
- No artificial inter-chunk delay on replay (section 4, as decided): `Flux.fromIterable`.
- Spring Boot auto-configuration wires `VcrStreamTrackStore`/`VcrStreamTrackMapper`
  automatically whenever `spring.ai.test.vcr.enabled=true` — no separate streaming
  toggle, since `.call()` and `.stream()` are two facets of the same advisor.
- Test coverage: 12 unit tests against a mocked `StreamAdvisorChain` (chunk-for-chunk
  exact-match assertions, not aggregate-only; empty stream, single-chunk stream, empty-
  text chunk, a streamed tool call, every VCR mode), 11 round-trip tests for the new
  `stream` package, 1 auto-configuration wiring test, and 2 real end-to-end tests against
  Docker + `llama3.2:1b` (`OllamaStreamingEndToEndTests`) — one plain-text stream, one
  real streamed tool-calling round trip, both asserting exact chunk sequence equality on
  replay and zero additional HTTP requests, counted at the `WebClient` layer Ollama's
  streaming path actually uses (a real gap the first test-writing pass caught: streaming
  responses do not go through the same `RestClient` the blocking path does).

Everything in this PRD — sections 1, 2, 5, 7 (confirmed via bytecode/source), sections
3, 4, 6 (decided design choices), and section 6's empirical prerequisite — is now backed
by shipped, tested code.
