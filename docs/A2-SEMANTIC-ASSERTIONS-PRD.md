# A2 — Embedding/Semantic Assertions: PRD

Status: **design only, no code written yet.** Written before implementation per
`docs/ROADMAP.md`'s A2 row and this project's own "PRD-first for new public API or a
real design decision" discipline (`docs/A1-ASSERTIONS-PRD.md`,
`docs/R4-EMBEDDING-INTERCEPTION.md`). Every Spring AI type referenced was confirmed via
`javap`/dependency inspection against the jars already resolved on this project's
classpath, not guessed.

## 1. Scope, restated from `docs/VISION.md` — this is an assertion, not the rejected matching

`CLAUDE.md`'s "Do not" list and `docs/ROADMAP.md` section 5 reject **semantic
*matching*** permanently and unconditionally: using embedding similarity to decide
*which recorded fixture* an incoming request resolves to. A2 is a categorically
different operation and does not reopen that rejection:

- **Rejected (stays rejected):** "is this incoming request close enough to a previously
  recorded one that we should replay its fixture?" — this would make the cache key
  fuzzy, and design rule #1 (exact SHA-256 match, always) is non-negotiable.
- **A2 (this PRD):** "the response is already resolved — deterministically, by exact
  hash, live or replayed, no different from what A1 already asserts on — is *its text*
  close enough in meaning to an expected answer?" This runs strictly *after* Recorder has
  already produced a response. It never influences which fixture is served or whether a
  request counts as a hit. It is a correctness check performed *on* an already-fixed
  response, using an embedding call for a completely different purpose (comparing two
  fixed pieces of text) than the rejected feature (deciding whether two *requests* are
  "close enough").

This is the same distinction `docs/ROADMAP.md` section 5 already draws for A2 in the
abstract; this PRD is where it becomes concrete API.

## 2. The critical design question: where does the `EmbeddingModel` come from?

`VcrAssertions.assertThat(response)` (A1) takes one argument — the response. A
similarity assertion needs an `EmbeddingModel` to turn both the response text and the
expected text into vectors, and there is no natural place already carrying one.

**Three options considered:**

| Option | Shape | Assessment |
|---|---|---|
| (a) A second `assertThat` overload | `VcrAssertions.assertThat(response, embeddingModel)` | Breaks the one-argument-is-the-actual-value AssertJ idiom every other entry point in this library (and AssertJ itself) follows. Also multiplies overloads: `assertThat(ChatResponse)`, `assertThat(ChatResponse, EmbeddingModel)`, `assertThat(ChatClientResponse)`, `assertThat(ChatClientResponse, EmbeddingModel)` — four entry points for what is really one piece of optional configuration |
| (b) A fluent configuring method | `assertThat(response).usingEmbeddingModel(model).isSemanticallySimilarTo(expected)` | This is AssertJ's own established idiom for optional configuration that isn't the value under test — `usingComparator(...)`, `usingElementComparator(...)`, `withPrecision(...)` all follow exactly this "configure via a method returning `this`, then assert" shape. No new entry point, no overload explosion, and it reads naturally: "using this model, assert semantic similarity" |
| (c) Global static configuration | e.g. `VcrAssertionsConfig.setDefaultEmbeddingModel(model)`, read implicitly | Rejected outright, not just deprioritized: mutable global state is exactly what this project's own test suite avoids (Surefire runs test classes in parallel by default in this project's own setup), and it would make an assertion's embedding model an invisible, action-at-a-distance dependency instead of something visible at the call site |

**Decision: (b).** `usingEmbeddingModel(EmbeddingModel)` is added to both `ChatResponseAssert`
and `ChatClientResponseAssert`, returns `this`, and stores the model for that one
assertion chain. `isSemanticallySimilarTo(...)` fails immediately, with a clear message,
if called before `usingEmbeddingModel(...)`. This isn't treated as a genuinely open fork
needing a stop-and-ask: it's AssertJ's own documented idiom applied to a new situation,
not a novel design with unclear trade-offs.

## 3. Determinism enforcement — what happens without a VCR-wired model

**The whole reason R4 (`EmbeddingModel` interception) exists is so this assertion never
makes a live, non-deterministic embedding call in CI.** But `usingEmbeddingModel(...)`
accepts any `EmbeddingModel` — nothing stops a caller from passing a raw, unwrapped one.

**Decision: detect and warn, don't refuse.** `VcrEmbeddingModel` (R4) is a concrete,
public class, so `usingEmbeddingModel(...)` can check `embeddingModel instanceof
VcrEmbeddingModel` and log an SLF4J `WARN` (not throw) when it is not — naming the risk
("this assertion will make a live embedding call on every run") without blocking a
caller who has a legitimate reason to pass a live one (e.g. a test explicitly tagged
`integration` and never run in `REPLAY_ONLY` CI). This mirrors the precedent already set
by `DeterministicVcrAdvisor` warning (not refusing) when constructed with
`VcrMode.RECORD_ALWAYS`: a real risk is named loudly, but a legitimate use case is not
forced through a workaround. A hard `instanceof` check is intentionally imperfect — a
caller could wrap `VcrEmbeddingModel` in their own decorator for unrelated reasons (e.g.
metrics) and trigger a false-positive warning — but a false-positive warning is a far
smaller cost than either silently allowing non-determinism or refusing a legitimate
wrapped instance outright.

## 4. Cosine similarity — no built-in helper exists, confirmed not assumed

