# Roadmap

Last updated: 2026-07-22

## Why this file exists

There was no single roadmap before this. Planning was split across two documents that
each cover part of the picture:

- **`docs/STATUS.md`** — "Next tasks, in order" (7 items) plus an "Open questions for the
  maintainer" list. Sequential and compile/test-focused, written for whoever picks up the
  repo next.
- **`docs/DISPATCH_PROMPT.md`** — four copy-paste prompts for a dispatched coding agent
  (compile-and-green, e2e Testcontainers test, auto-config slice tests, and a design-only
  task for the `REPLAY_ONLY` escape hatch). Task 1 is now done (see `STATUS.md`).

Neither document ranks work by value, sizes it, or looks outward at how mature prior art
(VCR.py, Ruby VCR, WireMock, Polly.js) solved adjacent problems. That's what this file
adds. It does not replace `STATUS.md` — that stays the terse "what's true right now"
ledger. This file is the "what should we build, in what order, and why" view, and it
folds in everything `STATUS.md`'s task list and `DISPATCH_PROMPT.md` already committed
to, so you only need to read one thing to plan work.

## Current state (as of this writing)

`mvn test` green (55/55), plus two real Testcontainers + Ollama end-to-end tests
(`OllamaEndToEndTests` and `OllamaToolCallingEndToEndTests`, both excluded from the
default run, verified via `mvn test -Pintegration-test`) proving record → replay → zero
additional network requests on the hit against a real model, not a mock — the second one
specifically for a two-turn tool-calling round trip under `INSIDE_TOOL_LOOP`, including
the real `@Tool` method re-running on replay. `VcrTrack` schema version `"2"` fixed a
real gap found while building that test: a message's own tool calls/responses were
silently invisible to both the hash and the fixture, because `Message.getText()` is
empty for both an assistant's tool-calls-only turn and any tool-response message — two
different tool calls or two different tool results used to collide on the same fixture
hash under `INSIDE_TOOL_LOOP`. Golden hash tests for ordinary (non-tool-call) prompts
were verified unaffected; new golden hash tests pin the tool-call/tool-response
canonicalization specifically. `VcrFixtureRedactor` now exists
alongside `VcrPromptNormalizer` for redacting committed-fixture content without ever
being able to change a request's cache key, and `@Vcr(mode = ...)` now exists as a
per-test escape hatch out of a sealed `REPLAY_ONLY` CI run. A GitHub Actions workflow
(`.github/workflows/ci.yml`) is live and green at
<https://github.com/rifatcakir/spring-ai-test-vcr/actions> — the repo is public and
pushed, and both the per-PR unit test job and the scheduled Ollama e2e job have run
successfully on a real hosted runner. The build side of publishing (`LICENSE`, Central
metadata, a `release` profile) is done and `mvn -Prelease package` verified to actually
work; nothing has been published, and everything credential-related is left for the
maintainer per `docs/PUBLISHING.md`. See `STATUS.md` for the full detail and the bugs
fixed to get here.

---

## What prior art gets right (and what doesn't transfer)

| Tool | Model | Ideas worth stealing | Ideas that don't fit here |
|---|---|---|---|
| **[VCR.py](https://vcrpy.readthedocs.io/)** | HTTP-layer interception, one cassette (YAML/JSON) holds an ordered list of request/response pairs | Record modes (`once` / `new_episodes` / `none` / `all`), `before_record_request`/`filter_headers` as a **separate** hook from the request matcher, per-test cassette activation (`@pytest.mark.vcr`) | Fuzzy/partial request matching (`match_on` combinators) — this project's whole reason to exist is refusing exactly that; cassette-as-multi-interaction-file — this project already made the opposite call (one file per hash, O(1) lookup, see `STATUS.md` decision table) |
| **Ruby VCR** | Same lineage as VCR.py, more mature hook system | `allow_playback_repeats`, pluggable request matchers as first-class objects, `ignore_request` | Same fuzzy-matching caveat as VCR.py |
| **WireMock** (record-playback) | Server-side stub journal, supports **scenarios**: the same request returns different canned responses across ordered calls (simulate retry-then-success, rate-limit-then-ok) | The scenario/sequenced-response idea — useful for testing retry and backoff logic against an LLM client without a live flaky provider | Its stub-priority/proxy model doesn't map cleanly onto an in-process advisor with no HTTP layer |
| **Polly.js** | Interception adapters decoupled from storage "persisters" (fs, REST, localStorage) | Decoupling capture from storage *in principle* | Pluggable storage backends directly **contradict** this project's rule #5 (fixtures are committed, pretty-printed, reviewed in PRs) — adopting this would mean fixtures could live somewhere un-reviewable. Only worth revisiting if a second consumer with a real need shows up (see `STATUS.md`'s multi-module open question) |
| **nock** | Fixture "modes" (record / dryrun / wild / lockdown) similar in spirit to this project's `VcrMode` | Naming/semantics cross-check only — `VcrMode` already covers the same ground | Nothing new |

