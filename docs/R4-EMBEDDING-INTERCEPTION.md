# R4 — `EmbeddingModel` Interception: Design Note

Status: **implemented.** The one decision requiring explicit sign-off (vector storage
format, section 3) was resolved by the project owner: **Option A — default
pretty-printing, one array element per line, zero new serialization code.** Everything
else below was mechanical, close enough to Recorder's existing pattern that it didn't
need a separate approval round. Every Spring AI type below was confirmed via `javap -p`
against the local `spring-ai-model-2.0.0.jar`, not guessed — same discipline as
`docs/A1-ASSERTIONS-PRD.md` and `CLAUDE.md`'s own table.

## 1. `EmbeddingModel`'s real call shape (confirmed via bytecode)

```
EmbeddingModel implements Model<EmbeddingRequest, EmbeddingResponse>
    EmbeddingResponse call(EmbeddingRequest)                      -- the one abstract entry point
    float[] embed(Document)                                       -- also abstract, NOT a template method
    default float[] embed(String)                                 -- routes through call(...)
    default List<float[]> embed(List<String>)                    -- routes through call(...)
    default EmbeddingResponse embedForResponse(List<String>)      -- routes through call(...)

EmbeddingRequest(List<String> inputs, EmbeddingOptions options)
    getInstructions() -> List<String>     -- the batch of input texts, order is semantic
    getOptions()       -> EmbeddingOptions

EmbeddingOptions
    getModel() -> String
    getDimensions() -> Integer            -- some providers can truncate output dimensionality

EmbeddingResponse(List<Embedding> embeddings, EmbeddingResponseMetadata metadata)
    getResults() -> List<Embedding>       -- one per input, same order
    getResult()  -> Embedding             -- convenience, first result
    getMetadata() -> EmbeddingResponseMetadata (model, Usage)

Embedding(float[] embedding, Integer index, EmbeddingResultMetadata metadata)
    getOutput() -> float[]                -- the actual vector
```

**Confirmed via a real call, not assumed:** `llama3.2:1b` (the only model already
available in this environment — the baked Testcontainers image
`tc-ollama-llama3-2-1b-vcr-test`) answers Ollama's `/api/embed` endpoint and returns a
**2048-dimension** vector. No new model needs to be pulled — this resolves the "is a
dedicated embedding model needed" question definitively: no.

## 2. How interception has to work — no advisor chain exists here

Unlike `ChatClient`, `EmbeddingModel` has **no advisor abstraction at all** — it is a
bare `Model<EmbeddingRequest, EmbeddingResponse>` with one `call()` method, and there is
no `EmbeddingModelBuilderCustomizer` equivalent to `ChatClientBuilderCustomizer` (checked:
Spring AI 2.0 has no such type). So the "zero production code changes" mechanism this
library uses for chat — customizing a builder — has no counterpart for embeddings.

**The only provider-agnostic interception point is the `EmbeddingModel` bean itself.**
The plan: `VcrEmbeddingModel implements EmbeddingModel`, wrapping a delegate
`EmbeddingModel` and applying the identical record/replay decision tree
`DeterministicVcrAdvisor` already uses (`VcrMode` — reused as-is, no new enum needed: its
Javadoc is already written generically about "the real model," not chat-specific).
Auto-configuration registers a `BeanPostProcessor` that wraps whatever `EmbeddingModel`
bean already exists in the context, the same "application code never has a test-only
branch" outcome `ChatClientBuilderCustomizer` achieves for chat, just via a different
Spring mechanism because Spring AI gives embeddings no builder to customize.

`embed(Document)` is the one method that is **not** a template method over `call()` — it
is separately abstract. Scoped out of v1 caching (pure pass-through to the delegate,
undocumented as a limitation rather than silently caching something a fixture can't
actually represent yet): Document-based embedding is a RAG/vector-store ingestion
concern with its own id/metadata shape, not the "embed this text and assert something"
scenario R4 exists for (A2's prerequisite). Every other embedding entry point
(`embed(String)`, `embed(List<String>)`, `embedForResponse(List<String>)`) is a default
method that routes through `call(EmbeddingRequest)`, so overriding just `call()` caches
all of them for free.

## 3. Fixture schema — the one decision that needs your sign-off

**New type, not an extension of `VcrTrack`.** `VcrTrack`'s `RequestSnapshot`/
`ResponseSnapshot` are shaped entirely around chat (messages, tool calls, structured
output, finish reasons) — none of it applies to an embedding request, and forcing
embedding fields onto `VcrTrack` (or embedding-irrelevant chat fields as `null` onto a
new record) would be a worse fit than a clean, separate `VcrEmbeddingTrack` record with
its **own** `CURRENT_SCHEMA_VERSION` starting at `"1"` — an independent version counter
tracking this fixture family's own evolution, unrelated to `VcrTrack`'s. This mirrors the
project's existing bias toward separate, single-purpose types (e.g. `VcrPromptNormalizer`
vs. `VcrFixtureRedactor` as two SPIs rather than one overloaded one) and needs no
approval since it changes nothing about `VcrTrack` or any existing fixture.

**Cache key**, mirroring `VcrCacheKeyGenerator`'s existing discipline (hand-assembled
string, own header so an embedding hash can never collide with a chat hash even if the
inputs happened to coincide):

