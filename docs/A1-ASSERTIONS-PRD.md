# A1 — JSON / Structured Assertions: PRD

Status: **design only, no code written yet.** This document exists to be reviewed and
approved before any implementation starts, per `docs/ROADMAP.md` section 2 (A1) and
`docs/VISION.md` Layer 2. Every Spring AI type referenced below was confirmed by
unpacking `spring-ai-client-chat-2.0.0.jar` / `spring-ai-model-2.0.0.jar` /
`spring-ai-commons-2.0.0.jar` from the local `~/.m2` cache and reading the bytecode with
`javap -p` — the same discipline `CLAUDE.md`'s "VERIFIED API FACTS" table already applies
to Recorder. See the Appendix for the exact `javap` output this design is built on.

## 1. Problem

Today, a test that wants to check *what a model actually did* — not just "did I get a
non-empty string back" — has to hand-unwrap Spring AI's response object graph and reason
about raw JSON text itself. Concretely, this repository's own example project already
does this, in `ToolCallingRecordReplayTest`:

```java
// BEFORE — what a test author has to write today
ChatClientResponse response = chatClient.prompt().user(prompt).tools(orderStatusTool)
    .call().chatClientResponse();

Path cacheDirectory = Path.of("src/test/resources/llm-cache/tool-calling");
String combinedFixtureContent;
try (Stream<Path> fixtures = Files.list(cacheDirectory)) {
    combinedFixtureContent = fixtures.map(ToolCallingRecordReplayTest::readQuietly)
        .collect(Collectors.joining("\n"));
}
assertThat(combinedFixtureContent)
    .contains("\"name\" : \"getOrderStatus\"")
    .contains("orderId")
    .contains("ORD-4471");
```

This is worse than it looks, for four concrete reasons:

1. **It doesn't even assert against the response object** — it re-opens the *fixture
   files on disk* and does substring matching on their JSON text. That only works because
   this is a Recorder-backed test; a live (non-VCR) `ChatClient` call has no fixture file
   to grub through. This is explained in full in section 7 below — it is the single
   biggest thing A1 has to be honest about.
2. Even for the case where the tool call *is* visible on the response object
   (`ChatResponse.getResult().getOutput().getToolCalls()`), the caller has to know the
   exact unwrap chain: `.chatClientResponse().chatResponse().getResult().getOutput()
   .getToolCalls().get(0)`.
3. `AssistantMessage.ToolCall.arguments()` is a **raw, unparsed JSON string**
   (confirmed: `record ToolCall(String id, String type, String name, String arguments)`).
   `.contains("ORD-4471")` is a substring match on serialized text, not a real "does the
   `orderId` field equal `ORD-4471`" check — `{"orderId":"ORD-4471","note":"ORD-4471-x"}`
   would also pass that substring check, `{ "orderId" : "ORD-4471" }` (different
   whitespace, same meaning) would still pass but by luck, not by design.