Two gaps below were found by making this comparison, not carried over from either
existing planning doc — they're flagged as such.

---

## Feature roadmap

### Must-have (blocking a credible v0.1.0)

| # | Feature | Why | Size | Source |
|---|---|---|---|---|
| 1 | ~~**Separate the cache-key normalizer from fixture redaction**~~ **Done** | `VcrFixtureRedactor` (new SPI in the root `vcr` package, alongside `VcrPromptNormalizer`): applied only on the write path, after the hash is already computed from the un-redacted request; `hash()`/`schemaVersion()` on a redactor's return value are ignored and re-applied from the original track by `DeterministicVcrAdvisor`, enforced in code (belt-and-suspenders — a redactor is structurally unable to change the cache key, not merely asked not to). Collected the same way normalizers already are (`List<VcrFixtureRedactor>`, `Ordered` sequence). Five tests in `VcrFixtureRedactorTests` cover bit-identical no-redactor behavior, redaction never reaching a live response or replay, a forged-hash attempt being ignored, ordering across multiple redactors, and a throwing redactor propagating with nothing written. README gained a "Redacting fixture content" section with a comparison table, specifically because a normalizer *merges* requests and a redactor *never* does — confusing the two silently causes cache collisions on real PII, which is exactly the failure mode this item exists to close | M (1–2 days) | New |
| 2 | ~~**Auto-configuration slice tests + `additional-spring-configuration-metadata.json`**~~ **Done** | `SpringAiVcrAutoConfigurationTests` (9 tests): absence/presence by `enabled`, scope-derived vs. explicit order, `@ConditionalOnMissingBean` for all four bean types, and registered `VcrPromptNormalizer` beans confirmed reaching the generated `VcrCacheKeyGenerator`. Metadata file merges cleanly (verified against the built `spring-configuration-metadata.json`) | S–M (1 day) | `STATUS.md` #3/#4, `DISPATCH_PROMPT.md` Task 3 |
| 3 | ~~**`REPLAY_ONLY` escape hatch**~~ **Done** — `@Vcr(mode = ...)` JUnit 5 annotation + extension. See the design note below for the comparison and rationale | CI runs sealed (`REPLAY_ONLY`); there is now a sanctioned, narrowly-scoped way for a single test to make a live call without weakening the whole suite's guarantee | Design: hours. Impl: M (1 day) | `STATUS.md` #6, `DISPATCH_PROMPT.md` Task 4 |
| 4 | ~~**Real end-to-end proof**~~ **Core proof done** — `OllamaEndToEndTests` (`@Tag("integration")`, `mvn test -Pintegration-test`): real Testcontainers-managed Ollama container, real `llama3.2:1b`, genuine cache miss → record → hit → replay, with an HTTP request counter wired into the `RestClient` underneath `OllamaApi` proving zero additional network requests on the hit — not inferred from response text alone. **Not yet covered** by this test (narrower, can be added incrementally rather than re-blocking anything): `REPLAY_ONLY` throwing on a miss without touching the container, and the `INSIDE_TOOL_LOOP` vs `OUTSIDE_TOOL_LOOP` distinction via a counting `@Tool`. Docker Desktop needed starting first; once started, this was unblocked the same session | Core: done. Remaining two scenarios: S (a few hours, same test class) | `STATUS.md` #2, `DISPATCH_PROMPT.md` Task 2 |
| 5 | ~~**CI workflow with a fixture-drift gate**~~ **Done, verified green on a real runner** — `.github/workflows/ci.yml`, live at <https://github.com/rifatcakir/spring-ai-test-vcr/actions>: a `test` job (every push/PR, `mvn test`, no Docker) with a hard-fail `git status --porcelain` check after the run, and a separate `e2e` job (nightly + `workflow_dispatch` only, not per-PR) for the real Testcontainers proof. See the design note below for why `-Dspring.ai.test.vcr.mode=REPLAY_ONLY` was deliberately *not* added, and why the drift check is whole-tree rather than fixture-path-scoped | Turns "a fixture change in CI means someone bypassed review" from a stated intent into an enforced check. Both jobs have completed successfully on GitHub's own `ubuntu-latest` runners — no longer just a local dry run | S (a few hours) | `STATUS.md` #7 |
| 6 | **Document the non-determinism caveat** — a fixture recorded at `temperature > 0` freezes one sample from a distribution; replay will make a flaky-in-production prompt look deterministically stable in tests, and that's a property of testing, not of the model | Near-zero cost, prevents a confused bug report down the line ("why does this always pass in CI but the model is clearly non-deterministic in prod") | XS (docs only) | New, but small enough to just do — **done**, see README "Limitations" |