```
vcr-embedding-canonical-form/v1
model=<model, or " null">
dimensions=<dimensions, or " null">
input[0]=<escaped input text 0>
input[1]=<escaped input text 1>
...
```

Input order is preserved (like chat messages — semantic, index-correlated to the
response), not sorted (unlike tool definitions, which are an order-insensitive set).
Same `escape()`/SHA-256 logic as `VcrCacheKeyGenerator`, duplicated into a new
`VcrEmbeddingCacheKeyGenerator` rather than shared — consistent with how
`normalizeLineEndings` was already duplicated between `VcrCacheKeyGenerator` and
`VcrTrackMapper` in this codebase, small and explicit rather than a premature shared
abstraction across two otherwise-unrelated request shapes.

**The actual sign-off question: how to store the vector itself.**

A single `llama3.2:1b` embedding is a **2048-element `float[]`** (confirmed above, not
estimated). At design time, this section predicted that Jackson's default pretty-printer
(`INDENT_OUTPUT`, the same `JsonMapper` config as `VcrTrackStore.defaultJsonMapper()`)
would put one array element per line — a multi-thousand-line fixture file — and framed
the decision below as a tradeoff between accepting that or writing a custom compact
serializer. **That prediction was wrong, confirmed once a real fixture was recorded**:
Jackson 3's `float[]` handling (a primitive array, not a `List<Float>`) does not go
through the same per-element indentation path object/list serialization does — it writes
the entire vector on a single line regardless. The actual committed fixture (see
`spring-ai-test-tools-example`'s `src/test/resources/llm-cache-embedding/`) is **22
lines total**, not thousands. Design rule #5's "readable diff" framing still doesn't
really transfer to a single line of 2048 numbers — nobody reviews that line
number-by-number the way they'd review a schema or a prompt change, the same category of
concern that got multimodal caching explicitly rejected in `docs/ROADMAP.md` — but the
practical severity this section anticipated (multi-thousand-line files bloating every
diff) did not materialize. A hash-of-the-vector-instead-of-the-vector approach remains
not viable regardless, for the reason already given: a hash is one-way and could never
replay a usable vector, which is the entire point of R4.

So the real choice was, and remains, **how to render what must be stored**:

- **Option A — full vector, Jackson's default pretty-printing.** Simplest, zero new
  code beyond the record itself. Turned out to render the whole vector on one compact
  line, not one element per line as predicted — see above.
- **Option B — full vector, forced onto a single compact JSON line via a custom
  serializer.** Would have needed a small, contained serializer override scoped to just
  this one `float[]` field. Moot now that Option A already produces this outcome for
  free, with Jackson's own default behavior.

**Decided: Option A.** Chosen before the actual rendering behavior was empirically
known, on the reasoning that it was simplest and that a fully compact rendering (Option
B) could always be added later if Option A proved too bloated in practice. It didn't —
Option A already renders compactly, at zero cost, so there was nothing left for Option B
to improve. The one thing that remains true regardless of which option had been chosen:
this is the first fixture type in this project whose payload (the vector itself, now a
single dense line rather than a spread-out one) is not meaningfully human-reviewable the
way every other fixture type's content is — a deliberate, accepted departure from design
rule #5's "readable diff" framing for this one field, not an oversight.

## 4. What's mechanical (proceeding once 3 is resolved)

- `VcrEmbeddingModel implements EmbeddingModel` — the interceptor, structurally parallel
  to `DeterministicVcrAdvisor`'s decision tree (`BYPASS` → delegate; `RECORD_ALWAYS` →
  call + write; hit → replay; `REPLAY_ONLY` + miss → `VcrCacheMissException` (reused
  as-is — its fields are already generic: hash, expected path, canonical request); else
  → call + write).
- `VcrEmbeddingTrackStore` / `VcrEmbeddingTrackMapper` — parallel to
  `VcrTrackStore`/`VcrTrackMapper`, one JSON file per hash, atomic writes, tolerant reads
  (malformed fixture degrades to a miss, never crashes the build).
- Auto-configuration: `VcrProperties` gains a nested `embedding` group —
  `spring.ai.test.vcr.embedding.enabled` (default `false`, independent of chat's own
  `enabled` flag — enabling chat caching must never silently start caching embeddings
  too), `.mode` (`VcrMode`, default `RECORD_OR_REPLAY`), `.cache-directory`. A
  `BeanPostProcessor` wraps the primary `EmbeddingModel` bean when the embedding flag is
  on; chat's own auto-configuration is untouched.
- e2e test against real `llama3.2:1b`: record on a miss, replay on a hit, an HTTP-request
  counter proving zero additional network calls on the hit (same technique
  `OllamaEndToEndTests` already uses), and — the assertion specific to embeddings —
  `assertThat(replayedVector).isEqualTo(recordedVector)` (`float[]` array equality via
  AssertJ, exact values, not "same length" or "not null").
- Example project: a small showcase mirroring `StructuredOutputRecordReplayTest`'s
  shape — record once, replay, `REPLAY_ONLY`, Docker-free by default.

No hash-affecting or fixture-schema decision remains once section 3 is resolved; nothing
else here touches `VcrTrack`, `VcrCacheKeyGenerator`, or any existing chat fixture.
