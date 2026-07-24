# File-Sourced Stub Responses + Positioning Change: PRD

Status: **design decided below, implementation follows unless a genuine fork surfaces.**
No hash/fixture impact — this extends `io.github.rifatcakir.springai.testtools.stub`
(`docs/STUB-PRD.md`), a mechanism that already has none. The positioning change (this
document's second half) is documentation-only; nothing about `VcrCacheKeyGenerator`,
`DeterministicVcrAdvisor`, or any fixture type changes.

## What changed and why

The maintainer clarified the actual priority: the **explicit, code-configured stub path
(WireMock-style, no hash, no lookup) is the headline pattern**, chosen per test alongside
a real model or a Recorder-backed replay. Record/replay is not being removed or
diminished in capability — it's repositioned as **the option for capturing a realistic
answer automatically, without hand-typing one**, rather than as the project's sole
identity. Three ways to get a `ChatModel`/`EmbeddingModel` for a test, chosen per test,
all interchangeable from the application code's point of view because all three produce
the same interface:

1. **A real model** — a genuine integration test.
2. **An explicit stub, inline** — `VcrStubs.chatModel().respondingWith(...)`.
3. **An explicit stub, sourced from a file you name and manage** — this PRD's new
   capability.
4. *(Still available, still valuable)* **Record/replay** — capture a real answer once,
   replay it forever, no hand-authoring.

## New capability: file-sourced stub responses

### Chat — decided, implementing now

```java
ChatModel model = VcrStubs.chatModel()
    .respondingWithContentOf("responses/greeting.txt")
    .build();
```

Produces **the exact same `ChatResponse`** `.respondingWith(fileContentAsString)` would —
this method is not a new response shape, it's an alternate *source* for the same `text`
field every other builder method already composes with (`withToolCall`,
`withFinishReason`, `withUsage`, `failingWith`). No new envelope, no schema, no hash — the
maintainer's own stated complaint about record/replay's committed-fixture format
(structured JSON with a hash, a schema version, a canonical request) is exactly what this
capability avoids: **the file is the raw text the model would have returned, nothing
more.**

```
src/test/resources/responses/greeting.txt
```
```text
Yes, I can help with that. What's the order number?
```

```java
ChatModel model = VcrStubs.chatModel()
    .respondingWithContentOf("responses/refund-approved.json")
    .withFinishReason("stop")
    .build();
```

### File resolution — decided: test classpath resource, not a filesystem path

**`respondingWithContentOf(String)` resolves a classpath resource** (e.g.
`src/test/resources/responses/greeting.txt`, addressed as `responses/greeting.txt`),
exactly the way `ClassLoader.getResourceAsStream(...)` already works for any other test
resource in this ecosystem — not an absolute or relative filesystem path.

**Why classpath, not filesystem, and why only one mechanism rather than both:**

- **Portability.** A filesystem path is relative to whatever the current working
  directory happens to be when the JVM starts — different between an IDE run, `mvn test`,
  and a CI runner, and a common source of "works on my machine" test flakiness that has
  nothing to do with what the test is actually checking. A classpath resource has no such
  ambiguity: it's wherever the build put it on the test classpath, identically in every
  environment that can run the test at all.
- **Precedent already in this project.** `src/test/resources/llm-cache/` (record/replay's
  own fixture directory) is itself a classpath-adjacent, Maven-standard test-resources
  location — file-sourced stub responses living under the same root
  (`src/test/resources/responses/`, or wherever a test project organizes them) is a
  consistent convention, not a new one.
- **Precedent from prior art, done deliberately.** This mirrors WireMock's own `__files`
  directory convention: a named, managed, human-readable file a stub points at by name,
  resolved relative to a known root — not an arbitrary absolute path.
- **One mechanism, not two, on purpose.** Supporting both a classpath resource and a
  filesystem path in the same method would need either two separate methods (real API
  surface for a distinction most tests will never care about) or a single method with
  ambiguous resolution rules (does `"responses/greeting.txt"` mean classpath or relative
  to cwd? that ambiguity is exactly the kind of implicit, un-obvious behavior this
  project's whole design philosophy argues against). **Decided: classpath only for v1.**
  If a genuine need for arbitrary filesystem paths appears later (e.g. sharing fixture
  text across modules outside any one project's classpath), it is a new, separate,
  explicitly-named method (`respondingWithContentOfFile(Path)` or similar) — not a
  silent second meaning bolted onto this one.

### Content handling — decided: raw, verbatim, no normalization

The file's entire content is read as UTF-8 text and used **exactly as written** — no
trimming, no whitespace normalization, no encoding detection. This is a deliberate,
simple decision, not an oversight: this project's whole design center is explicit,
un-magical behavior (see `CLAUDE.md`'s rejection of fuzzy matching, applied here to file
content the same way). A trailing newline an editor added on save is part of the file's
content, and will be part of the returned response's text, exactly as it would be if you'd
typed `.respondingWith("...\n")` inline. Documented plainly in Javadoc so this is never a
silent surprise — a test author who wants an exact string without a trailing newline
should not put one in the file, the same way they wouldn't type one into
`.respondingWith(...)`.

### Missing file — decided: fail immediately, at the call site, not later

`respondingWithContentOf(...)` reads the file **eagerly, at the point it's called** —
not deferred to `.build()` or to the first `.call()` on the built model. A missing or
unreadable classpath resource throws immediately, with a message naming the exact
resource path that couldn't be found, pointing at the exact test line that made the
mistake — not a mysterious `NullPointerException` deep inside a later assertion, and not
a passing build followed by a confusing failure the first time the stub is actually
invoked.

### Tool calls, finish reason, `failingWith(...)` — unchanged, compose the same way

A file-sourced response composes with every existing builder method exactly the way an
inline one does, because it only ever sets the same `text` field an inline call would:

```java
ChatModel model = VcrStubs.chatModel()
    .respondingWithContentOf("responses/order-status.json")
    .withToolCall("getOrderStatus", "{\"orderId\":\"ORD-4471\"}")
    .build();
```

### Embedding — decided: not building this for embeddings

**File-sourced embedding responses are not being built.** Reasoning:

- The entire point of file-sourcing is letting a human write and read the response the
  same way they'd write prose or JSON by hand — a `float[]` vector is numeric data, not
  something a person types or reads meaningfully in a text file. Externalizing it to a
  file doesn't reduce Java boilerplate the way it does for a long chat response string;
  `VcrStubs.embeddingModel().respondingWith(new float[] {...})` is already exactly as
  readable as any file-based alternative would be, arguably more so (no second file to
  open to see what the test actually configured).
- No real, demonstrated need for this was named — the same "don't build ahead of a
  demonstrated need" discipline this project applies everywhere else (see
  `docs/STUB-PRD.md`'s own streaming-stub deferral, `docs/ROADMAP.md` §6).

If a real need for externalizing a specific numeric vector (say, one recorded from a real
model and reused across tests as a literal, non-fuzzy value) appears later, it's an
additive method on `VcrEmbeddingModelStubBuilder` at that point — not something to build
speculatively now.

## Choosing real vs. stub vs. record/replay, per test

All three produce the same `ChatModel`/`EmbeddingModel` — application code under test
(`ChatClient.builder(model)...`) never changes regardless of which one a test supplies.
The choice is purely which `ChatModel` gets constructed in the test itself:

| Need | Use |
|---|---|
| Prove the integration actually works against a real provider | A real `ChatModel` (Ollama, OpenAI, etc.) — a genuine integration test, typically `@Tag("integration")` |
| A specific, hand-authored answer — including one no real model will reliably produce on demand (a timeout, a refusal, `finishReason = "length"`) | `VcrStubs.chatModel().respondingWith(...)`/`.failingWith(...)` — inline, in the test itself |
| A realistic, longer, or reused answer you'd rather keep out of Java string literals | `VcrStubs.chatModel().respondingWithContentOf("...")` — the same stub, sourced from a file you name and manage |
| A realistic answer captured from a real model once, replayed automatically forever, without hand-authoring it yourself | Record/replay (`spring.ai.test.vcr.mode = RECORD_OR_REPLAY`/`REPLAY_ONLY`) |

This table is the core of the positioning change below: it treats all four as first-class,
equally legitimate choices for a specific, named need — not a primary mechanism
(record/replay) with a secondary escape hatch (stub) bolted on.

## Positioning change — README, doc-site, VISION

**What is not changing:** no request-matching/routing table for stubs (still explicitly
out of scope, still for the same reason — see `docs/STUB-PRD.md`), no Spring
autoconfiguration for stubs, no streaming stub yet, and record/replay's own mechanism,
fixture format, modes, and every existing capability are untouched. This is a
documentation and emphasis change, not an architecture change.

**What is changing:** the *order* and *framing* documentation presents these in.
Previously: record/replay first and foremost, "the Recorder layer of a planned
three-layer toolkit," with Stub introduced later as "a narrow complement, not a fourth
layer." Now: lead with the per-test choice table above — real model, inline stub,
file-sourced stub, or record/replay — and frame record/replay specifically as *the option
for automatic capture without hand-authoring*, not as the project's sole identity.
`docs/VISION.md`'s "not WireMock for AI" argument still holds exactly as written (no
request-matching, no HTTP-layer replay) — what's being corrected is only the earlier
"Stub is narrow, secondary" framing, which undersold a capability the maintainer now
wants presented as equally headline.

Concretely: `README.md`'s opening paragraph and Features list lead with the per-test
choice; the Stubbing section moves ahead of Record & Replay; `site/docs/index.md`'s
landing page and `site/mkdocs.yml`'s nav order lead with Stubbing; `docs/VISION.md`'s
package-tree description and "Stub" section are reworded from "narrow complement" to
"first-class, chosen per test alongside Recorder."