#### How item 1 was actually resolved

The design sketch below is kept for the record; each open question it raised was settled
as follows when `VcrFixtureRedactor` was built:

- **Request-side, response-side, or both?** One interface, one method, taking the whole
  `VcrTrack` (`VcrTrack redact(VcrTrack track)`) rather than a split
  `VcrRequestRedactor`/`VcrResponseRedactor` pair. A `canonicalRequest` string field also
  turned out to need redacting — it holds the same message text a `RequestSnapshot`
  redactor would already be scrubbing, just in a second place in the same fixture, so
  splitting by request/response wouldn't even have been enough; a redactor needs to see
  everything that gets written, in one pass.
- **Does redaction ever run on the replay path?** No — enforced structurally, not just
  documented. `DeterministicVcrAdvisor.applyRedactors()` is only ever called from the
  record path (`recordAndReturn()`); there is no code path that invokes a redactor
  during a hit, so a replay is physically incapable of being touched by one.
- **Naming clash risk with `VcrPromptNormalizer`.** Resolved with documentation, not a
  combined interface: `VcrFixtureRedactor`'s Javadoc and the README's new "Redacting
  fixture content" section lead with a direct comparison table (does it affect the hash?
  does it affect what a hit returns?) precisely because the two are easy to reach for
  interchangeably and only one of them is safe on real PII.
- **Does this replace the "raw prompt alongside normalized" open question?** Left open
  below, unchanged — a redactor makes storing the raw prompt *safer* if someone chooses
  to do it later, but doesn't by itself decide whether the project should.

One thing the original sketch didn't anticipate: the hash/schemaVersion enforcement
needed to be defense-in-depth, not just "the advisor doesn't call redactors on a hit."
A redactor's `redact()` method still receives and returns a full `VcrTrack`, meaning
nothing stops an implementation from returning a track with a different `hash()` —
so `applyRedactors()` explicitly discards whatever a redactor returns for `hash()` and
`schemaVersion()` and re-applies the original values after every redactor in the chain,
rather than trusting well-behaved implementations to leave those two fields alone.

#### Design note for item 3 — the `REPLAY_ONLY` escape hatch

The problem, precisely: CI runs with `spring.ai.test.vcr.mode=REPLAY_ONLY` so the whole
suite is sealed — a miss throws `VcrCacheMissException` rather than reaching a real
model. `VcrCacheMissException` itself already carries everything `CLAUDE.md`'s own
Javadoc conventions ask for (the hash, the expected file path, the full canonical request,
and the exact command to re-record — see `VcrCacheMissException`, unchanged by this item).
That part was never broken; what's missing is a way for *one* test to legitimately need a
live call — a smoke test against a real provider, or an assertion on something `VcrTrack`
deliberately drops (native usage objects, for instance) — without weakening the sealed
guarantee for the other few thousand tests in the same CI run.

Evaluated against three questions from `DISPATCH_PROMPT.md` Task 4 — does it keep CI
sealed *by default*, can it be abused to silently re-enable network calls for the *whole*
suite, and how much surface area does it add:

