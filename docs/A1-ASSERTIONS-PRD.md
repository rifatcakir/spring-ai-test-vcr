# A1 — JSON / Structured Assertions: PRD

Status: **implemented**, per the three decisions below, all explicitly made by the
project owner rather than assumed by whoever implemented this. Every Spring AI type
referenced below was confirmed by unpacking `spring-ai-client-chat-2.0.0.jar` /
`spring-ai-model-2.0.0.jar` / `spring-ai-commons-2.0.0.jar` from the local `~/.m2` cache
and reading the bytecode with `javap -p` — the same discipline `CLAUDE.md`'s
"VERIFIED API FACTS" table already applies to Recorder. See the Appendix for the exact
`javap` output this design is built on.

**The three decisions that turned this from an open design into a built feature:**

1. **JSON schema conformance stays Jackson-tree-based in v1 — no new external
   dependency.** A JSON Schema validator (e.g. `networknt/json-schema-validator`) would
   be a new main-scope dependency landing transitively on every consumer's classpath —
   too heavy a cost for a test-helper library to impose before a real need for full
   schema conformance is demonstrated. `hasJsonField`/`hasJsonFieldOfType` (RFC 6901 JSON
   Pointer + field-existence + type check) ship instead, using the `tools.jackson.*`
   dependency this project already has. Full JSON Schema validation remains a possible,
   explicitly-approved-later fast-follow — see 7.2's resolution below.
2. **Two entry points, both argument-matching overloads.** `VcrAssertions.assertThat
   (ChatClientResponse)` and `assertThat(ChatResponse)`; `hasToolCall(name,
   Map<String,Object>)` for exact-match and `hasToolCall(name,
   Consumer<Map<String,Object>>)` for partial/custom matching. Approved as designed in
   section 3, unchanged.
3. **The tool-call scope limitation (section 7.1) is documented, not worked around.**
   `hasToolCall(...)` is meaningful against a raw `ChatModel#call(Prompt)` result or a
   response inspected before `ToolCallingAdvisor` resolves its loop — not against the
   final answer of a normal `ChatClient.tools(...).call()`. This is spelled out in the
   Javadoc of every `hasToolCall` overload (`ChatResponseAssert`, in
   `src/main/java/.../assertions/ChatResponseAssert.java`) and in the example project's
   `AssertionsShowcaseTest`, rather than papered over with a Recorder-aware workaround.

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
- Field-level JSON assertions (`hasJsonField`, `hasJsonFieldOfType`) against a
  structured-output response's raw text, via RFC 6901 JSON Pointer paths — field
  existence, exact value, and node type, independent of whether the caller also converts
  the response to a POJO. Deliberately **not** full JSON Schema conformance checking in
  v1 — see the resolved decision at the top of this document and section 7.2.

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
interface reinvented from scratch. `ChatResponseAssert` holds every method's actual
logic; `ChatClientResponseAssert` holds no logic of its own — each of its methods builds
a `ChatResponseAssert` around `actual.chatResponse()` and delegates to it, one line per
method (an adapter, not a shared package-private helper class as an earlier draft of
this PRD considered — one fewer type to maintain, and the delegation itself is simple
enough that a third class would be the premature abstraction, not the duplication).

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

// JSON — Jackson-tree-based, v1 decision (no new external JSON Schema dependency;
// see the resolved decision at the top of this document and section 7.2)
hasJsonField(String jsonPointer)
    // asserts a field exists at an RFC 6901 JSON Pointer (e.g. "/carrier" or
    // "/shipping/carrier") into AssistantMessage.getText(), parsed as JSON.

hasJsonField(String jsonPointer, Object expectedValue)
    // field exists AND equals expectedValue. Numbers compare via BigDecimal, so an
    // int literal like 9 matches a JSON number regardless of how Jackson boxes it.

hasJsonFieldOfType(String jsonPointer, JsonNodeType expectedType)
    // asserts the JSON node type at jsonPointer (STRING/NUMBER/BOOLEAN/OBJECT/ARRAY/
    // NULL — tools.jackson.databind.node.JsonNodeType, not a bespoke enum).
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
`ChatGenerationMetadata.getFinishReason()`, all pure in-memory reads. `hasJsonField`/
`hasJsonFieldOfType` parse `AssistantMessage.getText()` with a private, local `JsonMapper`
— no schema or document is fetched from anywhere, let alone via a model call. This is what keeps
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

As shipped, in `src/test/java/.../assertions/`:

- **`ChatResponseAssertTests`** (28 tests, `@Nested` per assertion group —
  `HasToolCallByName`, `HasToolCallWithExactArguments`,
  `HasToolCallWithArgumentsRequirements`, `HasNoToolCalls`, `HasToolCallCount`,
  `HasFinishReason`, `ExtractingText`, `HasJsonField` with nested `WithExpectedValue`/
  `OfType`, and `EdgeCases`) — the core logic, exercised directly against hand-built
  `ChatResponse` objects.
