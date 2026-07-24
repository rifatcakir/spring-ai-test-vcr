# Stubbing

Programmatic, explicit, WireMock-style canned responses. `VcrStubs` builds a plain
`ChatModel`/`EmbeddingModel` — no fixture, no cache, no hash, no Spring wiring:

```java
ChatModel model = VcrStubs.chatModel().respondingWith("Yes.").build();

ChatModel model = VcrStubs.chatModel()
    .withToolCall("getWeather", "{\"city\":\"Ankara\"}")
    .build(); // finish reason auto-defaults to "TOOL_CALLS", no need to say so separately

ChatModel model = VcrStubs.chatModel().withFinishReason("length").build();

ChatModel model = VcrStubs.chatModel().failingWith(new RuntimeException("timeout")).build();

EmbeddingModel embeddingModel = VcrStubs.embeddingModel().respondingWith(new float[] { 0.1f, 0.2f }).build();
```

Every builder method is optional — `build()` with nothing configured still returns a
valid, empty-text response, never `null`. Pass the built model straight to
`ChatClient.builder(stub)`; no autoconfiguration, no `spring.ai.test.vcr.stub.*` property,
no Spring context needed at all.

## File-sourced responses

Keep a longer or reused response out of a Java string literal — read it from a file you
name and manage instead. `.respondingWithContentOf(...)` produces exactly what
`.respondingWith(fileContent)` would; it's an alternate *source* for the same text, not a
new response shape, envelope, or schema:

```java
ChatModel model = VcrStubs.chatModel()
    .respondingWithContentOf("responses/refund-approved.json")
    .withFinishReason("stop")
    .build();
```

```
src/test/resources/responses/refund-approved.json
```
```json
{"status":"approved","refundId":"REF-9981","amount":42.50}
```

Resolves a **test classpath resource** (`src/test/resources/responses/refund-approved.json`,
addressed as `"responses/refund-approved.json"`), not a filesystem path.

!!! note "Why classpath, not a filesystem path"
    A filesystem path is relative to whatever the current working directory happens to
    be when the JVM starts — different between an IDE run, `mvn test`, and CI, and a
    source of flakiness that has nothing to do with what the test is actually checking.
    A classpath resource has no such ambiguity: it's wherever the build put it on the
    test classpath, identically in every environment that can run the test at all.

The file is read **exactly as written** — no trimming, no normalization. A trailing
newline an editor added on save is part of the file's content and will be part of the
returned response's text, exactly as it would be had you typed it into
`.respondingWith(...)` yourself.

!!! warning "A missing file fails immediately, at the call site"
    `.respondingWithContentOf(...)` reads the file eagerly, at the exact line it's
    called — not deferred to `.build()` or to the first call on the built model. A
    missing or unreadable resource throws immediately, naming the exact path that
    couldn't be found.

Composes with every other builder method exactly the way an inline response does, because
it only ever sets the same text field:

```java
ChatModel model = VcrStubs.chatModel()
    .respondingWithContentOf("responses/order-status.json")
    .withToolCall("getOrderStatus", "{\"orderId\":\"ORD-4471\"}")
    .build();
```

Not built for embeddings: the whole point of file-sourcing is letting a human write and
read the response the same way they'd write prose or JSON, and a `float[]` vector isn't
something a person types or reads meaningfully in a text file — externalizing it doesn't
reduce boilerplate the way it does for a chat response string.

## Choosing per test: real vs. stub vs. record/replay

All three produce the same `ChatModel`/`EmbeddingModel` — `ChatClient.builder(model)...`
never changes regardless of which one a test supplies.

| Need | Use |
|---|---|
| Prove the integration actually works against a real provider | A real `ChatModel` — a genuine integration test, typically `@Tag("integration")` |
| A specific, hand-authored answer — including one no real model will reliably produce on demand (a timeout, a refusal, `finishReason = "length"`) | `VcrStubs.chatModel().respondingWith(...)` / `.failingWith(...)` — inline, in the test itself |
| A realistic, longer, or reused answer you'd rather keep out of Java string literals | `VcrStubs.chatModel().respondingWithContentOf("...")` — the same stub, sourced from a file you name and manage |
| A realistic answer captured from a real model once, replayed automatically forever, without hand-authoring it yourself | [Record & Replay](record-replay.md) |

## Why this is deliberately narrower than a general-purpose mocking framework

A stub always answers the same way, for any prompt — there is no request-matching or
per-prompt routing table, on purpose. A test that needs two different answers builds two
stub instances, exactly the pattern this project's own unit tests already use for a
hand-rolled fake `ChatModel`. Being WireMock-*style* (explicit, hand-authored, no hash) is
the point; being WireMock-*shaped* (a matching/routing engine) is exactly what this
library keeps avoiding.

Streaming stubs (`Flux<ChatResponse>`) are not built yet: a stub's default `.stream()`
behaves exactly like a real non-streaming `ChatModel` does — it throws
`UnsupportedOperationException("streaming is not supported")` — which is a correct,
self-consistent default, not a missing feature.
