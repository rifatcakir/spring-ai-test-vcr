# CLAUDE.md — spring-ai-test-vcr

Instructions for any agent working in this repository. Read `docs/STATUS.md` before
starting work; it records what is verified, what is not, and what to do next.

## What this project is

A lightweight, open-source Java library providing deterministic, file-based
record-and-replay (VCR pattern) caching for Spring AI integration tests.

First run calls a real model and writes the exchange to
`src/test/resources/llm-cache/{sha256}.json`. Subsequent runs replay from that file with
no container, no network, no tokens.

Prior art: [VCR.py](https://vcrpy.readthedocs.io/). We deliberately intercept at the
Spring AI **advisor layer** rather than the HTTP socket layer.

## Tech stack — pinned, do not "upgrade"

| | Version |
|---|---|
| Java | 21 |
| Spring Boot | 4.0.0 |
| Spring AI | 2.0.0 (GA, 12 June 2026) |
| Jackson | **3** (`tools.jackson.*`) |
| Build | Maven |

## VERIFIED API FACTS

These were confirmed by unpacking the published `spring-ai-*-2.0.0` jars from Maven
Central and inspecting the class constant pools. **Do not "fix" code to contradict this
table.** Much of it contradicts pre-2.0 tutorials, blog posts and model priors.

| Claim | Reality in Spring AI 2.0.0 |
|---|---|
| `CallAroundAdvisor` / `StreamAroundAdvisor` | **Removed.** Gone since 1.0.0-RC1. |
| `AbstractAdvisor` | **Does not exist.** Never has. |
| Advisor interfaces | `Advisor` → `CallAdvisor`, `StreamAdvisor`; `BaseAdvisor` extends both |
| Advisor method | `ChatClientResponse adviseCall(ChatClientRequest, CallAdvisorChain)` — **not** `aroundCall`; the reference docs show a stale name, the javadoc and bytecode agree on `adviseCall` |
| `BaseAdvisor` | `before()`/`after()` only — **always** calls the chain. Cannot short-circuit. Unusable for VCR. |
| `CallAdvisorChain` | `nextCall(ChatClientRequest)`, `copy(CallAdvisor)` |
| `ChatClientRequest` | `record(Prompt prompt, Map<String,Object> context)` — public canonical ctor |
| `ChatClientResponse` | `record(@Nullable ChatResponse chatResponse, Map<String,Object> context)` |
| `ChatClientCustomizer` | **`@Deprecated(since="2.0.0", forRemoval=true)`** → use `ChatClientBuilderCustomizer` |
| `ToolCallingChatOptions.getToolNames()` | **REMOVED.** Only `getToolCallbacks()` and `getToolContext()` remain. |
| `ToolCallingAdvisor` | Auto-registered, order `HIGHEST_PRECEDENCE + 300`, owns the tool loop |
| `AssistantMessage` | 4-arg ctor is `protected`; tool calls require `AssistantMessage.builder()` with `.content() .properties() .toolCalls() .media()` |
| `AssistantMessage.ToolCall` | `record(String id, String type, String name, String arguments)` |
| `Generation` | Ctors `(AssistantMessage)` and `(AssistantMessage, ChatGenerationMetadata)` — both confirmed present |
| `ChatGenerationMetadata` | `.NULL` constant; `builder().finishReason(String).build()` (`finishReason` is on the **Builder**, not the interface) |
| `ChatResponse` | `builder().generations(List).metadata(ChatResponseMetadata).build()`; `getResult()`, `getResults()` |
| `ChatResponseMetadata` | `getId()`, `getModel()`, `getUsage()`; builder has `.id .model .usage .keyValue .metadata` |
| `ChatResponseMetadata` map access | `getOrDefault`, `getRequired`, `containsKey`, `keySet`, `entrySet` — **no plain `get(String)`** |
| `DefaultUsage` | `(Integer promptTokens, Integer completionTokens, Integer totalTokens)` |
| `ChatOptions` | `getModel getTemperature getTopP getTopK getMaxTokens getFrequencyPenalty getPresencePenalty getStopSequences`; static `builder()` |
| Jackson 3 | `tools.jackson.core:jackson-databind`; `tools.jackson.databind.json.JsonMapper`; throws **unchecked** exceptions (no checked `IOException` on mapper calls) |
| Jackson 3 annotations | still `com.fasterxml.jackson.annotation.*` — only databind/core moved |

**If you must change something in this table, verify it the same way first:**

```bash
curl -sO https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-client-chat/2.0.0/spring-ai-client-chat-2.0.0.jar
unzip -qo spring-ai-client-chat-2.0.0.jar -d x
strings x/org/springframework/ai/chat/client/advisor/api/CallAdvisor.class | grep advise
```

Then update this table in the same commit.

## Non-negotiable design rules

1. **Exact match only.** SHA-256 over the canonicalised prompt. No semantic similarity,
   no thresholds, no fuzzy matching, ever. A prompt that changed by one character must
   produce a new fixture or a loud failure — never a "close enough" hit. This is the
   whole reason the library exists instead of using Spring AI's semantic cache.
2. **Never serialise Spring AI domain types directly.** `ChatResponse`, `Generation`,
   `AssistantMessage` and `ChatResponseMetadata` are not safely round-trippable
   (protected ctors, `nativeUsage` holding raw provider SDK objects). Everything goes
   through the `VcrTrack` DTO.
3. **The canonical form is hand-assembled.** Never use `toString()`, `hashCode()`,
   reflection or Jackson to build a cache key. A fixture recorded today must still
   resolve after a JDK upgrade and a Spring AI point release.
4. **Escape message text before hashing.** An embedded newline must not be able to forge
   a canonical field (`x\nmodel=evil`). There is a test for this; keep it passing.
5. **Fixtures are pretty-printed and committed.** They get reviewed in pull requests. A
   readable diff is worth more than a small file.
6. **Writes are atomic** (temp file + move). Parallel Surefire runs must not leave a
   half-written fixture behind.
7. **A corrupt fixture degrades to a cache miss**, it does not fail the build. In
   `REPLAY_ONLY` the caller turns that absence into a loud failure anyway.
8. **Normalizers affect the hash and the fixture, never the live call.** The text sent to
   a real model on a miss is always the original prompt.

## Coding conventions

- Spring Framework code style: tabs, `this.` prefix on field access, `Assert.notNull` on
  public entry points, constructor injection, no field injection.
- Slf4j. Log every cache hit, miss, record and file operation. Hit/miss logs are at
  `INFO` with a short 12-char hash; file detail at `DEBUG`.
- Javadoc explains **why**, not what. Where a decision was non-obvious or contradicts an
  intuition (e.g. why `CallAdvisor` and not `BaseAdvisor`), say so in the Javadoc — that
  is the note that stops the next person from "simplifying" it back.
- JSpecify nullability is the Spring AI 2.0 baseline; prefer explicit null handling over
  `Optional` in hot paths, `Optional` at API boundaries.
- Tests use JUnit 5 + AssertJ + Mockito. **Mock `CallAdvisorChain` rather than
  implementing it** — the interface will drift and a hand-rolled stub will rot.
- Advisor tests must assert on **chain invocation counts**, not just response payloads. A
  test that only checks the returned text passes even when the cache is calling the real
  model every time.

## Do not

- Do not add semantic/vector/similarity matching.
- Do not add a TTL or time-based invalidation.
- Do not use `ChatClientCustomizer`, `CallAroundAdvisor`, `AbstractAdvisor`, or
  `com.fasterxml.jackson.databind.ObjectMapper`.
- Do not make the auto-configuration active by default. `spring.ai.test.vcr.enabled` must
  be explicitly `true`.
- Do not implement streaming replay without reading the note in `docs/STATUS.md` first.
- Do not commit fixtures generated by `RECORD_ALWAYS` without reviewing the diff.