- **`ChatClientResponseAssertTests`** (11 tests) — proves delegation to
  `ChatResponseAssert` works for every method, including the null-`chatResponse()` edge
  case, without re-proving every pass/fail case `ChatResponseAssertTests` already covers.

Every assertion type has both a passing test and a failing test whose message is
checked for actual content (not just "it threw an `AssertionError`") — an assertion
library's whole job is failing with a message that says what was actually there.
Concretely: `hasToolCall("wrongName")` against a response that called a *different*
tool fails with a message naming the actual tool that was called, not a generic
not-found message; `hasToolCall(name, Map)` is proven against two differently
*serialized*-but-equal argument JSON strings (different key order and whitespace) to
show the "substring match was never really checking the field" bug from section 1 is
actually fixed, not just that the method compiles; `hasJsonField(path, 9)` is proven
against both a JSON integer and would-be `9.0` to show the numeric comparison doesn't
depend on how Jackson happens to box the value.

No `@SpringBootTest`, no `Testcontainers`, no Ollama — these are unit tests against
hand-built Spring AI domain objects, same category as `VcrCacheKeyGeneratorTests`. Full
suite: **104/104** passing (65 pre-existing + 39 new), `mvn -Prelease javadoc:javadoc`
clean with zero warnings from the new `assertions` package.

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
work around. **Decided:** document this limitation in Javadoc (done — see every
`hasToolCall` overload's Javadoc on `ChatResponseAssert`) rather than build a second,
Recorder-aware assertion (e.g. a hypothetical `VcrAssertions.assertThatFixtures
(cacheDirectory)...`) that would formalize what `ToolCallingRecordReplayTest` already
does by hand today. The latter would blur A1 into depending on Recorder's fixture
format, which `docs/VISION.md` explicitly says Assertions should *not* need to know
about. Revisit only as a separate, explicitly-scoped item if real usage shows it's
needed often — not folded into A1 retroactively.

**7.2 — Resolved: no new external JSON Schema validation dependency in v1.**

Nothing in `spring-ai-client-chat`/`spring-ai-model` validates a JSON document against a
JSON Schema document — Spring AI only *generates* schemas (`BeanOutputConverter`), it
never validates against one. Full JSON Schema conformance would need a new dependency
(e.g. `networknt:json-schema-validator`), landing transitively on every consumer's
classpath — decided against for v1 specifically because of that transitive cost to a
test-helper library. Shipped instead: `hasJsonField`/`hasJsonField(path, value)`/
`hasJsonFieldOfType` — RFC 6901 JSON Pointer field existence, exact-value, and node-type
checks, built on `tools.jackson.databind.JsonNode`/`JsonNodeType`, a dependency this
project already has in main scope. This is explicitly a v1 decision, not a permanent
rejection: full JSON Schema validation (via `networknt:json-schema-validator` or
similar) remains a possible, explicitly-approved-later fast-follow once a real,
demonstrated need for it (rather than a hypothetical one) justifies the new dependency.

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

Shipped as `AssertionsShowcaseTest` in `spring-ai-test-tools-example`, mirroring how
R1/R2 each got one, and honestly reflecting the 7.1 limitation rather than hiding it —
built to need **no new recording**:

- `hasToolCallAssertsOnTheModelsStillPendingToolCallRequest()` reads the *first-turn*
  tool-calling fixture (`3a3c5135...json`) straight off disk via the library's own
  public `VcrTrackStore`/`VcrTrackMapper`, converts it back to a `ChatResponse`, and
  asserts `hasToolCall("getOrderStatus", args -> ...)` and `hasFinishReason("stop")`
  against it. That first turn's `ChatResponse` *is* the model's still-pending tool-call
  request — `VcrScope.INSIDE_TOOL_LOOP` records one fixture per model turn, and this is
  the turn before `ToolCallingAdvisor` executes and resolves it — exactly the shape
  section 7.1 says `hasToolCall` is meaningful against. The test's own Javadoc explains
  this reasoning inline so a reader doesn't wonder why it looks structurally different
  from `ToolCallingRecordReplayTest`.
- `hasJsonFieldAssertsOnAStructuredOutputResponseWithoutConvertingItToAPojoFirst()` reads
  the existing structured-output fixture (`b27b4fbb...json`) the same way and
  demonstrates `hasJsonField`, `hasJsonFieldOfType`, and `extractingText()` against its
  raw JSON response text, independent of `entity()`'s own POJO conversion.

Both tests: 0 Docker containers, 0 Ollama calls, 0 new fixtures — pure fixture reads via
already-public Recorder API, proving A1 works identically against a replay as it would
against a live response.

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