Checked, not assumed: `spring-ai-commons`, `spring-ai-model`, and `spring-ai-client-chat`
(every jar already on this project's classpath) were searched for `cosine`/`similarity`
methods. The only hit is `org.springframework.ai.observation.conventions.VectorStoreSimilarityMetric`
— an enum of metric *names* for observability tagging (`COSINE`, `EUCLIDEAN`, `DOT`), not
a computation. `org.springframework.ai.model.EmbeddingUtils` (also checked) only converts
between `List<Float>`/`float[]`/`List<Double>` — no similarity math either.

**Decision: implement cosine similarity directly, no new dependency.** It's `dot(a, b) /
(‖a‖ · ‖b‖)` — roughly ten lines, well-understood, and adding a math/linear-algebra
dependency for one formula would be a worse trade than the ten lines, consistent with
A1's own precedent of not adding a JSON Schema validator dependency for a similarly small
piece of functionality. Edge cases handled explicitly with clear failure messages, not
silent `NaN`: a zero vector (undefined cosine similarity) and mismatched vector
dimensions (different embedding models or a corrupted fixture) both fail loudly rather
than producing a meaningless number.

## 5. Threshold default

**Decision: 0.7, explicitly presented as a starting point to tune per model/domain, not
a universal constant.** Cosine similarity score *distributions* vary meaningfully across
embedding models, so no single default can be correct for every model a caller might
wire in; the explicit-threshold overload (`isSemanticallySimilarTo(String, double)`) is
the one a real test suite should reach for once it has empirically observed its own
model's score distribution, exactly the same spirit as A1's `hasJsonField` needing a
caller-supplied expected value rather than a guessed one.

**Empirically checked against real `llama3.2:1b` (section 7), not just asserted — and
what it found is a real caveat worth stating plainly, not a footnote:** direct
`/api/embed` calls against six sentence pairs showed a genuine paraphrase pair at
**0.93–0.95** cosine similarity, but "unrelated" sentences (bananas and potassium, the
weather, a favorite programming language, cats as pets) at **0.66–0.72** — high enough
that a naive 0.7 default does *not* reliably separate "paraphrase" from "unrelated" for
this model; one unrelated-topic pair (0.7171) sits just *above* 0.7 and would have
falsely passed. This is a known property of embeddings extracted from an LLM's hidden
states rather than a model purpose-trained for embedding separation (sometimes called
*anisotropy* in the literature): such vectors cluster in a narrow cone, so raw cosine
similarity runs uniformly high regardless of actual semantic relatedness. **The 0.7
default is kept as-is** — it remains a reasonable middle point for a dedicated
sentence-embedding model, which is the more common case this library expects to be used
with — but this finding is documented here and in the e2e test itself (section 7) so a
caller using a small, non-dedicated embedding model like `llama3.2:1b` knows to expect a
compressed score range and to calibrate a materially higher explicit threshold (this
project's own e2e test needed **0.85** to cleanly separate the paraphrase from the
unrelated sentences for this specific model) rather than trusting the default blindly.

## 6. API surface

```java
// ChatResponseAssert / ChatClientResponseAssert (mirrored, same pattern as A1)

usingEmbeddingModel(EmbeddingModel embeddingModel)
    // Stores the model for this assertion chain; returns this. Warns (does not throw)
    // if embeddingModel is not a VcrEmbeddingModel instance — see section 3.

isSemanticallySimilarTo(String expected)
    // Cosine similarity between the response's primary text and `expected`, using the
    // default threshold (0.7 — see section 5). Fails with a message showing both texts
    // and the actual similarity score if below threshold, or that usingEmbeddingModel()
    // was never called if the model is missing.

isSemanticallySimilarTo(String expected, double threshold)
    // Same, with an explicit threshold.

isSemanticallySimilarToAnyOf(Collection<String> candidates, double threshold)
    // Passes if similarity to *any* candidate meets the threshold — for "one of these
    // acceptable phrasings," not just one fixed expected string. Failure message shows
    // every candidate's actual similarity, not just "none matched," so a near-miss is
    // visible without re-running anything.
```

Both embedding calls this assertion makes (`expected`'s and — only if not already
computed elsewhere in the chain — the response text's) go through the given
`EmbeddingModel` exactly as any other caller would use it: `embeddingModel.embed(String)`,
a default method on `EmbeddingModel` that routes through `call(EmbeddingRequest)` (R4
confirmed this via bytecode) — meaning if a `VcrEmbeddingModel` is supplied, both calls
are independently cached and replayed by R4's existing mechanism, with zero new fixture
type and zero new Recorder capability, the same "no production code needed beyond usage"
outcome E1 already demonstrated for Spring AI's own `Evaluator`.

## 7. Test strategy

- **Deterministic unit tests** (no model, no Docker): a hand-rolled `EmbeddingModel`
  stub returning fixed, known vectors for fixed inputs, so cosine similarity's own math
  is verified against hand-computable expected values — exact numbers, not "greater than
  zero." Both pass and fail cases per method, message-content-checked failures (matching
  A1's bar), including: below threshold, `usingEmbeddingModel` never called, mismatched
  vector dimensions, a zero vector.
- **Real e2e test** (`@Tag("integration")`, real `llama3.2:1b`, no new model): build a
  `VcrEmbeddingModel`-wrapped real embedding model, assert a genuinely similar sentence
  pair passes `isSemanticallySimilarTo` and a genuinely unrelated pair does not — this is
  also where the 0.7 default gets empirically checked against reality, not just asserted
  in the PRD. Prove replay is actually used on a second run: an HTTP request counter
  (same technique as every other e2e test in this suite) must show zero additional
  requests for both the expected-text embedding and the response-text embedding on a
  second, identical assertion.

## 8. Example project

A showcase mirroring `AssertionsShowcaseTest`/`EmbeddingRecordReplayTest`'s
record-once/read-fixture pattern — recorded once against real `llama3.2:1b`, reviewed,
flipped to `REPLAY_ONLY`, green with Docker stopped.
