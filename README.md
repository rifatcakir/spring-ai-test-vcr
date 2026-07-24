# spring-ai-test-tools

> **This is an independent, community-maintained project.** It is not affiliated with,
> endorsed by, or an official project of Broadcom, VMware, Spring, or Spring AI. "Spring"
> and "Spring AI" are trademarks of their respective owners; this library simply
> integrates with their public APIs.

Test Spring AI `ChatClient`/`EmbeddingModel` code without hitting a real model every run.
Pick, per test: a real model, an explicit stub you write inline or load from a file you
name and manage — no hash, no lookup, WireMock-style — or record/replay if you'd rather
capture a real answer once and let it replay automatically forever instead of
hand-authoring one. All three hand back the same `ChatModel`/`EmbeddingModel`, so the
application code under test never changes.

## The problem

You're writing a test for code that calls a Spring AI `ChatClient`. You have three bad
options:

1. **Mock `ChatModel` with Mockito.** You end up hand-building a `ChatResponse` from
   scratch for every scenario — the exact boilerplate `VcrStubs` exists to remove — and
   the mock never catches a real integration bug.
2. **Stand up WireMock and replay raw HTTP.** Now you're maintaining JSON bodies shaped
   like your provider's wire protocol, at the wrong abstraction level entirely — tool
   calls and structured output don't exist yet at the HTTP layer WireMock operates at.
3. **Call a real model, every run.** Testcontainers + Ollama means every `mvn test`
   re-runs full inference. In CI there's no GPU, and a hosted provider means flakiness,
   token spend, and a credential in the pipeline.

Spring AI's own production semantic cache doesn't help either: it matches on similarity
thresholds — exactly backwards for a test, where a prompt that changed by one character
should produce a new fixture or a loud failure, never a "close enough" hit.

## Choosing per test: real model, inline stub, file-sourced stub, or record/replay

All four produce the same `ChatModel`/`EmbeddingModel`. The choice is purely which one
gets constructed in the test itself — `ChatClient.builder(model)...` never changes.

| Need | Use |
|---|---|
| Prove the integration actually works against a real provider | A real `ChatModel` — a genuine integration test, typically `@Tag("integration")` |
| A specific, hand-authored answer — including one no real model will reliably produce on demand (a timeout, a refusal, `finishReason = "length"`) | `VcrStubs.chatModel().respondingWith(...)` / `.failingWith(...)` — inline, in the test itself |
| A realistic, longer, or reused answer you'd rather keep out of Java string literals | `VcrStubs.chatModel().respondingWithContentOf("...")` — the same stub, sourced from a file you name and manage |
| A realistic answer captured from a real model once, replayed automatically forever, without hand-authoring it yourself | Record/replay (`spring.ai.test.vcr.mode`) |

```java
// Real model -- a genuine integration test
ChatModel model = ollamaChatModel;

// Inline stub -- exactly what you typed, no lookup, no hash
ChatModel model = VcrStubs.chatModel().respondingWith("Yes, shipped yesterday.").build();

// File-sourced stub -- same stub, response text lives in a file you manage
ChatModel model = VcrStubs.chatModel().respondingWithContentOf("responses/order-status.txt").build();

// Record/replay -- captured from a real model once, replayed automatically forever
ChatModel model = recorderBackedChatModel; // via spring.ai.test.vcr.enabled=true

ChatClient chatClient = ChatClient.builder(model).build(); // identical either way
```

