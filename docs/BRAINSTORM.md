# Brainstorm

Last updated: 2026-07-19

**Status: raw ideas, no decisions.** Nothing here is scheduled, sized, or committed to.
This file exists so two rougher directions the maintainer wants to think through don't
get mixed up with `docs/ROADMAP.md`'s prioritized, sized feature list. If either idea
below turns into something worth building, it should graduate to `ROADMAP.md` with a
size estimate and a rationale — until then it stays here as thinking material.

---

## 1. A lambda-based callback/hook system

### The gap this would fill

Today there is exactly one extension point: `VcrPromptNormalizer`, applied to message
text before hashing, in `Ordered` sequence. `docs/ROADMAP.md`'s must-have item #1 already
identifies the sharpest problem with that — it does double duty as both the cache-key
stabilizer and the only available redaction tool, and using it for redaction can silently
corrupt the cache. That item proposes one narrow fix: a second SPI,
`VcrFixtureRedactor`, for write-path-only redaction.

This brainstorm is the broader version of that question: rather than adding one more
single-purpose interface, should there be a small, general **hook system** — a family of
lambda-shaped extension points a consumer can register as beans, each firing at a
specific point in the record/replay lifecycle? This is the Java equivalent of VCR.py's
`before_record_request` / `before_record_response` / `before_playback` hooks, which are
themselves more general than `match_on` (the request matcher).

### Candidate lifecycle points

Thinking through where hooks could fire, in the order a request actually flows through
`DeterministicVcrAdvisor.adviseCall`:

1. **Before hashing** — this is `VcrPromptNormalizer` today. Stays hash-affecting,
   by design; this is the "does this count as a cache hit" decision.
2. **Before writing a fixture** (on a miss, after the real call returns) — this is the
   proposed `VcrFixtureRedactor` from `ROADMAP.md`. Hash-inert: changes what gets
   committed, never what gets replayed or what key it's filed under.
3. **Before returning a replayed response to the chain** (on a hit) — not currently
   proposed anywhere. Would let a consumer, say, inject a fresh request-scoped ID into a
   replayed response, or assert something about the fixture being served without
   touching its content. Riskiest of the three: if this hook is allowed to *change* the
   returned response, replay stops being a faithful reproduction of what was recorded,
   which undermines the entire point of a cache. Whether this point should exist at all
   is more open than the other two.
4. **On a miss in `REPLAY_ONLY`** (before throwing `VcrCacheMissException`) — could let a
   consumer attach extra diagnostic context to the exception, or route it somewhere
   before it propagates. Low value against the complexity of adding a hook just for an
   exception path; probably better served by the exception's existing constructor
   parameters (hash, path, canonical request) being descriptive enough already.

Point 3 in particular starts to look like a "man in the middle" for replay, which cuts
against `CLAUDE.md`'s rule that "a corrupt fixture degrades to a cache miss" and rule #7
generally — the library's whole value is *not* having behavior that varies at replay
time. Worth being skeptical of point 3 specifically rather than assuming a symmetric
before/after hook pair is obviously correct just because it's tidy.

### Proposed shapes (illustrative, not decided)

```java
@FunctionalInterface
public interface VcrFixtureRedactor {
    // Fires only on write, after the hash is already computed. Never affects replay.
    VcrTrack redact(VcrTrack track);
}
```

A broader, single "hook bundle" interface is also worth weighing against several
narrow ones:

```java
public interface VcrRecordingListener {
    default void beforeWrite(VcrTrack track) { }
    default void afterHit(VcrCacheKey key, VcrTrack track) { }
    default void afterMiss(VcrCacheKey key) { }
}
```

The narrow-interfaces-per-concern approach (à la `VcrPromptNormalizer`,
`VcrFixtureRedactor`) keeps each hook's contract easy to state precisely (this one is
hash-affecting, this one never is). A single multi-method listener interface is more
extensible without breaking existing implementers when a new lifecycle point is added,
at the cost of a fatter interface where most consumers only care about one method.
Spring AI itself leans toward small single-method interfaces (`CallAdvisor`,
`StreamAdvisor`, `VcrPromptNormalizer` already follows this) — consistency with that
precedent probably favors narrow interfaces here too, but this is exactly the kind of
call that should be made once, deliberately, rather than accreted method-by-method.

### Wiring into auto-configuration

Whatever shape is chosen, registration should mirror how `VcrPromptNormalizer` beans are
already collected today: an `ObjectProvider<List<T>>` (or plain `List<T>` constructor
parameter) gathered by `SpringAiVcrAutoConfiguration`, applied in `Ordered` sequence,
with `@ConditionalOnMissingBean` staying meaningless for a `List<T>` (there's no
"default" to override — the list is just empty if nothing is registered, which is
already how normalizers behave with zero beans).

### Open questions

- Is a single `VcrFixtureRedactor` (`ROADMAP.md` #1) enough, or does the broader
  lifecycle-hook idea pull its weight? Shipping just the redactor first and revisiting
  the rest only if a real need shows up is the more conservative path, and matches this
  project's general bias toward not building speculative extension points.
- Does a replay-time hook (candidate point 3 above) belong in this library at all, given
  it's in tension with "replay must be a faithful reproduction"? Leaning no, but flagged
  as an open question rather than settled.
- Should hooks be able to throw, and if so, does a hook exception during recording fail
  the test, or degrade like a corrupt fixture does? `CLAUDE.md` rule #7 ("a corrupt
  fixture degrades to a cache miss, it does not fail the build") suggests hook failures
  during *write* should probably not fail an otherwise-successful real model call, but
  this needs a real decision, not an assumption.

---

## 2. Batch test-output collection + single-LLM-call answer verification

> **Status update:** this idea is no longer purely open-ended — it's tracked as **E3** in
> `docs/ROADMAP.md`'s "The layered roadmap" section, scheduled *after* E1 (the single-call
> LLM-as-judge mechanism) is built and proven in production use, and explicitly requiring
> its own design note before any code, for exactly the reasons laid out below. Nothing
> below has been decided or resolved by that scheduling — it only means "not forgotten,"
> not "de-risked."

