# Project status

Last updated: 2026-07-19 · Version `0.1.0-SNAPSHOT`

## Current state

Core architecture scaffolded. **Never compiled.** See "Known risks" below — this is the
single most important fact on this page.

## What exists

```
pom.xml                                   Maven, Java 21, Boot 4.0.0 + Spring AI 2.0.0 BOMs
CLAUDE.md                                 agent instructions + verified API reference table
README.md                                 user-facing docs
docs/STATUS.md                            this file

src/main/java/io/github/rifatcakira/springai/vcr/
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
| Streaming | not implemented, passes through | see below |
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
4. `ChatClientBuilderCustomizer`'s functional method shape — confirmed `customize` exists,
   lambda target signature assumed to be `(ChatClient.Builder) -> void`.
5. Whether `spring-boot-dependencies:4.0.0` actually manages
   `tools.jackson.core:jackson-databind`. If not, pin an explicit version in `pom.xml`.
6. `ToolDefinition` is confirmed to have `inputSchema` and `description`; `name` was not
   separately confirmed (it is almost certainly `name()`).

### 3. Streaming is deliberately absent

`.stream()` calls pass straight through to the real model. Replaying a token stream
deterministically means solving chunk boundaries, inter-chunk timing, and partial
tool-call fragment reassembly. Emitting the whole cached response as a single-chunk `Flux`
would make streaming tests pass while hiding exactly the bugs they exist to catch. If
streaming is implemented later it needs its own design pass and its own fixture schema
field, not a bolt-on to `VcrTrack`.

### 4. Scope limits

Only `ChatClient` calls are cached. Embedding, image, audio and moderation models do not
pass through the chat advisor chain. If a test's determinism depends on a cached
embedding, this library does not currently help.

## Next tasks, in order

1. **Make it compile and go green.** `mvn clean test`. Fix signature mismatches against
   the real jars. Update the verified-API table in `CLAUDE.md` with anything learned.
2. **Real end-to-end test.** Testcontainers + Ollama with a tiny model
   (`qwen2.5:0.5b`), recording on the first run and replaying on the second. Assert the
   second run makes zero network calls. This is the test that proves the library actually
   works; everything above it is unit-level.
3. **Auto-configuration slice test.** `ApplicationContextRunner` — assert the advisor bean
   is absent when `enabled=false`, present and correctly ordered when `true`, and that a
   user-supplied `@Bean` of each type overrides the default via `@ConditionalOnMissingBean`.
4. **`additional-spring-configuration-metadata.json`** for IDE completion and property
   descriptions.
5. **Decide the `REPLAY_ONLY` escape hatch.** Should a single test be able to opt into a
   live call while the rest of CI stays sealed? An `AdvisorParams`-style per-request
   override, or a `@Vcr(mode = BYPASS)` JUnit extension. Currently there is no way to do
   this, which will eventually be a problem.
6. **CI workflow.** GitHub Actions: build on JDK 21, run tests with
   `-Dspring.ai.test.vcr.mode=REPLAY_ONLY`, and fail if any fixture in the working tree
   is modified by the run (a fixture change in CI means someone bypassed review).
7. **Publishing.** Sonatype OSSRH coordinates, GPG signing, `maven-source-plugin`,
   `maven-javadoc-plugin`, `LICENSE` (Apache-2.0), `CONTRIBUTING.md`.

## Open questions for the maintainer

- Package name is currently `io.github.rifatcakira.springai.vcr` and groupId
  `io.github.rifatcakira`. Confirm before the first publish — changing it later is a
  breaking change.
- Should `VcrTrack` record the *raw* prompt alongside the normalized one? It would make
  fixture review richer but reintroduces the secret-leak risk that normalizers exist to
  prevent. Currently: normalized only.
- Multi-module split (`spring-ai-test-vcr` core + `-spring-boot-starter`) or stay single
  module? Single module is right until there is a second consumer.