4. A failure gives a generic AssertJ string-assertion message ("expected string to
   contain ..."), not something that tells you what tool *was* actually called.

The intended after-state (API detailed in section 3):

```java
// AFTER — proposed A1 API, operating directly on the response the caller already holds
VcrAssertions.assertThat(chatClientResponse)
    .hasToolCall("getOrderStatus", args -> assertThat(args).containsEntry("orderId", "ORD-4471"));
```

One call, no manual unwrap chain, arguments compared as parsed data rather than raw text,
and a failure message that names the tool calls that *were* found when the expected one
isn't. Section 7 explains exactly which real Spring AI response shapes this can and
cannot see through to.

## 2. Scope

**In scope for A1** (deterministic, no model call made by the assertion itself):

- Tool-call-shape assertions: was a specific tool called, with what (parsed) arguments,
  how many tool calls total, was a specific tool call absent.
- Finish-reason assertions (`ChatGenerationMetadata.getFinishReason()`).
- Text/content assertions that bridge into ordinary AssertJ string assertions (so A1
  doesn't need to reinvent string matching — see section 3).
- JSON-schema conformance for structured output (`entity()` / provider-native
  structured output) — validating the raw response text against a JSON Schema document,
  independent of whether the caller also converts it to a POJO. Open dependency
  question in section 7.
- Field-level assertions against parsed JSON (tool arguments today; response-body JSON
  more generally, scoped by whatever A1 ships first — see section 7).

**Explicitly out of scope for A1** (belongs to a later layer):

| Not A1 | Where it belongs | Why not A1 |
|---|---|---|
| "Is this answer *semantically* close to an expected answer" (cosine similarity, embeddings) | **A2** | Needs a live embedding call unless that call is itself Recorder-backed (blocked on R4 — see `docs/ROADMAP.md`). A1 must never make a model call to check an assertion; A2's whole reason to exist is doing exactly that safely |
| "Is this a faithful summary / not hallucinated / on-topic" (LLM-as-judge) | **E1** | Needs a second, judging model call — same non-determinism problem one level up, resolved in `docs/VISION.md` by routing the judge call through Recorder. A1 is pure data-shape checking with zero model calls, ever |
| Batch verification across many tests in one judge call | **E3** | Depends on E1 existing first |

The boundary rule, stated once so it doesn't need restating per assertion: **if checking
it requires calling a model, it is not A1.** A1 only ever looks at data that is already
sitting in the `ChatClientResponse`/`ChatResponse` object the caller passes in.

## 3. API design

### Entry point

```java
package io.github.rifatcakir.springai.testtools.assertions;

public final class VcrAssertions {
    public static ChatClientResponseAssert assertThat(ChatClientResponse actual) { ... }
    public static ChatResponseAssert assertThat(ChatResponse actual) { ... }
}
```

Two overloads, not one, because both are things a real caller holds today, confirmed via
`ChatClient.CallResponseSpec` (`javap` output in the Appendix):

- `.call().chatClientResponse()` → `ChatClientResponse` (`record(ChatResponse
  chatResponse, Map<String,Object> context)`) — the richer object, includes advisor
  `context()`.
- `.call().chatResponse()` → `ChatResponse` directly — what most existing tests in this
  repo and its example project already reach for (see `VcrTrackStoreRoundTripTests`,
  which builds raw `ChatResponse` objects directly today).

Static-import friendly, same idiom as `org.assertj.core.api.Assertions.assertThat` and
consistent with how this codebase's own tests already write `assertThat(...)`.

### `ChatClientResponseAssert` / `ChatResponseAssert`

Both extend AssertJ's own `AbstractObjectAssert<SELF, ACTUAL>` — the standard AssertJ
extension idiom (custom assertion class + static entry point), not a bespoke fluent
interface reinvented from scratch. `ChatClientResponseAssert` delegates its tool-call/
text/finish-reason logic to the same package-private helper `ChatResponseAssertions`
that `ChatResponseAssert` uses directly against `actual.chatResponse()` — real shared
logic, not "three similar lines," so a shared helper is justified here rather than
duplicated.

Proposed methods (both classes expose the same set, `ChatClientResponseAssert` reading
through to its wrapped `ChatResponse`):

```java
// Tool calls — operate on ChatResponse.getResult().getOutput().getToolCalls()
hasToolCall(String name)
    // asserts at least one AssistantMessage.ToolCall with this name exists on the
    // *primary* result (ChatResponse.getResult(), i.e. generations.get(0) — see the
    // open question on multi-generation responses in section 7).
    // Failure message names the tool calls that were actually found, e.g.:
    // "expected a tool call to \"getOrderStatus\" but found: [getWeather]"

hasToolCall(String name, Map<String, Object> exactArguments)
    // name match AND the tool call's arguments, parsed from the raw JSON string via
    // tools.jackson.databind.json.JsonMapper (never com.fasterxml...ObjectMapper, per
    // this project's own pinned Jackson-3 rule), equal exactArguments exactly.

hasToolCall(String name, Consumer<Map<String, Object>> argumentsRequirements)
    // name match, then the caller's own AssertJ assertions run against the parsed
    // arguments map — e.g. args -> assertThat(args).containsEntry("orderId", "ORD-4471").
    // This is deliberately how "exact vs. partial matching" (open question #7 in the
    // original task) gets resolved: both exist, as two overloads, and the test author
    // picks per call site instead of A1 forcing one default on everyone.

hasNoToolCalls()
hasToolCallCount(int expected)

// Finish reason — ChatGenerationMetadata.getFinishReason()
hasFinishReason(String expected)

// Text — bridges into ordinary AssertJ string assertions instead of reinventing them
extractingText()   // returns AbstractStringAssert<?>, backed by AssistantMessage.getText()
    // e.g.: VcrAssertions.assertThat(response).extractingText().contains("shipped")

// JSON/schema — see the open dependency question in section 7 before this is final
matchesJsonSchema(String jsonSchemaDocument)
    // validates AssistantMessage.getText() (or, for a structured-output call, the same
    // text entity() would otherwise parse) against a caller-supplied JSON Schema
    // document. Independent of POJO conversion — useful specifically because entity()
    // silently defaults an unmatched field to null instead of failing loudly, which a
    // schema check would catch.
```

### Nested tool-call assertions — deliberately NOT a separate `ToolCallAssert` class

An earlier draft of this design considered `hasToolCall(name)` returning a nested
`ToolCallAssert` (mirroring AssertJ's own navigational assertions, e.g.
`assertThat(list).first()...`). Rejected for A1: it adds a second assertion class and a
"how do I get back to the parent assertion" navigation method for a benefit the
`Consumer<Map<String,Object>>` overload already gives you — arbitrary follow-up
assertions on the parsed arguments, using AssertJ's own `Map` assertions, with no new
type to learn. Revisit only if real usage shows the `Consumer` form is awkward in
practice.

### Package

`io.github.rifatcakir.springai.testtools.assertions` — sibling to
`...testtools.recorder`, per `docs/VISION.md`'s three-package layout. Single Maven
module (this project is not currently multi-module — `pom.xml` has one `<artifactId>`,
one `jar` packaging), so this is a new package inside the existing module, not a new
Maven module. If a future multi-module split happens, that's a separate, larger decision
outside A1's scope.

## 4. Determinism boundary

**A1 must never make a model call to evaluate an assertion.** Every method in section 3
reads data that already exists on the `ChatClientResponse`/`ChatResponse` object passed
in — `getResult()`, `getOutput()`, `getToolCalls()`, `getText()`,
`ChatGenerationMetadata.getFinishReason()`, all pure in-memory reads. `matchesJsonSchema`
validates against a document the *caller* supplies as a `String` argument — it does not
fetch a schema from anywhere, let alone call a model to produce one. This is what keeps
A1 usable identically against a live response and a Recorder replay (per `docs/VISION.md`
Layer 2's stated reason for Assertions being a separate layer from Recorder at all): the
assertion has no side effects and no network dependency regardless of how the response
it's checking was obtained.

## 5. Dependency on Recorder

Confirmed: **A1 requires no new Recorder capability.** It operates purely on the
already-existing `ChatClientResponse`/`ChatResponse` shape that Recorder already produces
identically on a live call or a replay — nothing in `VcrTrack`, `VcrCacheKeyGenerator`,
or `VcrTrackMapper` needs to change for A1 to exist. (Compare to A2, which *hard*-depends
on R4 not existing yet — A1 has no such blocker, matching `docs/ROADMAP.md`'s existing
"Depends on: Recorder only (already exists)" line for A1.)

## 6. Test strategy

A1's own tests need no real model and no Docker, because A1 is pure data-shape checking
against objects the test constructs directly — same style already used in this
repository's `DeterministicVcrAdvisorStructuredOutputTests` and
`VcrTrackStoreRoundTripTests` (hand-built `ChatResponse`/`Generation`/`AssistantMessage`
objects via their public builders/constructors, not a live call).

Concretely:

- Build a `ChatResponse` via `ChatResponse.builder().generations(List.of(new
  Generation(AssistantMessage.builder()...toolCalls(List.of(new AssistantMessage.ToolCall(...)))
  .build()))).build()` and assert `VcrAssertions.assertThat(response).hasToolCall(...)`
  succeeds/fails as expected — exact values, not `assertNotNull`, matching this project's
  own test-quality bar (`docs/STATUS.md`'s test-audit history).
- One test per method in section 3, plus explicit **negative** tests: `hasToolCall` on a
  response with no tool calls must fail with a message naming zero calls found;
  `hasToolCall("wrongName", ...)` against a response that called a *different* tool must
  fail and name the actual tool that was called (proving the "tell me what was actually
  there" requirement from section 1, not just "it failed somehow").
- `hasToolCall(name, Map)` and `hasToolCall(name, Consumer)` both need a test proving
  they parse `arguments()` (a raw JSON string) correctly, including a case where two
  differently-*serialized*-but-equal argument JSON strings (different key order or
  whitespace) both satisfy an exact-match assertion — this is the concrete
  "substring-match was never really checking the field" bug from section 1, and a test
  should prove A1 actually fixes it, not just that it compiles.
- `matchesJsonSchema` tests need a real, small JSON Schema document (Draft 2020-12, same
  as `BeanOutputConverter` emits) and both a conforming and a non-conforming response
  text, asserting pass and a descriptive failure respectively.
- No `@SpringBootTest`, no `Testcontainers`, no Ollama — these are unit tests against
  hand-built Spring AI domain objects, same category as `VcrCacheKeyGeneratorTests`.

## 7. Open questions / risks

**7.1 — The big one, verified by reading `ToolCallingAdvisor`'s bytecode: A1 cannot see
a tool call that a `ChatClient`'s auto-registered tool loop already resolved.**

`ToolCallingAdvisor` (`getOrder() == HIGHEST_PRECEDENCE + 300`, auto-registered per
`CLAUDE.md`'s own table) runs the *entire* tool-calling round trip internally —
`doInitializeLoop` → model call → `doGetNextInstructionsForToolCall` → tool execution →
recursion → `doFinalizeLoop` — before it ever returns a `ChatClientResponse` to the
caller. The `ChatClientAttributes` enum (confirmed via bytecode: `OUTPUT_FORMAT`,
`STRUCTURED_OUTPUT_SCHEMA`, `STRUCTURED_OUTPUT_NATIVE`, `TOOL_CALL_ADVISOR_AUTO_REGISTER`,
`TOOL_CALLING_ADVISOR_AUTO_REGISTER`) has **no key that stashes the intermediate tool
call(s) or their arguments** into the final response's `context()`. The final
`ChatClientResponse`'s own `ChatResponse.getResult().getOutput().getToolCalls()` is
**empty** — the loop already consumed it.

This is not hypothetical: it is *why* this repository's own
`ToolCallingRecordReplayTest` reads raw fixture files instead of asserting on the
response object (section 1's example). `hasToolCall(...)`, as designed, is well-defined
and genuinely useful for exactly the responses where a tool call is still the *terminal*
state of the object the caller holds — concretely:

- A raw `ChatModel.call(Prompt)` result (confirmed: `ChatModel.call(Prompt)` returns
  `ChatResponse` directly, no `ChatClient`/`ToolCallingAdvisor` in between) — the normal
  way to test a tool-calling *decision* in isolation, one turn at a time, without the
  built-in loop auto-executing and hiding it.
- Any custom advisor placed *before* `ToolCallingAdvisor` in the chain, inspecting the
  response as it passes through mid-loop.

It is **not** usable, as designed, against the final answer a normal
`chatClient.prompt()...tools(...).call()` returns once the built-in loop has already run
to completion — which is the exact case the example project's existing test needed to
work around. **Decision needed before implementation:** should A1's Javadoc/README simply
document this limitation honestly (recommended — matches this project's existing
"honest caveat" style in `docs/VISION.md`), or should A1 grow a second, explicitly
Recorder-aware assertion (e.g. `VcrAssertions.assertThatFixtures(cacheDirectory)...`) that
formalizes what `ToolCallingRecordReplayTest` already does by hand today? The latter
would blur A1 into depending on Recorder's fixture format, which `docs/VISION.md`
explicitly says Assertions should *not* need to know about. Recommendation: **document
the limitation, do not build the fixture-aware variant as part of A1** — revisit as a
separate, explicitly-scoped item only if real usage shows it's needed often.

**7.2 — `matchesJsonSchema` needs an external JSON Schema validation library.**

Nothing in `spring-ai-client-chat`/`spring-ai-model` validates a JSON document against a
JSON Schema document — Spring AI only *generates* schemas (`BeanOutputConverter`), it
never validates against one. A1 would need a small new test-scope (or even main-scope,
since assertions ship as library code, not test-only) dependency — e.g.
`networknt:json-schema-validator` (Draft 2020-12 support, matching what
`BeanOutputConverter` emits). **This is a new external dependency and should be called
out explicitly for approval before implementation**, not silently added. Alternative that
avoids a new dependency entirely: ship only a lighter-weight, Jackson-tree-based
"has field / field equals / field type is" assertion set for A1 v1 (using
`tools.jackson.databind.json.JsonMapper`, already a dependency), and defer full JSON
Schema conformance checking to a later A1 follow-up once the dependency question is
settled. **Recommendation: start with the Jackson-tree-based field assertions (no new
dependency) and treat full `matchesJsonSchema` as a fast-follow, not a hard A1
launch requirement** — this keeps A1's "S–M" size estimate honest.

**7.3 — Multi-generation responses.**

`ChatResponse.getResults()` can hold more than one `Generation` (n-best/parallel
completions) — `getResult()` returns one (empirically, the first). A1 v1, as designed,
asserts against `getResult()` only. Open question: is a "does *any* generation satisfy
X" or "do *all* generations satisfy X" assertion in scope for A1, or deferred until a
real multi-generation use case appears in this project? **Recommendation: defer** — no
test in this repository or its example project currently requests more than one
generation, so building for it now would be speculative.

**7.4 — Exact vs. partial tool-argument matching.**

Resolved in section 3, not left open: **both**, via two overloads
(`Map<String,Object>` for exact, `Consumer<Map<String,Object>>` for partial/custom).
Flagging here only so the PRD's own open-questions section doesn't silently drop a
question the original task explicitly asked to be answered.

## 8. Example project demonstration

A1 needs its own showcase test in the example project (`spring-ai-test-tools-example`),
mirroring how R1/R2 each got one, and honestly reflecting the 7.1 limitation rather than
hiding it:

- A new test built on a **raw `ChatModel.call(Prompt)`** (not the full `ChatClient` tool
  loop) demonstrating `hasToolCall("getOrderStatus", args -> ...)` against a response
  where the tool call is genuinely still the terminal, visible state — the case A1
  actually supports well.
- A short README note, in the same honest-dev-history style already used for the
  cross-platform fix, explaining *why* this test uses `ChatModel` directly instead of
  `ChatClient.tools(...)` — namely, that the built-in tool loop resolves and hides the
  call before a `ChatClientResponse`-level assertion could see it (section 7.1) — so a
  reader doesn't wonder why the new test looks structurally different from
  `ToolCallingRecordReplayTest`.
- `extractingText()` and `hasFinishReason(...)` demonstrated against the existing
  `RecordReplayBasicsTest`-style response, since those work identically regardless of
  the tool-loop question.

## Appendix — verified API facts this design is built on

Confirmed via `javap -p` against `spring-ai-client-chat-2.0.0.jar` /
`spring-ai-model-2.0.0.jar` / `spring-ai-commons-2.0.0.jar` (local `~/.m2` cache, same
jars this repository already builds against):

| Type | Confirmed shape |
|---|---|
| `ChatClientResponse` | `record(ChatResponse chatResponse, Map<String,Object> context)`, both accessors present |
| `ChatClient.CallResponseSpec` | Has all of: `chatClientResponse()`, `chatResponse()`, `content()`, `entity(Class/ParameterizedTypeReference/StructuredOutputConverter, optional Consumer<EntityParamSpec>)`, `responseEntity(...)` |
| `ChatResponse` | `getResult()` → single `Generation`; `getResults()` → `List<Generation>`; `hasToolCalls()`; `hasFinishReasons(Set<String>)`; `getMetadata()` → `ChatResponseMetadata` |
| `Generation` | `getOutput()` → `AssistantMessage`; `getMetadata()` → `ChatGenerationMetadata` |
| `AssistantMessage` | `getToolCalls()` → `List<ToolCall>`; `hasToolCalls()`; `getText()` (inherited from `AbstractMessage`) |
| `AssistantMessage.ToolCall` | `record(String id, String type, String name, String arguments)` — `arguments` is a **raw JSON string**, not parsed |
| `ChatGenerationMetadata` | `getFinishReason()` → `String`; also a generic `get/getOrDefault/containsKey/keySet/entrySet` map-like surface; `.NULL` constant |
| `ChatModel` | `call(Prompt)` → `ChatResponse` directly — no `ChatClient`/`ToolCallingAdvisor` tool loop involved at this level |
| `ChatClientAttributes` | Enum values: `OUTPUT_FORMAT`, `STRUCTURED_OUTPUT_SCHEMA`, `STRUCTURED_OUTPUT_NATIVE`, `TOOL_CALL_ADVISOR_AUTO_REGISTER`, `TOOL_CALLING_ADVISOR_AUTO_REGISTER` — **no key carries intermediate tool-call history** into a final response's `context()` |
| `ToolCallingAdvisor` | `getOrder()` returns a constant `DEFAULT_ORDER`; runs `doInitializeLoop` → model call → `doGetNextInstructionsForToolCall` → tool execution → `doFinalizeLoop` entirely internally before returning to the caller — confirms 7.1 |
| `ToolCallingChatOptions` / its `Builder` | No "disable internal tool execution" flag exists on this interface in 2.0.0 — confirms the only way to see an unresolved tool call today is a raw `ChatModel` call, not a `ChatClient` option toggle |

If any of the above is re-verified and found to have changed by the time A1 is actually
implemented, update this table in the same commit that changes the implementation —
same rule `CLAUDE.md` already applies to the Recorder-layer table.
