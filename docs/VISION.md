# Vision

Last updated: 2026-07-22

## What this project is becoming

`spring-ai-test-tools` (Maven artifactId; GitHub repo `rifatcakir/spring-ai-test-tools`,
renamed to match) is not just a VCR library. It is meant to become a three-layer test-and-evaluation toolkit for
Spring AI, at the same abstraction level Spring AI itself operates at (the `ChatClient`
advisor chain), rather than at the HTTP socket layer WireMock and VCR.py work at. Each
layer is its own Java package under `io.github.rifatcakir.springai.testtools`:

```
io.github.rifatcakir.springai.testtools
├── recorder     <- exists today. Everything in this repo so far.
├── assertions   <- roadmap, not built.
└── evaluator    <- roadmap, not built.
```

## Layer 1 — Recorder (exists)

Deterministic, file-based record-and-replay for `ChatClient` calls. This is the entire
codebase as of this writing: `VcrMode`, `VcrScope`, `DeterministicVcrAdvisor`, `VcrTrack`
and its fixture format, `VcrPromptNormalizer`, `VcrFixtureRedactor`, the `@Vcr` escape
hatch. See `README.md` and `docs/STATUS.md` for what it actually does today.

Its job, stated narrowly: given the same canonical request, always return the same
recorded response, with no network call, no container, no token spend, and no flakiness —
so that everything built on top of it (Assertions, Evaluator) gets to run in CI without
ever touching a real model.

## Layer 2 — Assertions (roadmap)

Structured assertions on a `ChatClientResponse` that go beyond string equality —
tool-call-shape assertions, JSON-schema conformance on structured output, "did this
response cite required system context," and so on. Not started. No design has been done
here yet beyond the name; do not assume any API surface for it exists.

The reason this is a *separate* layer from Recorder rather than folded into it: an
assertion library doesn't need to know how a response was obtained (real call vs. replayed
fixture) — it should work identically against a live `ChatClientResponse` and a replayed
one. Recorder's job ends at "produce the same response deterministically"; Assertions'
job starts at "is this response actually correct."

## Layer 3 — Evaluator (roadmap)

Where this gets interesting, and where the project stops being "VCR, but for Spring AI."
Some correctness properties can't be checked by a plain assertion — the interesting cases
in LLM application testing are usually "is this answer *semantically* right," "did this
response stay on-topic," "is this a faithful summary" — the kind of check that itself
needs another LLM call (LLM-as-judge) or an embedding-based semantic-similarity check to
answer. `docs/BRAINSTORM.md`'s "batch LLM-judge verification" idea lives here.

**The critical design tension, stated plainly:** this project's Recorder exists
specifically *because* non-deterministic, networked, token-costing model calls are
unacceptable in a CI test. An Evaluator that calls out to a judge model to score a
response has exactly that same problem, one level up — a "deterministic" test whose
pass/fail depends on an evaluator LLM's live, non-deterministic judgment is not actually
deterministic at all, and it reintroduces every problem (network flakiness, token cost, a
model that drifts across provider versions) that Recorder was built to eliminate.

**The resolution: the Evaluator's own judge calls must themselves go through the
Recorder.** An Evaluator is not a peer of Recorder that happens to sit next to it — it is
a *consumer* of it. When an Evaluator scores a response by asking a judge model "is this a
faithful summary of X, yes or no," that judge call is itself just another `ChatClient`
call, and it gets cached and replayed exactly like the call under test. This is the whole
reason Recorder is the foundational layer rather than one of three independent
peers: **Recorder is what makes Evaluator CI-safe at all.** Without it, "semantic
correctness testing" would mean either a real network call and real token spend on every
CI run, or accepting that the judge's verdict itself is not reproducible between runs —
both unacceptable for the same reasons a non-deterministic model call under test is
unacceptable in the first place.

Concretely, this means an Evaluator built later should not invent its own caching
mechanism — it should be built as a normal `ChatClient` consumer sitting behind the same
`DeterministicVcrAdvisor` (or a sibling advisor built the same way) that Recorder already
provides. Nothing about Recorder's design should need to change to support this; it was
already written generically over "a `ChatClient` call," not over "the call under test"
specifically.

## Positioning: not WireMock for AI

WireMock (and VCR.py, Ruby VCR, Polly.js — see `docs/ROADMAP.md`'s prior-art table) solve
"stop this test from making a real HTTP call." That is a real problem and Recorder solves
the equivalent of it for Spring AI. But a WireMock-shaped tool stops at "replay the same
bytes" — it has no opinion about whether the bytes were *correct*, because for a typical
REST API under test, correctness is usually a separate, cheap, deterministic assertion
(status code, JSON shape) that doesn't need an LLM to check.

LLM application testing doesn't have that luxury: often the only way to check "is this
response good" is itself a judgment call, sometimes one that needs another model to make.
That is not a gap WireMock's model was ever meant to fill, and bolting a semantic-judge
feature onto an HTTP-replay tool would be solving it at the wrong layer — HTTP bytes don't
know about `ChatClientResponse`, tool calls, or Spring AI's own abstractions. Operating at
the advisor layer (Recorder's existing design choice, made from day one for exactly this
reason) is what makes an Evaluator layer buildable on top at all: it already speaks in
`ChatClientRequest`/`ChatClientResponse`, not raw HTTP.

So the intended positioning is: **a test-and-evaluation toolkit for Spring AI**, at the
abstraction level Spring AI itself works in — not a narrower "VCR clone that happens to
target LLMs."

## Honest caveat: provider independence is a goal, not a proven fact

Recorder is designed to be provider-agnostic — it intercepts at the `ChatClient` advisor
layer, above any provider-specific HTTP client, and `VcrTrack` never round-trips a
provider-native object. That design intent is real. What is **not** yet true is empirical
proof of it: every end-to-end test in this repository (`OllamaEndToEndTests`,
`OllamaToolCallingEndToEndTests`) runs against real Ollama only. Nothing has been verified
against OpenAI, Anthropic, Azure OpenAI, Bedrock, or any other Spring AI chat model
implementation. It is entirely possible a provider-specific quirk (a different tool-call
argument encoding, a metadata field this project doesn't know to expect) surfaces the
first time this runs against a second provider. Until that test exists, "provider
independent" is a claim about the design, not a verified property of the software — do
not represent it as more than that in README or marketing copy.

## What this document is not

This is not a committed roadmap with sizes and sequencing — that's `docs/ROADMAP.md`,
which still only lists Recorder-layer work because Assertions and Evaluator have no design
yet. This document exists so that whoever designs Assertions or Evaluator later starts
from the constraint above (Evaluator's judge calls must flow through Recorder) rather than
rediscovering it, and so nobody accidentally builds Evaluator as a Recorder-independent
peer that quietly reintroduces the non-determinism problem this project exists to solve.
