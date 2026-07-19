# Dispatch prompt

Copy the block below into Dispatch. It assumes the repo is pushed and Dispatch has it
checked out.

---

## Task 1 — make it compile and go green (start here)

```
You are a Principal Java Architect working on spring-ai-test-vcr, a library providing
deterministic file-based record-and-replay (VCR) caching for Spring AI integration tests.

Read CLAUDE.md and docs/STATUS.md first. CLAUDE.md contains a verified API reference table
for Spring AI 2.0.0 that was confirmed by unpacking the published jars. It contradicts most
online tutorials and probably your own priors — trust the table over your instincts, and if
you find it wrong, verify against the real jar before changing anything and update the table
in the same commit.

The codebase has never been compiled. The authoring environment had no Maven and only
JDK 11. Your job is to make `mvn clean test` pass.

Scope:
1. Run `mvn clean test` and fix compile errors. Expect mismatches in generic signatures,
   parameter order and nullability — the class and method NAMES are verified, their exact
   signatures are not.
2. Get all 26 existing tests green. Do NOT weaken a test to make it pass. If a test is
   wrong, fix the test and say why in the commit message. If it exposes a real bug in the
   main code, fix the main code.
3. Update the verified-API table in CLAUDE.md with anything you learn, and update the
   "Known risks" section of docs/STATUS.md to reflect reality after the build is green.

Hard constraints (from CLAUDE.md, repeated because they matter most):
- Do NOT use CallAroundAdvisor, StreamAroundAdvisor, AbstractAdvisor, or ChatClientCustomizer.
- Do NOT switch BaseAdvisor in for CallAdvisor. BaseAdvisor always calls the downstream
  chain; this advisor's entire purpose is to be able to not call it.
- Do NOT use com.fasterxml.jackson.databind.ObjectMapper. Jackson 3, tools.jackson.*.
- Do NOT add semantic similarity, fuzzy matching, or TTL. Exact SHA-256 match only.
- Do NOT serialise Spring AI domain types directly. Everything goes through VcrTrack.

Report at the end: what you changed, which of my API assumptions were wrong, and anything
in docs/STATUS.md's risk list you were able to close out.
```

## Task 2 — end-to-end proof with Testcontainers + Ollama

Run only after Task 1 is green.

```
Read CLAUDE.md and docs/STATUS.md.

The unit tests prove the pieces work in isolation. Nothing yet proves the library actually
works end to end. Write that test.

Add an integration test using Testcontainers + Ollama with the smallest viable model
(qwen2.5:0.5b or similar):

1. Boot a Spring context with spring.ai.test.vcr.enabled=true and mode=RECORD_OR_REPLAY,
   pointing the cache directory at a JUnit @TempDir.
2. First run: call a real ChatClient, assert a fixture file appears.
3. Second run with an identical prompt: assert the response matches AND that the Ollama
   container received exactly ONE request total across both runs. Assert on request count
   at the container or a wrapping ChatModel spy — asserting only on response text would
   pass even if the cache were doing nothing, which is the failure mode that matters.
4. A third run in REPLAY_ONLY with a deliberately altered prompt must throw
   VcrCacheMissException without touching the container.
5. Tag it so it can be excluded from normal builds (@Tag("integration") + a Surefire
   profile). CI must not need a model container for the default build.

Also cover the OUTSIDE_TOOL_LOOP vs INSIDE_TOOL_LOOP distinction with a @Tool method that
increments a counter: on replay, OUTSIDE must leave the counter untouched, INSIDE must
increment it. That behavioural difference is currently documented but unproven.
```

## Task 3 — auto-configuration slice tests

```
Read CLAUDE.md and docs/STATUS.md.

Add ApplicationContextRunner slice tests for SpringAiVcrAutoConfiguration:

- absent when spring.ai.test.vcr.enabled is unset or false
- present when true, with DeterministicVcrAdvisor, VcrTrackStore, VcrTrackMapper and
  VcrCacheKeyGenerator all registered
- advisor order is below ToolCallingAdvisor's HIGHEST_PRECEDENCE+300 for
  scope=OUTSIDE_TOOL_LOOP and above it for INSIDE_TOOL_LOOP
- an explicit spring.ai.test.vcr.order overrides the scope-derived value
- a user-supplied @Bean of each type wins over the auto-configured one
  (@ConditionalOnMissingBean actually works)
- registered VcrPromptNormalizer beans are picked up by both the key generator and the
  mapper, in Ordered sequence

Then add src/main/resources/META-INF/additional-spring-configuration-metadata.json with
descriptions and defaults for every spring.ai.test.vcr.* property, so the properties get
IDE completion and hover docs.
```

## Task 4 — the REPLAY_ONLY escape hatch (design first, then build)

```
Read CLAUDE.md and docs/STATUS.md, then read the open question in STATUS.md about the
REPLAY_ONLY escape hatch.

Problem: CI runs in REPLAY_ONLY so no test can reach a real model. But occasionally one
test legitimately needs a live call — a smoke test against a real provider, or a test
asserting on something VcrTrack deliberately drops.

Do NOT write code yet. First produce a short design note in docs/ comparing at least these
options, with a recommendation:

  a) per-request override via the advisor params mechanism, mirroring Spring AI's own
     AdvisorParams.toolCallingAdvisorAutoRegister(false)
  b) a JUnit 5 extension + @Vcr(mode = BYPASS) annotation
  c) a property listing exempt test classes
  d) do nothing — argue that the need is a smell and such tests belong in a separate
     source set with the advisor disabled entirely

Evaluate each against: does it keep CI sealed by default, can it be abused to silently
re-enable network calls for the whole suite, and how much surface area does it add.
Then wait for my sign-off before implementing.
```

---

## Notes on running these

- Tasks 1 → 2 → 3 are sequential; Task 1 must be green before the others mean anything.
- Task 4 is a design task and deliberately stops before implementation.
- Each prompt is self-contained and re-states the hard constraints, because a dispatched
  agent starts cold and the constraints are the part most likely to be "helpfully"
  violated.
