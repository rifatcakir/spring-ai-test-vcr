# spring-ai-test-vcr

Deterministic, file-based record-and-replay caching for Spring AI integration tests.

The first run calls a real model and writes the exchange to `src/test/resources/llm-cache/{sha256}.json`.
Every run after that replays that file in milliseconds — no Ollama container, no network, no tokens.

Inspired by [VCR.py](https://vcrpy.readthedocs.io/), adapted to the Spring AI advisor chain
rather than the HTTP socket layer.

## The problem

1. **Local test loops.** Testcontainers + Ollama means every `mvn test` re-runs full inference on your CPU. Seconds per test, minutes per build.
2. **CI.** No GPU, no model container, and calling OpenAI or Anthropic from a pipeline means flakiness, token spend, and a key in the environment.
3. **Semantic caching does not solve this.** Spring AI's production caches match on similarity thresholds. In a test, a prompt that changed by one character must produce a new result or a loud failure — never a "close enough" hit from the old one.

## Quick start

```xml
<dependency>
    <groupId>io.github.rifatcakira</groupId>
    <artifactId>spring-ai-test-vcr</artifactId>
    <version>0.1.0-SNAPSHOT</version>
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

## Modes

| Mode | VCR.py equivalent | Behaviour |
|---|---|---|
| `RECORD_OR_REPLAY` | `once` / `new_episodes` | Replay if a fixture exists, otherwise call the model and record. **Default.** |
| `REPLAY_ONLY` | `none` | Replay if a fixture exists, otherwise throw. **Use in CI.** |
| `RECORD_ALWAYS` | `all` | Ignore fixtures, call the model, overwrite. Re-recording only — never CI. |
| `BYPASS` | — | No reads, no writes. Straight to the model. |

VCR.py separates `once` from `new_episodes` because a cassette is one file holding many
ordered episodes. This library stores one file per request hash, so both collapse into
`RECORD_OR_REPLAY`.

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
bound. VCR.py handles this with custom request matchers; here it is a `VcrPromptNormalizer`
applied before hashing:

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
    return track -> /* return a copy of track with the customer ID replaced in
                        track.request() and/or track.response() */;
}
```

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

## What busts the cache

Any of these changes the SHA-256 and forces a re-record:

- message text or role, and their order
- model, temperature, topP, topK, maxTokens, penalties, stop sequences
- tool name, description or JSON input schema

That makes fixtures a prompt regression check. If a teammate reshapes a system prompt, CI
fails with the exact canonical request that changed rather than a silently different answer.

## Secrets

Interception happens at the advisor layer, above HTTP. No `Authorization` header, bearer
token or API key ever reaches a fixture — there is nothing to filter, unlike VCR.py where
scrubbing `sk-...` from committed cassettes is a required manual step.

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