See [Stubbing](#stubbing) and [Record & Replay](#record--replay) below for each in full.

## Features

- **Programmatic stubbing, WireMock-style — no hash, no lookup.** `VcrStubs.chatModel()`/
  `VcrStubs.embeddingModel()` build a canned `ChatModel`/`EmbeddingModel` from an inline
  string or a file you name and manage — plain Java, no fixture, no Spring context.
- **File-sourced responses, read verbatim.** `.respondingWithContentOf("responses/x.txt")`
  is the same stub as `.respondingWith(fileContent)`, just sourced from a classpath
  resource instead of a Java string literal — no envelope, no schema, no hash.
- **Record/replay for capturing a realistic answer without hand-authoring it.** The first
  call reaches a real model and writes the exchange to
  `src/test/resources/llm-cache/{sha256}.json`; every call after that replays it — no
  container, no network, no tokens.
- **Exact-match caching, always.** One SHA-256 hash per canonical request. A prompt that
  changes by a single character misses and re-records; it never returns a "close enough"
  answer from a different prompt.
- **Zero production code changes.** The record/replay advisor attaches to every
  `ChatClient.Builder` via `ChatClientBuilderCustomizer`. Nothing under test — and
  nothing in production — knows the cache exists.
- **Tool calling and structured output, not just plain text.** A tool call's name and
  arguments, and an `entity()` call's target schema, all participate in the record/replay
  cache key — verified against a real model, not assumed.
- **Streaming responses record and replay chunk-for-chunk, tool calls included.** No
  single-chunk fake standing in for a real stream, and no artificial inter-chunk delay on
  replay — verified against a real model, chunk-by-chunk, not just on the aggregated text.
- **Provider independent by design, not by assumption.** Verified against two genuinely
  different Spring AI `ChatModel` implementations (Ollama's native client and the official
  OpenAI Java SDK) — a fixture recorded through one replays identically through the other.
- **Volatile-value normalization and fixture redaction as separate, composable hooks.**
  Collapse harmless noise (timestamps, UUIDs) into a stable cache key without ever risking
  a collision on a real secret — the two problems are solved by two different mechanisms on
  purpose, not one overloaded one.
- **A sanctioned escape hatch for CI.** `@Vcr(mode = ...)` lets one test reach a live model
  in an otherwise sealed `REPLAY_ONLY` run, without weakening the seal for anything else.
- **Committed, human-reviewable fixtures.** Pretty-printed JSON, meant to be read in a pull
  request — a fixture diff is a prompt regression check.
- **Fixtures are cross-platform.** Line endings inside a tool's input schema or an
  `entity()` call's format instructions/JSON schema are normalized before hashing, so a
  fixture recorded on Windows replays identically on a Linux or macOS CI runner.
- **Fluent, AssertJ-idiomatic assertions on top of a response.** `VcrAssertions.assertThat(...)`
  checks tool-call shape, finish reason, and JSON field content — deterministic, no model
  call made by the assertion itself, working identically on a live response, a stub, or a
  replay.
- **`EmbeddingModel` calls cache too, independently of chat.** Wraps the `EmbeddingModel`
  bean transparently — no advisor chain to attach to the way `ChatClient` has one — and a
  replayed vector is exactly, not approximately, what was recorded.
- **Spring AI's own `Evaluator`s (`RelevancyEvaluator`, `FactCheckingEvaluator`), run in
  either of two modes.** Deterministic replay for CI, or a live drift/quality check
  (`BYPASS`) that always reaches the real model even when a fixture already exists — the
  same evaluator, zero new mechanism, purely a `VcrMode` choice. Spring AI's own
  `Evaluator` has no replay concept at all; this is the choice it doesn't offer.
- **Semantic similarity assertions, embedding-backed and deterministic.**
  `usingEmbeddingModel(...).isSemanticallySimilarTo(...)` compares a response to an
  expected answer by meaning, not exact text — both embedding calls run through the same
  Recorder-backed `EmbeddingModel` (R4), so a second identical assertion makes zero
  additional network calls.

## Quick start

The fastest path — no YAML, no Spring context, just Java:

```xml
<dependency>
    <groupId>io.github.rifatcakir</groupId>
    <artifactId>spring-ai-test-tools</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

```java
ChatModel model = VcrStubs.chatModel().respondingWith("Yes, shipped yesterday.").build();
ChatClient chatClient = ChatClient.builder(model).build();

String answer = chatClient.prompt().user("What's the status of order ORD-4471?").call().content();

assertThat(answer).isEqualTo("Yes, shipped yesterday.");
```

Prefer to capture a real model's answer once and replay it automatically forever instead
of writing the response yourself? `src/test/resources/application-test.yml`:

```yaml
spring:
  ai:
    test:
      vcr:
        enabled: true
        mode: RECORD_OR_REPLAY
```

That is the entire integration. The advisor attaches itself to every `ChatClient.Builder`
in the context via `ChatClientBuilderCustomizer`, so no production code changes and no test
knows the cache exists. In CI:

```yaml
spring.ai.test.vcr.mode: REPLAY_ONLY
```

## Stubbing

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
`ChatClient.builder(stub)`; no autoconfiguration, no `spring.ai.test.vcr.stub.*`
property, no Spring context needed at all.

### File-sourced responses

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
addressed as `"responses/refund-approved.json"`), not a filesystem path — a filesystem
path is relative to whatever the current working directory happens to be when the JVM
starts, different between an IDE run, `mvn test`, and CI, and a source of flakiness that
has nothing to do with what the test is actually checking. A classpath resource has no
such ambiguity.

The file is read **exactly as written** — no trimming, no normalization. A trailing
newline an editor added on save is part of the file's content and will be part of the
returned response's text, exactly as it would be had you typed it into
`.respondingWith(...)` yourself. A missing or unreadable resource fails immediately, at
the `.respondingWithContentOf(...)` call site, naming the exact path that couldn't be
found — not a mysterious failure later when the model is actually invoked.

Composes with every other builder method exactly the way an inline response does, because
it only ever sets the same `text` field:

```java
ChatModel model = VcrStubs.chatModel()
    .respondingWithContentOf("responses/order-status.json")
    .withToolCall("getOrderStatus", "{\"orderId\":\"ORD-4471\"}")
    .build();
```

### Why this is deliberately narrower than a general-purpose mocking framework

A stub always answers the same way, for any prompt — there is no request-matching or
per-prompt routing table, on purpose. A test that needs two different answers builds two
stub instances, exactly the pattern this project's own unit tests already use for a
hand-rolled fake `ChatModel`. Streaming stubs (`Flux<ChatResponse>`) are not built yet: a
stub's default `.stream()` behaves exactly like a real non-streaming `ChatModel` does — it
throws `UnsupportedOperationException("streaming is not supported")` — which is a correct,
self-consistent default, not a missing feature. See `docs/STUB-PRD.md` and
`docs/STUB-FILE-SOURCE-PRD.md` for the full reasoning.

## Record & Replay

The option for capturing a realistic answer automatically, without hand-authoring one:
the first call reaches a real model and writes the exchange to
`src/test/resources/llm-cache/{sha256}.json`; every call after that — in this run, and in
every run after this one, forever — replays it instead.

### Configuration reference

Every property is under the `spring.ai.test.vcr` prefix:

| Property | Type | Default | Description |
|---|---|---|---|
| `enabled` | `boolean` | `false` | Whether to attach the advisor at all. Off unless explicitly enabled — a library that silently starts caching model responses is a library that silently makes a production build pass for the wrong reason. |
| `mode` | `VcrMode` | `RECORD_OR_REPLAY` | Record-and-replay strategy — see "Modes" below. |
| `scope` | `VcrScope` | `OUTSIDE_TOOL_LOOP` | Where the advisor sits relative to tool calling — see "Tool calling" below. |
| `cache-directory` | `String` | `src/test/resources/llm-cache` | Where fixtures are read from and written to. Meant to be committed to version control. |
| `order` | `Integer` | derived from `scope` | Explicit advisor order. Only needed to interleave with other custom advisors at a specific position. |

### Modes

| Mode | Behaviour |
|---|---|
| `RECORD_OR_REPLAY` | Replay if a fixture exists, otherwise call the model and record. **Default.** |
| `REPLAY_ONLY` | Replay if a fixture exists, otherwise throw. **Use in CI.** |
| `RECORD_ALWAYS` | Ignore fixtures, call the model, overwrite. Re-recording only — never CI. |
| `BYPASS` | No reads, no writes. Straight to the model. |

Fixtures are stored one JSON file per request hash rather than one file holding many
ordered interactions, so "record what's missing" and "record everything from scratch" only
ever differ in whether an existing file gets overwritten — exactly the difference between
`RECORD_OR_REPLAY` and `RECORD_ALWAYS`.

### Escaping `REPLAY_ONLY` for one test

CI sealing the whole suite is the point — right up until one test legitimately needs a
live call anyway: a smoke test against a real provider, or an assertion on something a
fixture deliberately drops (a provider-native usage object, say). `@Vcr` lets that one
test opt out without weakening the seal for every other test in the same run:

```java
@Test
@Vcr(mode = VcrMode.BYPASS)
void assertsOnProviderNativeUsage() {
    // reaches the real model even though the rest of this CI run is REPLAY_ONLY
}
```

A method-level `@Vcr` overrides a class-level one; a test with no `@Vcr` anywhere runs
under whatever mode the advisor was actually configured with — nothing changes for tests
that don't use it. The override is thread-scoped and cleared automatically once the test
completes, so it cannot leak into the next test or silently re-enable network calls for
the rest of the suite the way a shared exempt-list property could.

Applies to whichever thread runs the annotated test method — the same constraint blocking
calls already have (see "Limitations" below): async or reactive code that switches
threads before reaching the advisor will not see the override.

### Volatile prompts

Naive prompt hashing dies the moment a prompt contains a date:

```java
.user("Today is " + LocalDate.now() + ". Summarise the backlog.")
```

The hash changes daily, the cache misses forever, and the fixture directory grows without
bound. A `VcrPromptNormalizer`, applied before hashing, collapses that noise into a stable
placeholder instead:

```java
@Bean
VcrPromptNormalizer ignoreVolatileValues() {
    return RegexPromptNormalizer.ISO_DATE
        .andThen(RegexPromptNormalizer.UUID);
}
```

Built in: `ISO_DATE`, `ISO_DATE_TIME`, `UUID`, `EPOCH_MILLIS`, plus
`RegexPromptNormalizer.of(regex, placeholder)` for anything else.

Normalizers affect the hash and what gets written to the fixture. The text sent to the real
model on a miss is always the original, unmodified prompt.

> Redact volatile values, not meaningful ones. Normalizing away something the model
> conditions on will make two genuinely different requests share one fixture.

### Redacting fixture content (without touching the cache key)

`VcrPromptNormalizer` and `VcrFixtureRedactor` sound similar and solve adjacent problems,
but **they change different things**, and confusing them has a real cost:

|   | Affects the hash? | Affects what a hit returns? | Affects what's written to disk? |
|---|---|---|---|
| `VcrPromptNormalizer` | **Yes** | No (replay is unaffected either way) | Yes |
| `VcrFixtureRedactor` | **No** | No | Yes |

A normalizer *merges* requests: two prompts that normalize to the same text share one
fixture. That's exactly what you want for a timestamp, and exactly what you don't want
for something the model actually behaves differently for — say, a customer ID. If you
reach for a normalizer to keep a customer ID out of a committed fixture, you've just made
every request for every customer share one cache entry, which is silently wrong in a way
nothing will warn you about.

A redactor never merges anything. It runs once, after the real model has already
answered and after the cache key has already been computed from the un-redacted request,
and it only changes what a reviewer sees in the committed JSON:

```java
@Bean
VcrFixtureRedactor redactCustomerId() {
    return track -> new VcrTrack(track.schemaVersion(), track.hash(), track.recordedAt(),
            // canonicalRequest is a *separate* top-level field that also embeds the raw
            // message text — redact it too, or the value leaks right back through here
            // even though request.messages() below looks correctly redacted.
            track.canonicalRequest().replaceAll("customer-\\d+", "[REDACTED]"),
            new VcrTrack.RequestSnapshot(track.request().model(), track.request().temperature(),
                    track.request().topP(), track.request().topK(), track.request().maxTokens(),
                    track.request().stopSequences(),
                    track.request().messages().stream()
                        .map(message -> new VcrTrack.MessageSnapshot(message.type(),
                                message.text().replaceAll("customer-\\d+", "[REDACTED]"),
                                message.toolCalls(), message.toolResponses()))
                        .toList(),
                    track.request().tools(), track.request().structuredOutput()),
            track.response());
}
```

> **A partial redaction is a silent leak, not a smaller one.** The `canonicalRequest`
> line above is not optional decoration — an earlier version of this exact example
> redacted `request.messages()` only, and the raw customer ID was still sitting in
> `canonicalRequest` in the committed fixture. The bug wasn't caught by an assertion; it
> was caught by actually opening the generated JSON file and reading it. Do the same
> before trusting any redactor you write: check the raw committed file, not just the one
> field you remembered to test.

The value you redact **still determines which fixture a request resolves to** — it is
simply never written down. Two requests differing only in a redacted field still get two
different fixtures, exactly as before redaction existed. This is why it's safe to use on
real secrets or PII in a way a normalizer is not: redacting can never cause a cache
collision. The trade-off is the mirror image of a normalizer's — a redactor cannot
collapse a volatile-but-harmless value the way `RegexPromptNormalizer.ISO_DATE` can; use a
normalizer for that instead.

`VcrTrack#hash()` and `VcrTrack#schemaVersion()` in whatever a redactor returns are
ignored — the fixture is always filed under the hash actually computed, regardless of
what a redactor's return value claims. Multiple redactors run in registration order
(`Ordered` sequence when Spring-managed); a redactor that throws is not swallowed, so a
broken redactor fails the recording loudly rather than shipping a half-redacted fixture.

### Tool calling

Spring AI 2.0 moved the tool-calling loop into the advisor chain as `ToolCallingAdvisor`
(order `HIGHEST_PRECEDENCE + 300`). Where this advisor sits decides what a fixture contains:

```yaml
spring.ai.test.vcr.scope: OUTSIDE_TOOL_LOOP   # default
```

- **`OUTSIDE_TOOL_LOOP`** — one fixture per interaction, holding the final answer. Fastest.
  On a hit the loop never runs, so your `@Tool` methods are never invoked. A test asserting a
  tool's side effect will fail on replay.
- **`INSIDE_TOOL_LOOP`** — one fixture per model turn. Tool-call requests replay from disk
  while real `@Tool` methods still execute each iteration. Use this for side-effect assertions.

Verified against a real model, not just designed: a two-turn tool-calling round trip
(the model calls a tool, the real `@Tool` method runs, the result goes back, the model
answers) records two fixtures under `INSIDE_TOOL_LOOP`, replays both with zero further
network calls, and still re-invokes the real `@Tool` method on replay, exactly as
documented above — see `OllamaToolCallingEndToEndTests` in the test suite.

### Streaming

`ChatClient...stream()` records and replays too — chunk-for-chunk, not as a single-chunk
fake standing in for a real stream:

```java
Flux<String> tokens = chatClient.prompt().user(prompt).stream().content();
```

The first call reaches the real model and records every chunk the live `Flux<ChatResponse>`
emitted, in order. The identical second call replays the exact same chunk sequence — same
count, same order, same per-chunk text/finish-reason/tool-call content — with zero network
calls. There is no artificial delay between replayed chunks: a fixture replays as fast as
Reactor's scheduler processes it, because deterministic tests should never depend on
wall-clock timing.

Streamed tool calls are supported in the same fixture, with no extra configuration:
empirically, against real Ollama, a genuine tool call always arrives whole — id, name, and
the complete arguments — in a single chunk, never fragmented across multiple chunks, so
storing the raw chunk sequence verbatim already replays a streamed tool call faithfully.
`VcrScope.INSIDE_TOOL_LOOP`/`OUTSIDE_TOOL_LOOP` apply to streaming exactly as they do to
`.call()` (see "Tool calling" above) — the same advisor, and the same shared `getOrder()`,
governs both chains.

The fixture is a new, independent type (`VcrStreamTrack`, not a field bolted onto the
`.call()` fixture shape) so the two fixture families evolve separately. Alongside the raw
chunk list, it stores a computed `aggregateText` (and aggregate finish-reason/tool-call
summary) purely for PR reviewability — never read back for replay — so a reviewer sees the
final answer at a glance instead of having to mentally concatenate small chunk fragments.

Verified against a real model, not just designed: both a plain-text stream and a genuine
streamed tool-calling round trip record and replay chunk-for-chunk with zero additional
HTTP requests — see `OllamaStreamingEndToEndTests` in the test suite.

### Structured output

`ChatClient...call().entity(MyDto.class)` (Spring AI's `BeanOutputConverter`-based
structured output) round-trips through Recorder — verified against a real model, not
assumed:

```java
record CityWeather(String city, Integer temperatureCelsius) {}

CityWeather weather = chatClient.prompt().user(prompt).call().entity(CityWeather.class);
```

POJO conversion happens entirely client-side, after the advisor chain returns, so a
replayed response converts to the same object a live call would have produced — no extra
configuration needed.

The cache key is sensitive to *what* `entity()` asks for, not just the prompt text it's
paired with: the target type's format instructions and JSON schema participate in the
hash right alongside the message content. Two `entity()` calls that share identical
prompt text but ask for different target types always record and replay as two separate
fixtures — a schema change is exactly the kind of thing that should bust the cache, the
same principle "What busts the cache" below is built around. Verified end to end against
a real model in `OllamaStructuredOutputEndToEndTests`, and fast/Docker-free in
`DeterministicVcrAdvisorStructuredOutputTests`.

Spring AI supports two ways to get structured output, and both are cached the same way:

- **Text-instruction-based** (the default) — the model is asked, in plain language, to
  produce JSON matching a schema. Works with any provider, but asks a smaller model to
  follow written instructions closely.
- **Provider-native** (`entity(Class, spec -> spec.useProviderStructuredOutput())`) — for
  providers that support it (Ollama included), the schema constrains generation at the
  token level instead of relying on the model to read and follow instructions. More
  reliable for smaller models. See
  [`spring-ai-test-tools-example`](https://github.com/rifatcakir/spring-ai-test-tools-example)'s
  `StructuredOutputRecordReplayTest` for a worked example.

### Embeddings

`EmbeddingModel` calls record and replay too, independently of chat — enable with
`spring.ai.test.vcr.embedding.enabled=true` (separate from, and off unless enabled
alongside, the top-level `spring.ai.test.vcr.enabled`):

```yaml
spring:
  ai:
    test:
      vcr:
        embedding:
          enabled: true
          mode: RECORD_OR_REPLAY
          cache-directory: src/test/resources/llm-cache-embedding
```

`EmbeddingModel` has no advisor chain the way `ChatClient` does, so interception wraps
the `EmbeddingModel` bean itself, transparently — `@Autowired EmbeddingModel` in your own
code is unchanged, and every entry point (`embed(String)`, `embed(List<String>)`,
`embedForResponse(List<String>)`) is covered, since they all route through the same
underlying call. A replayed vector is exactly (not "the same length as") what was
recorded — see
[`spring-ai-test-tools-example`](https://github.com/rifatcakir/spring-ai-test-tools-example)'s
`EmbeddingRecordReplayTest` for a worked example, including against `llama3.2:1b`, which
isn't a dedicated embedding model but answers embedding requests too.

This is the foundation the roadmap's semantic/embedding assertions layer depends on: an
assertion that computes cosine similarity against a reference answer needs its own
embedding call to be exactly this deterministic, or every CI run would make a live,
non-reproducible embedding call to check a "deterministic" test.

## Assertions

Beyond record/replay and stubbing, `io.github.rifatcakir.springai.testtools.assertions`
gives you fluent, AssertJ-idiomatic checks on top of a response — deterministic, with no
model call made by the assertion itself, and working identically whether the response
came from a live call, a stub, or a replay:

```java
import static io.github.rifatcakir.springai.testtools.assertions.VcrAssertions.assertThat;

ChatResponse response = chatModel.call(prompt); // or chatClient...call().chatResponse()

assertThat(response)
    .hasToolCall("getOrderStatus", args -> assertThat(args).containsEntry("orderId", "ORD-4471"))
    .hasFinishReason("stop");

assertThat(response).hasJsonField("/estimatedDays", 9).extractingText().contains("Turkish Airlines");
```

- **Tool-call-shape assertions** — `hasToolCall(name)`, exact-argument matching
  (`hasToolCall(name, Map<String,Object>)`), partial/custom matching
  (`hasToolCall(name, Consumer<Map<String,Object>>)`), `hasNoToolCalls()`,
  `hasToolCallCount(int)`. Arguments are parsed before comparison, not string-matched —
  two differently-serialized-but-equal argument JSON strings both satisfy an exact-match
  assertion.
- **`hasFinishReason(String)`** and **`extractingText()`** (bridges into an ordinary
  AssertJ string assertion, so every existing `contains`/`matches`/`isEqualTo` already
  works).
- **Field-level JSON assertions** — `hasJsonField(jsonPointer)`,
  `hasJsonField(jsonPointer, expectedValue)`, `hasJsonFieldOfType(jsonPointer,
  JsonNodeType)`, addressed by RFC 6901 JSON Pointer (e.g. `"/carrier"` or
  `"/shipping/carrier"`) — useful for checking a structured-output response's shape
  independent of whether you also convert it with `entity()`.

**Tool-call assertions have one real scope limit, worth knowing up front:** they see a
tool call that is still *pending* on the response you're asserting on — a raw
`ChatModel#call(Prompt)` result, for instance. A normal
`chatClient.prompt()...tools(...).call()`'s built-in tool loop already resolves and
executes the call internally before you ever see the final response, so there's nothing
left for `hasToolCall(...)` to find on that final answer — check the model's own turn
(or, for a Recorder-backed test, that turn's `INSIDE_TOOL_LOOP` fixture) instead. See
[`spring-ai-test-tools-example`](https://github.com/rifatcakir/spring-ai-test-tools-example)'s
`AssertionsShowcaseTest` for a worked example against an already-committed fixture.

### Semantic assertions

`"is this response close enough in meaning to what I expected"` — a plain string or JSON
assertion can't answer that, but an embedding comparison can. `usingEmbeddingModel(...)`
supplies the model, `isSemanticallySimilarTo(...)` compares:

```java
assertThat(response)
    .usingEmbeddingModel(embeddingModel) // pass the VcrEmbeddingModel-wrapped one (R4) -- see below
    .isSemanticallySimilarTo("Paris is the capital city of France.");

assertThat(response).usingEmbeddingModel(embeddingModel)
    .isSemanticallySimilarToAnyOf(List.of("shipped", "on its way", "out for delivery"), 0.8);
```

**Both embedding calls this makes — the response text's and the expected text's — go
through the model you supply exactly like any other caller would use it.** Pass a
`VcrEmbeddingModel` (R4, above) and both are cached and replayed for free, with zero
additional network calls on a second identical assertion; pass a live one and this
assertion makes a live, non-deterministic, token-costing call on every test run — this
library warns (doesn't refuse) when the model you pass isn't Recorder-backed, since a
live model may be a deliberate choice for an explicitly-tagged integration test.

Cosine similarity is computed directly (no dependency added for one formula — checked,
not assumed, that no Spring AI jar already on this project's classpath has a
ready-made helper). `isSemanticallySimilarTo(expected)` uses a default threshold of
`0.7`; `isSemanticallySimilarTo(expected, threshold)` takes an explicit one.
**The default is a starting point, not a universal constant** — confirmed empirically,
not just argued: small models whose embeddings come from an LLM's own hidden states
(rather than a model purpose-trained for embedding separation), `llama3.2:1b` included,
compress similarity scores into a narrow, uniformly-high range — real measurements
against it showed genuine paraphrases at 0.93–0.95 but unrelated sentences at 0.66–0.72,
high enough that 0.7 doesn't reliably separate them for that specific model. Observe your
own model's score distribution and pick an explicit threshold accordingly rather than
trusting the default blindly.

This is an *assertion*, not the semantic *matching* `CLAUDE.md` permanently rejects for
cache-key resolution: it runs strictly after Recorder has already resolved the response
by exact hash, live or replayed either way, and never influences which fixture is served.

## Evaluator

Spring AI ships its own `Evaluator` mechanism — `RelevancyEvaluator` ("is this response
relevant to the query, given this context") and `FactCheckingEvaluator` ("is this claim
supported by this document"), both built from a `ChatClient.Builder`. Both make their
internal judge call exactly the way any other `ChatClient` call is made — so wiring the
same VCR-enabled builder this library already customizes into one of them makes its
judge call deterministic and free to run in CI, with **no new code from this library at
all**:

```java
ChatClient.Builder chatClientBuilder = ...; // already customized by this library's ChatClientBuilderCustomizer

Evaluator relevancyEvaluator = RelevancyEvaluator.builder()
    .chatClientBuilder(chatClientBuilder)
    .build();

EvaluationResponse result = relevancyEvaluator.evaluate(
    new EvaluationRequest(query, List.of(new Document(context)), response));
```

The first `evaluate()` call for a given input records a fixture the same way any other
call does; every identical call after that replays it — no additional judge-model call,
no additional token spend, no flakiness from the judge model's own non-determinism. This
isn't a feature this library had to build: both `RelevancyEvaluator.evaluate()` and
`FactCheckingEvaluator.evaluate()` were confirmed, via bytecode disassembly, to do
nothing more than `chatClientBuilder.build().prompt().user(...).call().content()`
internally — structurally identical to any other `ChatClient` call — and verified end to
end against a real model, not just argued from the bytecode: see
`OllamaEvaluatorEndToEndTests` in the test suite.

A recorded verdict is never frozen against a response that has since changed, either:
because the judge prompt is rendered with the actual response/claim spliced directly
into the message text this library hashes, judging a *different* answer produces a
*different* cache key and reaches the judge model again — confirmed the same way as
everything else here, by counting real HTTP requests and real fixture files, not by
reading the prompt template and assuming.

### Two modes, one evaluator

Spring AI's own `Evaluator` has no concept of replay — every `evaluate()` call reaches
the model, live, every time, by design. **This is the actual differentiator this
project adds on top: run the exact same `RelevancyEvaluator`/`FactCheckingEvaluator` in
either of two modes, with zero new mechanism, purely by which mode its `ChatClient.Builder`
was built with:**

- **Deterministic replay** (`REPLAY_ONLY`) — every CI run, every push/PR. No network, no
  token spend, no flakiness. The judge's verdict for a known input is read from a
  committed fixture.
- **Live drift/quality check** (`BYPASS`, or `RECORD_ALWAYS` to overwrite the fixture with
  a fresh verdict) — a deliberate, separate run: nightly, `workflow_dispatch`, or a
  developer checking before a release whether the model's judgment on a known case has
  drifted. `BYPASS` reaches the real model on *every* call, confirmed even when a
  matching fixture already sits on disk — the live path never replays, by construction
  (`DeterministicVcrAdvisor`'s `BYPASS` branch returns before ever computing a hash or
  touching the fixture store).

**Never run the live path in default CI** — it reintroduces every problem Recorder
exists to eliminate. It belongs in a separate, opt-in job, exactly like this project's
own nightly `e2e` workflow already runs its real-Ollama proofs. Spring AI gives you the
evaluators; this project gives you the choice of which mode to run them in.

**Toxicity checks:** confirmed absent from Spring AI 2.0.0 — checked, not assumed, across
every jar this project depends on. A toxicity judge would need a bespoke `Evaluator`
implementation, built the same shape `FactCheckingEvaluator` already is (a
`ChatClient.Builder` plus a judge prompt) — a documented, buildable pattern, not something
built here speculatively ahead of a real need.

## Providers

Interception happens at the `ChatClient` advisor layer, above any provider-specific HTTP
client — the cache key is built from `ChatOptions` and message content alone, never from
which `ChatModel` implementation or wire protocol is in use. This means switching
implementations doesn't require re-recording fixtures, as long as the model name and
parameters stay the same: a fixture is filed under what would be sent to a model, not
under which Java class sent it.

Verified with two genuinely different implementations, not assumed from one: alongside
`OllamaChatModel` (`spring-ai-ollama`'s native, `RestClient`-based client), `OpenAiChatModel`
(built in Spring AI 2.0 on the official OpenAI Java SDK — an entirely different HTTP stack)
records and replays correctly on its own, and a fixture recorded through the native Ollama
client replays identically through the OpenAI-SDK client too, at zero additional network
cost — see `OpenAiViaOllamaEndToEndTests` in the test suite, which points `OpenAiChatModel`
at Ollama's own OpenAI-compatible endpoint rather than a real OpenAI account.

## What busts the cache

Any of these changes the SHA-256 and forces a re-record (record/replay only — stubs have
no cache key at all):

- message text or role, and their order
- model, temperature, topP, topK, maxTokens, penalties, stop sequences
- tool name, description or JSON input schema
- which tool a model turn called, with what arguments, and what that tool responded
  with — the hash tells two different tool calls, or two different tool results, apart
  even inside conversation history under `INSIDE_TOOL_LOOP`, where each model turn gets
  its own fixture
- an `entity()` call's target type — its format instructions and JSON schema participate
  in the hash, so two different structured-output types sharing the same prompt text
  always record and replay as their own separate fixtures

That makes fixtures a prompt regression check. If a teammate reshapes a system prompt, CI
fails with the exact canonical request that changed rather than a silently different answer.

## Secrets

Interception happens at the advisor layer, above HTTP. No `Authorization` header, bearer
token or API key ever reaches a fixture — there is nothing to filter, and no
header-scrubbing step to remember before committing one.

Prompt *content* is another matter: if your prompts carry PII, redact it with a
`VcrPromptNormalizer` before committing fixtures.

## Limitations

- **`ChatClient` and `EmbeddingModel` only.** Image, audio and moderation models do not
  pass through either mechanism and are not cached or stubbed.
- **Record/replay is lossy by design.** Provider-native usage objects and non-portable
  metadata are dropped. If a test must assert on those, run it in `BYPASS`.
- **A fixture freezes one sample, not the model's behaviour.** If a prompt is recorded at
  `temperature > 0` (or with any other source of sampling variance), the fixture holds
  exactly one draw from that distribution. Replaying it makes the test deterministic —
  that is the entire point — but it does **not** mean the underlying model call is
  deterministic in production. A prompt that is genuinely flaky against the real provider
  will look perfectly stable in a replayed test forever. This is a property of testing
  against a cache, not a claim about the model. If a test's purpose is to catch
  output *variance* itself, VCR replay is the wrong tool for it — run that one in `BYPASS`.
- **Stubs have no request-matching.** A stub always answers the same way, for any prompt
  — a test that needs two different answers builds two stub instances. Streaming stubs
  are not built yet.

## Requirements

Java 21 · Spring Boot 4.0+ · Spring AI 2.0+ (Jackson 3, `tools.jackson.*`)

## Licence

Apache-2.0