### The idea

Instead of each test individually asserting on an LLM's output (exact string match,
regex, a hand-written predicate), collect `(test name, prompt, actual output, expected
criteria)` tuples across many tests in a run, then make **one** LLM call at the end
asking it to judge all of them together — "does each actual output satisfy its stated
criteria? Return true/false with a one-line reason for each" — instead of one
LLM-as-judge call per test.

### Why this looks appealing

- **Fewer LLM calls** if a suite has many "LLM-as-judge"-style assertions — one round
  trip instead of N.
- **Centralizes the judge prompt.** One place to version, tune, and review the judging
  instructions, rather than N call sites each rolling their own.

### Why it needs real scrutiny before it's more than an idea

- **Context contamination.** Batching N unrelated test cases into one prompt risks the
  judge's assessment of case *k* being influenced by whatever sits next to it in the
  same prompt — anchoring or contrast effects where a batch of mostly-good outputs makes
  a borderline one look better by comparison, or vice versa. This is a real, documented
  failure mode of "judge several things in one call" setups, not a hypothetical one.
- **Traceability.** If test A fails, which test actually failed needs to be unambiguous
  from the judge's response. That requires a strict, validated per-item output schema
  (e.g., a JSON array keyed by test id, with the returned count checked against the
  submitted count) — and even then, a single malformed or truncated judge response could
  turn N genuinely failing tests into "inconclusive," silently. That is precisely the
  failure mode `CLAUDE.md` rule #7 exists to prevent for fixtures ("a corrupt fixture
  degrades to a cache miss, it does not fail the build" — the build is still supposed to
  notice). A batch verifier that can go quietly ambiguous on partial failure would be a
  regression against that principle, not an extension of it.
- **Recursion — should the verification call itself be cached by VCR?** Two answers,
  both bad in a different way:
  - **Yes:** once recorded, the "verification" fixture is frozen. Every subsequent run
    replays the same frozen judgment forever, regardless of what the tests being judged
    actually produced that run. At that point it isn't verifying anything — it's a
    tautology that always agrees with whatever it agreed with the first time it ran.
  - **No:** the judge call is live on every run, which reintroduces exactly the cost,
    network dependency, and non-determinism this whole library exists to eliminate — now
    one layer up, for verification instead of generation. A `REPLAY_ONLY` CI run that's
    supposed to make zero network calls would need an explicit, documented carve-out for
    the verifier, undermining the "sealed CI" guarantee this project is built around.
  
  Neither answer is clearly right, which is itself the strongest signal that this needs
  a real design pass rather than an assumption either way.
- **The judge is itself non-deterministic.** "Did this test pass" becomes a second-order
  LLM call, meaning pass/fail can now be flaky at the judging layer even when the
  application layer is perfectly deterministic (e.g. because it's itself replayed from a
  VCR fixture). A maintainer now has two independent layers to disbelieve when a test
  flakes, which is arguably a worse debugging experience than today's single layer.
- **Batch size limits.** Context window ceilings cap how many test outputs fit in one
  judge prompt; the contamination risk above gets worse as batch size grows, and one
  ambiguous or adversarial case in the middle of a large batch could degrade judgment
  quality for cases around it, not just itself.
- **The claimed cost/latency win needs validating, not assuming.** LLM providers
  typically bill per input+output token, not per call — so one large prompt holding N
  cases costs roughly the same input tokens as N separate small judge calls would,
  absent a provider-specific batch discount (some providers do offer genuine batch APIs
  with a real price break; that's a separate, specific thing to check against, not
  something to assume applies here). The actual saving is mostly in **latency** (one
  round trip instead of N) and **less judge-prompt boilerplate repeated N times** — real
  benefits, but a different, smaller claim than "cheaper," and worth stating precisely
  rather than leading with "cost savings."
- **Is this even in scope for this library?** `spring-ai-test-vcr`'s stated job is
  deterministic caching of model calls, not test assertion strategy. LLM-as-judge batch
  verification is an orthogonal concern — arguably a separate, sibling tool that a test
  suite might use *alongside* this one, not a feature this library should own. Worth
  deciding explicitly rather than growing this project sideways into "also does
  assertions" scope creep. If the answer is "separate tool," the interesting connection
  point is probably just: should *that* tool's own judge calls be VCR-cacheable through
  this library's existing advisor, which is a much smaller and more answerable question
  than "should this library implement batch verification."

### Open questions

- Does this belong in `spring-ai-test-vcr` at all, or as a separate library that happens
  to compose with it?
- If pursued: yes-cache or no-cache for the verification call itself, and if cached, how
  does re-verification ever mean anything again?
- What does a validated, fail-loud (not fail-ambiguous) batch response schema look like,
  and what happens on a partial parse failure — does the whole batch fail, or only the
  unparseable entries?
- Has anyone measured actual latency/cost against N individual judge calls for a
  representative batch size, on a specific provider's pricing, before claiming this is a
  win at all?
- Is there prior art worth studying here specifically (e.g., how existing "LLM-as-judge"
  evaluation frameworks — Ragas, DeepEval, promptfoo's assertion runners — handle batching,
  if they do)? Not yet researched; flagged as the next step if this idea is pursued
  further rather than shelved.
