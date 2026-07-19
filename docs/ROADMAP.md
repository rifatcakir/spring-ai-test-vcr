# Roadmap

Last updated: 2026-07-19

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

Compiles, `mvn clean test` green (26/26). Nothing has been proven end-to-end against a
real model yet — every green test today is a unit test with a mocked `CallAdvisorChain`.
See `STATUS.md` for the full detail and the bugs fixed to get here.

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
| 1 | **Separate the cache-key normalizer from fixture redaction** *(new finding, not in either existing doc — promoted to top of the list because it's a design decision, not an implementation task; see the design sketch right after this table)* | `VcrPromptNormalizer` currently does double duty: it both stabilizes the hash against volatile values (dates, UUIDs) *and* is the only available tool for redacting PII/secrets from what gets written to a committed fixture. Per the README's own warning, normalizing away something the model conditions on makes two genuinely different requests share one fixture — so today, redacting a real PII field (say, a customer ID the model needs to behave differently for) to keep it out of a committed fixture **silently corrupts the cache**, which is exactly the failure mode rule #1 exists to prevent. VCR.py avoids this by keeping `match_on` (what decides a hit) and `before_record_request`/`filter_headers` (what gets written) as two independent hooks. This project needs the same split | M (1–2 days; touches `VcrCacheKeyGenerator`, `VcrTrackMapper`, `VcrProperties`, README) — **not started, awaiting sign-off on the design sketch below** | New |
| 2 | **Auto-configuration slice tests + `additional-spring-configuration-metadata.json`** | `SpringAiVcrAutoConfiguration` is untested; `@ConditionalOnMissingBean` override behavior and advisor ordering relative to `ToolCallingAdvisor` are asserted nowhere. The metadata file is what makes `spring.ai.test.vcr.*` show up with docs in an IDE — cheap to do alongside | S–M (1 day) | `STATUS.md` #3/#4, `DISPATCH_PROMPT.md` Task 3 |
| 3 | **`REPLAY_ONLY` escape hatch** — design note comparing the four options already listed in `DISPATCH_PROMPT.md` Task 4 (advisor-params override, `@Vcr` JUnit 5 extension, exempt-class property list, or "do nothing, use a separate source set"), then implement the chosen one | CI runs sealed (`REPLAY_ONLY`); there is currently no sanctioned way for a single test to make a live call without weakening the whole suite's guarantee. This is the single most VCR.py-like ergonomic feature missing (`@pytest.mark.vcr(record_mode=...)`) and it's already scoped as a design-first task — recommend the `@Vcr(mode=...)` extension unless the design note surfaces a reason not to | Design: hours. Impl: M (1 day) | `STATUS.md` #5, `DISPATCH_PROMPT.md` Task 4 |
| 4 | **Real end-to-end proof** — Testcontainers + Ollama, record on run 1, replay with zero network calls on run 2, `REPLAY_ONLY` throws on a changed prompt without touching the container, and an `INSIDE_TOOL_LOOP` vs `OUTSIDE_TOOL_LOOP` proof via a counting `@Tool` | Every green test today mocks the chain. Nothing has proven the library's actual reason to exist — replacing a real model call — works | M (1–2 days) — **BLOCKED: Docker Desktop daemon is not running in this environment** (`docker ps` fails to connect to `dockerDesktopLinuxEngine`; the CLI is installed). Start Docker Desktop to unblock; nothing else about this task is uncertain | `STATUS.md` #2, `DISPATCH_PROMPT.md` Task 2 |
| 5 | **CI workflow with a fixture-drift gate** — build on JDK 21, run with `-Dspring.ai.test.vcr.mode=REPLAY_ONLY`, fail if any committed fixture changed during the run | Turns "a fixture change in CI means someone bypassed review" from a stated intent into an enforced check. Needs items 2 and 4 done first so there's something meaningful to run in CI | S (a few hours once 2 and 4 land) | `STATUS.md` #6 |
| 6 | **Document the non-determinism caveat** — a fixture recorded at `temperature > 0` freezes one sample from a distribution; replay will make a flaky-in-production prompt look deterministically stable in tests, and that's a property of testing, not of the model | Near-zero cost, prevents a confused bug report down the line ("why does this always pass in CI but the model is clearly non-deterministic in prod") | XS (docs only) | New, but small enough to just do — **done**, see README "Limitations" |

#### Design sketch for item 1 (proposal only — nothing below is implemented or decided)

The problem in one sentence: there is currently no way to keep something *out of a
committed fixture* without also changing *whether two prompts hit the same cache entry*,
because both jobs run through the same `VcrPromptNormalizer` chain.

Proposed shape — a second, narrower SPI that runs only on the write path and never
touches the hash:

```java
@FunctionalInterface
public interface VcrFixtureRedactor {

    /**
     * Called only when about to write a fixture to disk, after the hash has already
     * been computed from the un-redacted canonical form. Anything this returns affects
     * what a reviewer sees in the committed JSON — never what gets replayed and never
     * the SHA-256 filename.
     */
    VcrTrack redact(VcrTrack track);
}
```

Registered the same way `VcrPromptNormalizer` beans already are today (collected into an
ordered `List<VcrFixtureRedactor>` by the auto-configuration, applied in `Ordered`
sequence). Open questions to settle before writing code:

- **Request-side, response-side, or both?** A redactor probably needs to see
  `RequestSnapshot` (to scrub PII the user sent) and `ResponseSnapshot` (in case the
  model echoes it back). One interface with one method taking the whole `VcrTrack`
  keeps it simple; a split `VcrRequestRedactor`/`VcrResponseRedactor` pair is more
  granular but doubles the SPI surface for a case that may not need it.
- **Does redaction ever run on the replay path?** It must not change what a hit
  *returns* — replay has to reproduce the exact recorded answer, or the cache stops
  meaning anything. So a redactor is write-only, by construction, and that constraint
  should probably be enforced in code (e.g. the advisor never calls it on a hit), not
  just documented.
- **Naming clash risk with `VcrPromptNormalizer`.** Two SPIs that both take text and
  both get called "normalize"/"redact" invite a user registering the wrong one for the
  job. Worth a clear doc table (or a single combined interface with two methods,
  `normalize()` vs `redactForStorage()`) rather than two same-shaped-but-different
  interfaces.
- **Does this replace or wrap the existing "raw prompt alongside normalized" open
  question in `STATUS.md`?** If a redactor exists, storing the raw prompt becomes safer
  (the redactor is the thing that would strip PII before it's committed) — worth
  resolving both open questions together rather than separately.

This needs a decision from whoever owns the API surface before any of it is coded — it's
listed here as a concrete starting point for that conversation, not a spec.

### Nice-to-have (valuable, doesn't block a v0.1.0 tag)

| # | Feature | Why | Size | Source |
|---|---|---|---|---|
| 7 | **Hit/miss diagnostics** — a lightweight counter or listener (e.g. `VcrDiagnostics`) exposing hits/misses/records per test run | `CLAUDE.md`'s own testing convention demands "assert on chain invocation counts, not just response payloads." Right now consumers have no supported way to do the equivalent for their own tests short of re-implementing chain-count assertions by hand each time | S (half a day) | New |
| 8 | **`allow_playback_repeats`-style explicit opt-in for identical repeated calls** | Same request hash hit multiple times in one test today just replays every time (this already works, since it's file-based and stateless) — but there's no way to *assert* "this fixture was meant to be used exactly N times," which matters once diagnostics (item 7) exist | XS once item 7 lands | Ruby VCR |
| 9 | **Publishing infrastructure** — Sonatype OSSRH coordinates, GPG signing, `maven-source-plugin`, `maven-javadoc-plugin`, `LICENSE` (Apache-2.0), `CONTRIBUTING.md` | Needed before a real `0.1.0` release, but only makes sense after the API stabilizes — no point signing and publishing something that changes shape next week | M | `STATUS.md` #7 |

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
| **Tool / function calls** | Modeled today: `ToolDefinitionSnapshot` (name/description/schema) feeds the hash, `ToolCallSnapshot` round-trips tool-call fixtures, and `VcrScope` (`OUTSIDE_TOOL_LOOP` / `INSIDE_TOOL_LOOP`) already decides whether replay skips or re-runs `@Tool` methods | Behavior is designed but **unproven** — folded into item 4's e2e test (the counting `@Tool` proof), currently blocked on Docker Desktop |
| **PII / secrets in prompt text** | Transport secrets (API keys, bearer tokens) never reach a fixture because interception is above HTTP — genuinely solved, better than VCR.py's manual `sk-...` scrubbing | Secrets/PII *inside the message text itself* have no safe redaction path today — that's item 1 above, the normalizer/redactor split, awaiting design sign-off |

---

## Suggested order

Re-sequenced after the redactor/normalizer finding and the Docker Desktop block below.

1. **Done** — Item 6 (document non-determinism caveat, in README "Limitations").
2. **Done** — Item 2 (`ApplicationContextRunner` auto-configuration slice tests).
3. Item 1 (redactor/normalizer design sign-off, then implementation) — sequenced ahead
   of the e2e test because it's a design decision that's cheap to make now and expensive
   to retrofit once fixtures exist that relied on the old, conflated behavior.
4. Item 4 (e2e proof) — **blocked**: requires Docker Desktop running. Unblock by
   starting Docker Desktop, then this is next.
5. Item 3 (`REPLAY_ONLY` escape hatch) — design note first, sign-off, then implement.
6. Item 5 (CI workflow) — once 3–4 exist to actually run.
7. Item 9 (publishing) — last, after the API has held still for a bit.
8. Items 7–8 (diagnostics) — opportunistic, whenever convenient.
9. Items 10–13 — not started without a dedicated design note each, per the table above.

---

## Brainstorm

Two rougher ideas the maintainer wants to think through out loud — a lambda-based
callback/hook system, and single-LLM-call batch answer verification across a test run.
Neither has a decision yet; both are written up separately so they don't get mistaken
for committed roadmap items. See `docs/BRAINSTORM.md`.