| Option | Sealed by default? | Whole-suite abuse risk | Surface area added |
|---|---|---|---|
| **a) Per-request override via an `AdvisorParams`-style mechanism** | Yes, in principle | Low in isolation, but only works if application code cooperates | Requires threading a param through the *production* `ChatClient.prompt()...advisors(...)` call in app code under test — directly at odds with `SpringAiVcrAutoConfiguration`'s own stated goal that "no test-only conditional appears anywhere" in production code |
| **b) `@Vcr(mode = ...)` JUnit 5 extension** | Yes — inert unless a test is annotated | Low — scoped to one test method/class, thread-local, automatically cleared after every test regardless of outcome | One annotation, one extension, one `ThreadLocal` holder; an optional `junit-jupiter-api` compile dependency (see below) |
| **c) A property listing exempt test classes** | Yes, if the list starts empty | Higher — a single shared list is exactly the kind of config that silently grows over time and is easy to forget to prune; unlike (b), nothing restores it automatically once a test no longer needs it | A class-name-matching mechanism wired into advisor construction, plus a process reminder to prune the list |
| **d) Do nothing — separate source set with the advisor absent entirely** | Yes, architecturally: no code path exists to bypass | None | A second source set/module, duplicated Spring wiring, and an argument that in-suite ergonomics for this case aren't worth it |

