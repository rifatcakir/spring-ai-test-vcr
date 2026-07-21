# Project status

Last updated: 2026-07-21 · Version `0.1.0-SNAPSHOT`

## Current state

Core architecture scaffolded and now proven end-to-end. **`mvn test` is green (47/47)**,
plus a real Testcontainers + Ollama integration test (excluded from the default run,
verified separately via `mvn test -Pintegration-test`) that proves the library's actual
reason to exist: record on a real cache miss, replay on a hit, zero additional network
calls on the hit. Three real bugs were found and fixed getting the unit tests green —
see "Bugs found on first compile" below. The rest of "Known risks" (the unverified
specifics list) still applies except where superseded by the e2e test above.

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
green** at <https://github.com/rifatcakir/spring-ai-test-vcr/actions> — the repo is
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

src/main/java/io/github/rifatcakir/springai/vcr/
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
   (`io.github.rifatcakir.springai.vcr.junit`), a JUnit 5 annotation + extension.
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
   <https://github.com/rifatcakir/spring-ai-test-vcr/actions>. Two jobs: `test` (JDK 21,
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
  <https://github.com/rifatcakir/spring-ai-test-vcr>, matching.

## Open questions for the maintainer

- Should `VcrTrack` record the *raw* prompt alongside the normalized one? It would make
  fixture review richer but reintroduces the secret-leak risk that normalizers exist to
  prevent. Currently: normalized only.
- Multi-module split (`spring-ai-test-vcr` core + `-spring-boot-starter`) or stay single
  module? Single module is right until there is a second consumer.
