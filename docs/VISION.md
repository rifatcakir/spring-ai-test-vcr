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

## Layer 3 — Evaluator (roadmap, reframed by a concrete finding)

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

**This is no longer a hypothetical resolution — Spring AI 2.0.0 already ships the
Evaluator this section describes, and it already fits the shape predicted above without
modification.** `org.springframework.ai.evaluation.Evaluator` (plus `EvaluationRequest`/
`EvaluationResponse`) and two ready-made implementations — `RelevancyEvaluator` and
`FactCheckingEvaluator` — live in `spring-ai-commons` and `spring-ai-client-chat`
respectively, both already transitive dependencies of this project (confirmed via `mvn
dependency:tree`; no new dependency needed). Both implementations are constructed from a
plain `ChatClient.Builder`, and both were confirmed via `javap -c` bytecode
disassembly — not assumed from documentation or class names — to do exactly
`chatClientBuilder.build().prompt().user(...).call().content()` internally: an entirely
ordinary `ChatClient` call, indistinguishable in shape from any call `DeterministicVcrAdvisor`
already intercepts. Wire the same `ChatClient.Builder` this library's
`ChatClientBuilderCustomizer` already attaches the advisor to into
`RelevancyEvaluator.builder().chatClientBuilder(...)` (or `FactCheckingEvaluator`'s
equivalent), and the evaluator's internal judge call is recorded and replayed by the
existing Recorder mechanism, with **zero new advisor, zero new fixture type, zero new
mechanism.**

Concretely, this changes what "building Evaluator" means: not "invent an LLM-as-judge
abstraction and a way to cache its calls," which is what this document originally
anticipated as the necessary work — but **"prove, and document, that Spring AI's own
official evaluators are already Recorder-backed for free once wired through the builder
this library already customizes."** The `docs/ROADMAP.md` Evaluator-layer table (E1/E2)
reflects this: E1 is now a proof-and-glue task sized **S**, not a from-scratch mechanism
sized **M**, and E2 is "demonstrate both official evaluators," not "invent hallucination/
toxicity judge prompts from nothing." A bespoke Evaluator implementation remains possible
for a criterion neither `RelevancyEvaluator` nor `FactCheckingEvaluator` expresses — but
that is now a fallback for a real, demonstrated gap, not the default plan.

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

## Honest caveat: provider independence is proven at the implementation level, not yet at the vendor level

Recorder is designed to be provider-agnostic — it intercepts at the `ChatClient` advisor
layer, above any provider-specific HTTP client, and `VcrTrack` never round-trips a
provider-native object. This is now empirically proven for a second, genuinely different
`ChatModel` implementation, not just claimed: `OpenAiViaOllamaEndToEndTests` runs
`OpenAiChatModel` — built in Spring AI 2.0 on the official OpenAI Java SDK
(`com.openai.client.OpenAIClient`, an OkHttp-based stack), architecturally distinct from
`OllamaChatModel`'s `RestClient`-based `OllamaApi`, not merely a different Spring AI
wrapper around the same transport — against Ollama's own OpenAI-compatible endpoint.
Two things were proven, not assumed: (1) record/replay works correctly through this second
implementation on its own, with zero additional requests on a hit; (2) a fixture recorded
through the *native* Ollama client replays, byte-for-byte identical, through the
*OpenAI-SDK* client too, at zero network cost — meaning the cache key genuinely does not
(and structurally cannot, since `VcrCacheKeyGenerator` reads only `ChatOptions` interface
getters, never `instanceof OllamaChatOptions`/`OpenAiChatOptions`) encode which Java class
or wire protocol reached the model. **No production code changed to make this pass** — the
advisor-layer design was already this provider-agnostic before this test existed; the test
only made that a demonstrated fact instead of an intended one.

**What is still not proven, and should not be overclaimed:** both provider paths in that
test talk to the exact same underlying model weights (`llama3.2:1b`, served by the same
Ollama instance) — one via Ollama's native API, one via its OpenAI-compatibility layer.
This proves implementation/transport independence, not independence from an actually
different vendor's model. Nothing in this repository has been verified against a real
OpenAI, Anthropic, Azure OpenAI, or Bedrock account with a genuinely different model
behind it — that would need real credentials and real token spend, deliberately out of
scope for a proof that was designed to need neither. A provider-specific quirk unique to
those real backends (a different tool-call argument encoding a real GPT/Claude model
happens to produce, say) remains a theoretical, unverified risk. Don't represent
"provider independent" as more than "proven across two different Spring AI `ChatModel`
implementations talking to the same model; unverified against a genuinely different
model vendor" in README or marketing copy.

## What this document is not

This is not a committed roadmap with sizes and sequencing — that's `docs/ROADMAP.md`.
Assertions (A1) now has real code and a real design (`docs/A1-ASSERTIONS-PRD.md`);
Evaluator now has a concrete, evidence-based reframing (Layer 3 above) but no code yet.
This document exists so that whoever implements Evaluator next starts from the
constraint above (its judge calls must flow through Recorder) and the concrete finding
that Spring AI's own official evaluators already satisfy it, rather than rediscovering
either — and so nobody accidentally builds a bespoke Evaluator mechanism as a
Recorder-independent peer that quietly reintroduces the non-determinism problem this
project exists to solve, when Spring AI's own `RelevancyEvaluator`/`FactCheckingEvaluator`
already avoid that trap for free.