**Recommendation: (b).** (a) turns out worse than it first looks: it requires the
*application* code under test to explicitly opt in to being overridable, which means a
test can't unilaterally request a live call without also reshaping how production code
calls `ChatClient` — the opposite of this library's whole design principle of attaching
via `ChatClientBuilderCustomizer` so application code never has test-only branches. (c) is
the "blast radius creeps quietly" option — a shared list has no automatic cleanup the way
(b)'s `afterEach` does. (d) is the architecturally purest option, and was seriously
considered, but the concrete examples this item exists for (one smoke test, one
lossy-round-trip assertion) are individual test *methods*, not whole modules' worth of
work — a second source set is a heavier tool than the problem calls for. (b) matches the
scoping of (d) (opt-in, cannot leak beyond where it's applied) while keeping the
ergonomics of annotating just the one test that needs it, consistent with how
`VcrPromptNormalizer` and `VcrFixtureRedactor` are already small, additive, opt-in SPIs
rather than central configuration.

**One thing not anticipated before actually building it:** shipping `@Vcr` means shipping
a JUnit 5 dependency from *main* sources, not test sources — because `@Vcr` needs to be
usable from a *consuming* project's own tests, and this library's main sources are what
gets published as that project's `<scope>test</scope>` dependency. `pom.xml` gained
`org.junit.jupiter:junit-jupiter-api` as an `optional` compile dependency for this reason
— present so this module compiles, not force-propagated onto consumers, who already have
`junit-jupiter-api` on their test classpath via `spring-boot-starter-test` (or equivalent)
in any project that could use this library at all. This mirrors why
`spring-boot-autoconfigure` is already `optional` in this same `pom.xml`.

**Known limitation, documented rather than solved:** the override is a `ThreadLocal`, so
it only applies to whichever thread actually invokes the advisor. Synchronous, blocking
`ChatClient.call()` usage — everything this advisor currently supports, since `.stream()`
already passes straight through by design — satisfies this. Code that switches threads
(an async executor, a reactive chain) before reaching the advisor will not see the
override; that is a pre-existing constraint of this advisor being `CallAdvisor`-only, not
something this feature introduces.

#### Design note for item 5 — the CI workflow

Two findings, made by actually checking this repository rather than assuming, changed
the shape of this from what `STATUS.md`'s original task description asked for:

- **This repository commits zero fixtures of its own.** Every existing test —
  `DeterministicVcrAdvisorTests`, `VcrFixtureRedactorTests`, `SpringAiVcrAutoConfigurationTests`,
  `VcrModeExtensionTests`, `OllamaEndToEndTests` — uses `@TempDir` for its cache
  directory. So "fail if any *committed* fixture changed" has nothing to point at yet in
  this repo specifically. The gate is written as a whole-working-tree
  `git status --porcelain` check instead of one scoped to `src/test/resources/llm-cache/`
  — harmless today (that path doesn't exist), and it starts doing its intended job
  automatically the moment any fixture is committed here or in a project copying this
  workflow, with no logic change required.
- **`-Dspring.ai.test.vcr.mode=REPLAY_ONLY` would be decorative, not functional, for this
  repo's own suite.** None of the tests above read that property from the ambient
  environment — they either construct `DeterministicVcrAdvisor` directly in Java with an
  explicit `VcrMode`, or set the property themselves via `ApplicationContextRunner`'s
  `withPropertyValues(...)`, which in Spring's property-source precedence wins over
  anything passed as a JVM `-D` flag regardless. Adding the flag anyway would look like a
  meaningful CI safeguard while doing nothing — worse than not adding it. What actually,
  rigorously verifies `REPLAY_ONLY`'s sealed behavior is already in the suite that
  `mvn test` runs on every commit: `DeterministicVcrAdvisorTests#replayOnlyThrowsOnMiss`,
  `#replayOnlyServesHits`, and all of `VcrModeExtensionTests`. That is the actual
  verification this item asked for — it already existed before this item started, and CI
  running the full suite on every push is what keeps it continuously checked, not a flag
  bolted onto the `mvn test` invocation.

**Why the `e2e` job is nightly + `workflow_dispatch`, not per-PR:** measured locally, a
cold run (fresh container, model pulled and baked into a tagged image) took ~185s; a warm
rerun reusing that tag took ~50s. A GitHub-hosted runner starts a fresh VM per job with no
persistent Docker image cache between runs, so *every* invocation there pays roughly the
cold-run cost, not the warm one measured here — there is no way to get the fast path
without a custom image-layer caching step (saving/loading `ollama/ollama` layers via
`actions/cache`), which was left as a possible future optimization rather than built now,
since it adds real complexity for a job that doesn't need to run on every PR anyway.

**What was verified, and when:**

- ✅ The YAML parses (checked with PyYAML, before the first push).
- ✅ The drift-check shell logic: staged the workflow file, ran `mvn test` locally,
  confirmed `git status --porcelain` showed no changes beyond what was already staged —
  a healthy run really does leave the tree clean, so the gate isn't trivially broken by
  its own presence.
- ✅ **The workflow has actually run, repeatedly, on real GitHub Actions
  infrastructure**, at <https://github.com/rifatcakir/spring-ai-test-vcr/actions>. The
  `test` job has completed successfully on `push` (~30s per run). The `e2e` job has
  completed successfully both via the `schedule` trigger (confirming the default-branch
  cron-activation caveat below is now moot — it fired) and was exercised manually too.
- ✅ A real `ubuntu-latest` runner does resolve `spring-boot-dependencies:4.0.0` /
  `spring-ai-bom:2.0.0` / `spring-ai-ollama:2.0.0` correctly — the `test` job passing is
  direct evidence of this, not an inference.
- ✅ Testcontainers/Ryuk works on a hosted runner's Docker setup, and the real Ollama
  container + model pull completes within the job — the scheduled `e2e` runs succeeded
  (~1–2 minutes each), which is only possible if all of that worked end to end.
- ✅ The `schedule` (cron) trigger does fire once the workflow file is on the default
  branch — confirmed by observing actual `schedule`-triggered runs complete, not just
  inferred from GitHub's documented behavior.
- Not specifically inspected: whether `actions/setup-java`'s `cache: maven` step is
  actually hitting a warm cache versus downloading fresh every run — the jobs succeed
  either way, so this affects run time, not correctness, and wasn't dug into further.

### Nice-to-have (valuable, doesn't block a v0.1.0 tag)

| # | Feature | Why | Size | Source |
|---|---|---|---|---|
| 7 | **Hit/miss diagnostics** — a lightweight counter or listener (e.g. `VcrDiagnostics`) exposing hits/misses/records per test run | `CLAUDE.md`'s own testing convention demands "assert on chain invocation counts, not just response payloads." Right now consumers have no supported way to do the equivalent for their own tests short of re-implementing chain-count assertions by hand each time | S (half a day) | New |
| 8 | **`allow_playback_repeats`-style explicit opt-in for identical repeated calls** | Same request hash hit multiple times in one test today just replays every time (this already works, since it's file-based and stateless) — but there's no way to *assert* "this fixture was meant to be used exactly N times," which matters once diagnostics (item 7) exist | XS once item 7 lands | Ruby VCR |
| 9 | ~~**Publishing infrastructure**~~ **Build side done, nothing published** — `LICENSE`, Central-required `pom.xml` metadata, `release` profile (`maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`, `central-publishing-maven-plugin` — Sonatype's documented OSSRH successor, versions confirmed against real Central rather than guessed), `docs/PUBLISHING.md` walkthrough. `CONTRIBUTING.md` not started | Needed before a real `0.1.0` release. `mvn -Prelease package -DskipTests` verified to produce a clean `-javadoc.jar`/`-sources.jar` with zero Javadoc errors — the step most projects find broken only when they try to release for real. What's left (namespace verification, a GPG key, Sonatype token, `settings.xml` credentials, an actual `mvn deploy`) is all maintainer-only by design — an agent generating a key or writing credentials would mean secrets in a transcript and an irreversible public release happening without the maintainer directly in the loop | M | `STATUS.md` #8 |

### Future — needs its own design pass before any code is written

| # | Feature | Why it's parked | Source |
|---|---|---|---|
| 10 | **Streaming replay** (`.stream()` currently passes straight through) | `CLAUDE.md` already forbids touching this without reading `STATUS.md`'s note first: faking a token stream with a single-chunk `Flux` would make streaming tests pass while hiding exactly the chunk-boundary/timing/partial-tool-call bugs they exist to catch. Needs its own fixture schema field, not a bolt-on to `VcrTrack` | `STATUS.md` "Known risks" #3 |
| 11 | **Sequenced/scenario responses per hash** (WireMock- and VCR.py-style: same request, different response on the 2nd/3rd call — useful for testing retry and backoff logic) | Directly in tension with design rule #1 ("exact match only... never a close-enough hit") and the one-file-per-hash layout. Would need an explicit, clearly-opt-in fixture variant (e.g. a `sequence` array) so it can never silently change single-answer semantics for everyone else. Don't start without a design note weighing this against just writing N separate tests with N distinct prompts instead | New (from WireMock comparison) |
| 12 | **VCR support for non-chat models** (embedding, image, audio, moderation) | Explicitly out of scope today because none of these pass through the `ChatClient` advisor chain — each would need its own interception point and its own investigation into whether an equivalent advisor/interceptor API even exists in Spring AI 2.0 for that model type | `STATUS.md` "Scope limits" |
| 13 | **Pluggable fixture storage backend** (Polly.js "persister" style) | Contradicts design rule #5 (fixtures are pretty-printed, committed, and reviewed in PRs) — a pluggable backend invites un-reviewable fixtures. Only reconsider if a second real consumer needs it, which is the same trigger condition already named in `STATUS.md`'s multi-module open question | New (from Polly.js comparison), `STATUS.md` open question |

### Explicitly rejected — do not re-litigate

These are already decided in `CLAUDE.md`'s "Do not" list and are repeated here only so
this roadmap doesn't accidentally re-open them:

- Semantic / vector / similarity matching, at any threshold.
- TTL or time-based fixture invalidation.
- `ChatClientCustomizer`, `CallAroundAdvisor`, `AbstractAdvisor`, or
  `com.fasterxml.jackson.databind.ObjectMapper`.
- Auto-enabling the advisor by default (`spring.ai.test.vcr.enabled` must stay opt-in).

---

## LLM-specific concerns — where each one is actually handled

Called out separately because it's easy to plan a "VCR for LLMs" library by analogy to
HTTP VCR tools and quietly miss the parts that only exist because the thing being cached
is a model call, not a REST response.

| Concern | Current state | Gap, if any |
|---|---|---|
| **Streaming responses** | Passes through live, un-cached, by design | Item 10 above — deliberately parked |
| **Token/usage accounting** | `VcrTrack.ResponseSnapshot.usage` (`UsageSnapshot`: prompt/completion/total tokens) already captured and round-tripped | None currently known; provider-native usage objects are deliberately dropped (lossy by design, documented in README) |
| **Non-deterministic output** | This is the library's entire value proposition (freeze one sample, replay it) | Needs the caveat documented — item 6 above |
| **Embeddings** | Out of scope; embedding calls don't pass through `ChatClient`'s advisor chain | Item 12 above |
| **Tool / function calls** | **Verified against a real model.** `ToolDefinitionSnapshot` feeds the hash; `ToolCallSnapshot`/`ToolResponseSnapshot` (new in schema `"2"`) round-trip a message's own tool calls and tool results, not just the final response's; `VcrCacheKeyGenerator` now hashes a message's tool calls/responses explicitly instead of relying on `Message.getText()`, which is empty for both — the exact gap `OllamaToolCallingEndToEndTests` was written to close. `VcrScope`'s `INSIDE_TOOL_LOOP` vs `OUTSIDE_TOOL_LOOP` distinction is proven end to end: two model turns, two fixtures, zero network on replay, real `@Tool` method still re-invoked on replay | None currently known |
| **PII / secrets in prompt text** | Transport secrets (API keys, bearer tokens) never reach a fixture because interception is above HTTP — genuinely solved, better than VCR.py's manual `sk-...` scrubbing. Secrets/PII *inside the message text itself* now have a safe redaction path too: `VcrFixtureRedactor` (item 1, done) | None currently known |

---

## Suggested order

Re-sequenced once Docker Desktop was started and the e2e proof became unblocked.

1. **Done** — Item 6 (document non-determinism caveat, in README "Limitations").
2. **Done** — Item 2 (`ApplicationContextRunner` auto-configuration slice tests +
   config metadata).
3. **Done (core)** — Item 4 (e2e proof): `OllamaEndToEndTests` against a real
   Testcontainers-managed Ollama container proves record → replay → zero additional
   network requests on the hit. Chosen to run before the redactor/normalizer work
   (reversing the previous plan) precisely because it's the safety net for that next
   change: once it exists, any future change to the hashing/fixture-write path can be
   checked against a real model, not just mocks. Two narrower scenarios from the
   original scope (`REPLAY_ONLY` miss without touching the container, and the
   `INSIDE_TOOL_LOOP`/`OUTSIDE_TOOL_LOOP` counting-`@Tool` proof) are not yet added —
   tracked as a small follow-up in the same test class, not a blocker.
4. **Done** — Item 1 (`VcrFixtureRedactor`). Hard constraints from the acceptance bar were
   met and tested, not just asserted: `VcrFixtureRedactorTests#noRedactorMeansUnchangedFixture`
   proves the pre-existing constructor path and the new redactor-aware one (given an
   empty list) produce identical fixtures, and a companion characterisation test
   (`VcrCacheKeyGeneratorTests#hashIsPinnedForKnownInputs`) pins literal hash values
   independent of this feature. The hook is opt-in (default `List.of()`) and
   structurally unable to change the hash (enforced in `DeterministicVcrAdvisor`, not
   left to trust).
5. **Done** — Item 3 (`@Vcr(mode = ...)` escape hatch). Design note compared all four
   `DISPATCH_PROMPT.md` options against three questions (sealed by default? whole-suite
   abuse risk? surface area?) and recommended the JUnit 5 extension; implementation
   matched the recommendation. `VcrModeExtensionTests` proves the override works, that
   it does not survive into a later unannotated test (ordered explicitly so this is a
   real proof), that it still runs the full record path including a registered
   redactor, and class-level vs. method-level precedence.
6. **Done, verified on a real runner** — Item 5 (CI workflow). `.github/workflows/ci.yml`
   is live at <https://github.com/rifatcakir/spring-ai-test-vcr/actions>; both the `test`
   job (every push/PR) and the scheduled `e2e` job have completed successfully on
   GitHub-hosted infrastructure, not just a local dry run.
7. **Build side done** — Item 9 (publishing). What's left is entirely the maintainer's
   own action (namespace verification, GPG key, Sonatype token, the real release
   commands) per `docs/PUBLISHING.md` — not a further design or code item.
8. Items 7–8 (diagnostics) — opportunistic, whenever convenient.
9. Items 10–13 — not started without a dedicated design note each, per the table above.
   Batch-verification brainstorm explicitly excluded from this list — see
   `docs/BRAINSTORM.md`; no code planned there.

---

## Brainstorm

Two rougher ideas the maintainer wants to think through out loud — a lambda-based
callback/hook system, and single-LLM-call batch answer verification across a test run.
Neither has a decision yet; both are written up separately so they don't get mistaken
for committed roadmap items. See `docs/BRAINSTORM.md`.
