# spring-ai-test-tools

> **This is an independent, community-maintained project.** It is not affiliated with,
> endorsed by, or an official project of Broadcom, VMware, Spring, or Spring AI. "Spring"
> and "Spring AI" are trademarks of their respective owners; this library simply
> integrates with their public APIs.

Deterministic, file-based record-and-replay caching for Spring AI integration tests —
today the **Recorder** layer of a planned three-layer Spring AI test-and-evaluation
toolkit. See [`docs/VISION.md`](docs/VISION.md) for where this is headed and why.

The first run calls a real model and writes the exchange to `src/test/resources/llm-cache/{sha256}.json`.
Every run after that replays that file in milliseconds — no Ollama container, no network, no tokens.

## The problem

1. **Local test loops.** Testcontainers + Ollama means every `mvn test` re-runs full inference on your CPU. Seconds per test, minutes per build.
2. **CI.** No GPU, no model container, and calling OpenAI or Anthropic from a pipeline means flakiness, token spend, and a key in the environment.
3. **Semantic caching does not solve this.** Spring AI's production caches match on similarity thresholds. In a test, a prompt that changed by one character must produce a new result or a loud failure — never a "close enough" hit from the old one.

## Features

- **Exact-match caching, always.** One SHA-256 hash per canonical request. A prompt that
  changes by a single character misses and re-records; it never returns a "close enough"
  answer from a different prompt.
- **Zero production code changes.** The advisor attaches to every `ChatClient.Builder` via
  `ChatClientBuilderCustomizer`. Nothing under test — and nothing in production — knows
  the cache exists.
- **Tool calling and structured output, not just plain text.** A tool call's name and
  arguments, and an `entity()` call's target schema, all participate in the cache key —
  verified against a real model, not assumed.
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
  call made by the assertion itself, working identically on a live response or a replay.

## Quick start

```xml
<dependency>
    <groupId>io.github.rifatcakir</groupId>
    <artifactId>spring-ai-test-tools</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

`src/test/resources/application-test.yml`:

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
knows the cache exists.

In CI:

```yaml
spring.ai.test.vcr.mode: REPLAY_ONLY
```

## Configuration reference

Every property is under the `spring.ai.test.vcr` prefix:

| Property | Type | Default | Description |
|---|---|---|---|
| `enabled` | `boolean` | `false` | Whether to attach the advisor at all. Off unless explicitly enabled — a library that silently starts caching model responses is a library that silently makes a production build pass for the wrong reason. |
| `mode` | `VcrMode` | `RECORD_OR_REPLAY` | Record-and-replay strategy — see "Modes" below. |
| `scope` | `VcrScope` | `OUTSIDE_TOOL_LOOP` | Where the advisor sits relative to tool calling — see "Tool calling" below. |
| `cache-directory` | `String` | `src/test/resources/llm-cache` | Where fixtures are read from and written to. Meant to be committed to version control. |
| `order` | `Integer` | derived from `scope` | Explicit advisor order. Only needed to interleave with other custom advisors at a specific position. |

## Modes

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

## Escaping `REPLAY_ONLY` for one test

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

## Volatile prompts

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

## Redacting fixture content (without touching the cache key)

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

## Tool calling

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

## Structured output

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

## Assertions

Beyond record/replay, `io.github.rifatcakir.springai.testtools.assertions` gives you
fluent, AssertJ-idiomatic checks on top of a response — deterministic, with no model call
made by the assertion itself, and working identically whether the response came from a
live call or a Recorder replay:

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

Any of these changes the SHA-256 and forces a re-record:

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

- **Blocking calls only.** `.stream()` passes straight through to the real model. Replaying
  a token stream deterministically — chunk boundaries, timing, partial tool-call fragments —
  is a separate design problem, and faking it with a single-chunk `Flux` would hide real
  streaming bugs.
- **`ChatClient` only.** Embedding, image, audio and moderation models do not pass through
  the chat advisor chain and are not cached.
- **Lossy by design.** Provider-native usage objects and non-portable metadata are dropped.
  If a test must assert on those, run it in `BYPASS`.
- **A fixture freezes one sample, not the model's behaviour.** If a prompt is recorded at
  `temperature > 0` (or with any other source of sampling variance), the fixture holds
  exactly one draw from that distribution. Replaying it makes the test deterministic —
  that is the entire point — but it does **not** mean the underlying model call is
  deterministic in production. A prompt that is genuinely flaky against the real provider
  will look perfectly stable in a replayed test forever. This is a property of testing
  against a cache, not a claim about the model. If a test's purpose is to catch
  output *variance* itself, VCR replay is the wrong tool for it — run that one in `BYPASS`.

## Requirements

Java 21 · Spring Boot 4.0+ · Spring AI 2.0+ (Jackson 3, `tools.jackson.*`)

## Licence

Apache-2.0
