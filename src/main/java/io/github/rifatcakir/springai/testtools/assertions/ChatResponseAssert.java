package io.github.rifatcakir.springai.testtools.assertions;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingModel;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.util.Assert;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeType;

/**
 * Fluent, deterministic assertions against a {@link ChatResponse} — the core Assertions
 * (Layer 2) logic. {@link ChatClientResponseAssert} wraps this class rather than
 * duplicating it, delegating every method to an instance built around {@code
 * ChatClientResponse#chatResponse()}.
 *
 * <p>Every method here reads data already present on {@code actual} — none of them make
 * a model call, parse anything from the network, or touch the filesystem. This is what
 * lets the same assertion run identically against a live response and a Recorder replay.
 *
 * <p>JSON handling ({@link #hasJsonField(String, Object)}, tool-call argument parsing)
 * uses a private, minimally configured {@link JsonMapper} local to this class — the
 * Assertions layer deliberately does not reach into {@code
 * io.github.rifatcakir.springai.testtools.recorder}'s own Jackson configuration, since
 * Assertions is meant to work with no dependency on Recorder internals (see {@code
 * docs/VISION.md} Layer 2).
 *
 * <p>{@link #usingEmbeddingModel(EmbeddingModel)}/{@link #isSemanticallySimilarTo(String)}
 * (A2) are the one place this class references a Recorder type ({@link
 * VcrEmbeddingModel}) — a deliberate, narrow exception: it is purely diagnostic (a
 * warning when the given model isn't Recorder-backed, see {@code
 * docs/A2-SEMANTIC-ASSERTIONS-PRD.md} section 3), never required for the assertion
 * itself to function correctly. Assertions still does not depend on Recorder to work,
 * it just recognizes it opportunistically for a better warning.
 *
 * @author Rifat Cakir
 */
public final class ChatResponseAssert extends AbstractObjectAssert<ChatResponseAssert, ChatResponse> {

	/**
	 * Deliberately conservative, deliberately not "the right answer for every model" —
	 * see {@code docs/A2-SEMANTIC-ASSERTIONS-PRD.md} section 5 for why no single default
	 * can be, and use the explicit-threshold overload once a real embedding model's own
	 * score distribution has been observed.
	 */
	public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.7;

	private static final Logger logger = LoggerFactory.getLogger(ChatResponseAssert.class);

	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

	private EmbeddingModel embeddingModel;

	ChatResponseAssert(ChatResponse actual) {
		super(actual, ChatResponseAssert.class);
	}

	/**
	 * Asserts that the response's primary generation ({@link ChatResponse#getResult()})
	 * includes a tool call named {@code name}, regardless of its arguments.
	 *
	 * <p><strong>Scope limitation, stated plainly (see {@code
	 * docs/A1-ASSERTIONS-PRD.md} section 7.1 for the full reasoning, confirmed against
	 * {@code ToolCallingAdvisor}'s bytecode, not assumed):</strong> this can only see a
	 * tool call that is still the <em>terminal</em> state of the response object being
	 * asserted on. A normal {@code chatClient.prompt()...tools(...).call()} goes through
	 * Spring AI's auto-registered {@code ToolCallingAdvisor}, which resolves and executes
	 * the tool call internally — model call, tool execution, recursion, finalization —
	 * before ever returning a response to the caller. By the time that final response
	 * reaches this assertion, its tool-call list is already empty; {@code
	 * ChatClientAttributes} carries no key that would let this assertion see through to
	 * what happened inside the loop. This assertion is meaningful against:
	 *
	 * <ul>
	 * <li>a raw {@code ChatModel#call(Prompt)} result, where the model's tool-call
	 * request is still pending and unexecuted; or</li>
	 * <li>a response captured by a custom advisor placed <em>before</em> {@code
	 * ToolCallingAdvisor} in the chain.</li>
	 * </ul>
	 *
	 * <p>It is <strong>not</strong> usable against the final answer of a normal
	 * {@code ChatClient} call whose built-in tool loop already ran to completion — for
	 * that case, inspect the model turn's own response (or, for a Recorder-backed test,
	 * the recorded {@code INSIDE_TOOL_LOOP} fixture for that turn) instead.
	 * @param name the expected tool name
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert hasToolCall(String name) {
		isNotNull();
		Assert.hasText(name, "name must not be blank");
		requireToolCall(name);
		return this;
	}

	/**
	 * Like {@link #hasToolCall(String)}, plus an exact-match check that the tool call's
	 * arguments — a raw JSON string on {@link AssistantMessage.ToolCall#arguments()} —
	 * parse to exactly {@code exactArguments}. Comparing parsed values rather than the
	 * raw JSON text means two differently-serialized-but-equal argument strings (e.g.
	 * different key order or whitespace) both satisfy this assertion, which a naive
	 * {@code arguments().contains(...)} check would not reliably do.
	 *
	 * <p>See {@link #hasToolCall(String)} for the scope limitation this shares.
	 * @param name the expected tool name
	 * @param exactArguments the exact arguments expected, as a parsed map
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert hasToolCall(String name, Map<String, Object> exactArguments) {
		isNotNull();
		Assert.hasText(name, "name must not be blank");
		Assert.notNull(exactArguments, "exactArguments must not be null");
		AssistantMessage.ToolCall call = requireToolCall(name);
		Map<String, Object> actualArguments = parseArguments(call);
		if (!actualArguments.equals(exactArguments)) {
			failWithMessage("%nExpected tool call <%s> arguments to be exactly:%n  <%s>%nbut were:%n  <%s>", name,
					exactArguments, actualArguments);
		}
		return this;
	}

	/**
	 * Like {@link #hasToolCall(String)}, plus a caller-supplied assertion against the
	 * tool call's parsed arguments — for partial/custom checks that an exact-match
	 * {@link #hasToolCall(String, Map)} can't express, e.g. {@code
	 * args -> assertThat(args).containsEntry("orderId", "ORD-4471")}.
	 *
	 * <p>See {@link #hasToolCall(String)} for the scope limitation this shares.
	 * @param name the expected tool name
	 * @param argumentsRequirements assertions run against the tool call's parsed
	 * arguments
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert hasToolCall(String name, Consumer<Map<String, Object>> argumentsRequirements) {
		isNotNull();
		Assert.hasText(name, "name must not be blank");
		Assert.notNull(argumentsRequirements, "argumentsRequirements must not be null");
		AssistantMessage.ToolCall call = requireToolCall(name);
		argumentsRequirements.accept(parseArguments(call));
		return this;
	}

	/**
	 * Asserts that the response's primary generation has no tool calls at all.
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert hasNoToolCalls() {
		isNotNull();
		List<AssistantMessage.ToolCall> toolCalls = toolCalls();
		if (!toolCalls.isEmpty()) {
			failWithMessage("%nExpected no tool calls but found:%n  <%s>", toolCallNames(toolCalls));
		}
		return this;
	}

	/**
	 * Asserts the exact number of tool calls on the response's primary generation.
	 * @param expected the expected tool call count
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert hasToolCallCount(int expected) {
		isNotNull();
		List<AssistantMessage.ToolCall> toolCalls = toolCalls();
		if (toolCalls.size() != expected) {
			failWithMessage("%nExpected <%s> tool call(s) but found <%s>:%n  <%s>", expected, toolCalls.size(),
					toolCallNames(toolCalls));
		}
		return this;
	}

	/**
	 * Asserts the primary generation's finish reason
	 * ({@code ChatGenerationMetadata#getFinishReason()}).
	 * @param expected the expected finish reason
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert hasFinishReason(String expected) {
		isNotNull();
		String actualReason = primaryGeneration().getMetadata().getFinishReason();
		if (!Objects.equals(actualReason, expected)) {
			failWithMessage("%nExpected finish reason:%n  <%s>%nbut was:%n  <%s>", expected, actualReason);
		}
		return this;
	}

	/**
	 * Extracts the primary generation's text ({@code AssistantMessage#getText()}) as an
	 * ordinary AssertJ string assertion, so callers get every existing {@code
	 * AbstractStringAssert} method (contains, matches, isEqualTo, ...) instead of A1
	 * reinventing string matching.
	 * @return a string assertion over the primary generation's text
	 */
	public AbstractStringAssert<?> extractingText() {
		isNotNull();
		return Assertions.assertThat(primaryGeneration().getOutput().getText());
	}

	/**
	 * Asserts that the primary generation's text, parsed as JSON, has a field at {@code
	 * jsonPointer} — an RFC 6901 JSON Pointer (e.g. {@code "/carrier"} or {@code
	 * "/shipping/carrier"}), Jackson's own built-in nested-access syntax, not a bespoke
	 * path language. Does not check the field's value — see the overload that does.
	 * @param jsonPointer an RFC 6901 JSON Pointer into the response's parsed JSON text
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert hasJsonField(String jsonPointer) {
		isNotNull();
		Assert.hasText(jsonPointer, "jsonPointer must not be blank");
		JsonNode node = responseJson().at(jsonPointer);
		if (node.isMissingNode()) {
			failWithMessage("%nExpected JSON field at <%s> to exist but it was missing in:%n  <%s>", jsonPointer,
					primaryText());
		}
		return this;
	}

	/**
	 * Like {@link #hasJsonField(String)}, plus a value check. {@code expectedValue} may
	 * be a {@link String}, {@link Boolean}, a {@link Number} (compared numerically, so
	 * {@code 9} and {@code 9.0} are equal — an {@code int} literal like {@code 9} must
	 * match a JSON number regardless of how Jackson would otherwise box it), or
	 * {@code null} (matches a JSON {@code null}).
	 *
	 * <p>This is deliberately field-level and Jackson-tree-based rather than full JSON
	 * Schema validation — a v1 decision made to avoid adding a new external dependency
	 * (e.g. a JSON Schema validator) to this library's main-scope footprint, which would
	 * transitively land on every consumer's classpath. See {@code
	 * docs/A1-ASSERTIONS-PRD.md} section 7.2: full schema conformance checking (a
	 * separate, explicit dependency decision) remains a possible fast-follow once a real
	 * need for it is demonstrated, not a v1 requirement.
	 * @param jsonPointer an RFC 6901 JSON Pointer into the response's parsed JSON text
	 * @param expectedValue the expected value at that path
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert hasJsonField(String jsonPointer, Object expectedValue) {
		isNotNull();
		Assert.hasText(jsonPointer, "jsonPointer must not be blank");
		JsonNode node = responseJson().at(jsonPointer);
		if (node.isMissingNode()) {
			failWithMessage("%nExpected JSON field at <%s> to equal:%n  <%s>%nbut the field was missing in:%n  <%s>",
					jsonPointer, expectedValue, primaryText());
		}
		if (!valueMatches(node, expectedValue)) {
			failWithMessage("%nExpected JSON field at <%s> to equal:%n  <%s>%nbut was:%n  <%s>", jsonPointer,
					expectedValue, node.isValueNode() ? node.asString() : node.toString());
		}
		return this;
	}

	/**
	 * Asserts the JSON node type at {@code jsonPointer} — the simple type-check half of
	 * "schema conformance, without a JSON Schema library" (see {@link #hasJsonField(
	 * String, Object)}'s Javadoc for why this stays Jackson-tree-based in v1).
	 * @param jsonPointer an RFC 6901 JSON Pointer into the response's parsed JSON text
	 * @param expectedType the expected {@link JsonNodeType}
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert hasJsonFieldOfType(String jsonPointer, JsonNodeType expectedType) {
		isNotNull();
		Assert.hasText(jsonPointer, "jsonPointer must not be blank");
		Assert.notNull(expectedType, "expectedType must not be null");
		JsonNode node = responseJson().at(jsonPointer);
		if (node.getNodeType() != expectedType) {
			failWithMessage("%nExpected JSON field at <%s> to be of type <%s> but was <%s>:%n  <%s>", jsonPointer,
					expectedType, node.getNodeType(), node);
		}
		return this;
	}

	/**
	 * Configures the {@link EmbeddingModel} {@link #isSemanticallySimilarTo(String)}/
	 * {@link #isSemanticallySimilarToAnyOf(Collection, double)} use to turn text into
	 * vectors. AssertJ's own idiom for optional configuration that isn't the value under
	 * test — the same shape as AssertJ's {@code usingComparator(...)} — rather than a
	 * second {@code VcrAssertions.assertThat(response, embeddingModel)} entry point; see
	 * {@code docs/A2-SEMANTIC-ASSERTIONS-PRD.md} section 2 for the alternatives this was
	 * weighed against.
	 *
	 * <p><strong>Determinism warning, not enforcement:</strong> logs an SLF4J
	 * {@code WARN} — does not throw — if {@code embeddingModel} is not a {@link
	 * VcrEmbeddingModel}. A semantic-similarity assertion built on a live embedding model
	 * makes a live, non-deterministic, token-costing call on every test run — exactly the
	 * problem Recorder exists to eliminate, one layer up (see {@code docs/VISION.md}
	 * Layer 3's identical argument for Evaluator judge calls). Not a hard failure: a
	 * caller may have a legitimate reason to pass a live model (an explicitly-tagged
	 * integration test), and the check itself is an imperfect {@code instanceof} that
	 * cannot see through an unrelated wrapper around an otherwise Recorder-backed model.
	 * @param embeddingModel the model to embed text with; should be Recorder-backed
	 * ({@code VcrEmbeddingModel}, see R4) for this assertion to be CI-safe
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert usingEmbeddingModel(EmbeddingModel embeddingModel) {
		isNotNull();
		Assert.notNull(embeddingModel, "embeddingModel must not be null");
		if (!(embeddingModel instanceof VcrEmbeddingModel)) {
			logger.warn(
					"VcrAssertions#usingEmbeddingModel was given an EmbeddingModel that is not a VcrEmbeddingModel "
							+ "-- semantic similarity assertions built on it will make a live, non-deterministic "
							+ "embedding call on every test run. Wrap it with VcrEmbeddingModel (see R4) for "
							+ "CI-safe determinism, unless this is deliberately a live/integration test.");
		}
		this.embeddingModel = embeddingModel;
		return this;
	}

	/**
	 * Asserts the response's primary text is semantically similar to {@code expected},
	 * using {@link #DEFAULT_SIMILARITY_THRESHOLD}. Requires {@link
	 * #usingEmbeddingModel(EmbeddingModel)} to have been called first.
	 * @param expected the reference text to compare against
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert isSemanticallySimilarTo(String expected) {
		return isSemanticallySimilarTo(expected, DEFAULT_SIMILARITY_THRESHOLD);
	}

	/**
	 * Like {@link #isSemanticallySimilarTo(String)}, with an explicit cosine-similarity
	 * threshold instead of {@link #DEFAULT_SIMILARITY_THRESHOLD}.
	 * @param expected the reference text to compare against
	 * @param threshold the minimum cosine similarity (typically {@code 0.0}-{@code 1.0})
	 * to pass
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert isSemanticallySimilarTo(String expected, double threshold) {
		isNotNull();
		Assert.notNull(expected, "expected must not be null");
		requireEmbeddingModel();
		double similarity = similarityTo(expected);
		if (similarity < threshold) {
			failWithMessage(
					"%nExpected response text:%n  <%s>%nto be semantically similar (cosine similarity >= %s) to:%n  <%s>%nbut similarity was:%n  <%s>",
					primaryText(), threshold, expected, similarity);
		}
		return this;
	}

	/**
	 * Passes if the response's primary text is semantically similar to <em>any</em> of
	 * {@code candidates} — for "one of these acceptable phrasings," not one fixed
	 * expected string. Requires {@link #usingEmbeddingModel(EmbeddingModel)} to have
	 * been called first.
	 * @param candidates reference texts, any one of which is acceptable
	 * @param threshold the minimum cosine similarity (typically {@code 0.0}-{@code 1.0})
	 * to pass, against at least one candidate
	 * @return {@code this}, for chaining
	 */
	public ChatResponseAssert isSemanticallySimilarToAnyOf(Collection<String> candidates, double threshold) {
		isNotNull();
		Assert.notEmpty(candidates, "candidates must not be empty");
		requireEmbeddingModel();
		Map<String, Double> similarities = new LinkedHashMap<>();
		for (String candidate : candidates) {
			similarities.put(candidate, similarityTo(candidate));
		}
		boolean anyMatch = similarities.values().stream().anyMatch(similarity -> similarity >= threshold);
		if (!anyMatch) {
			failWithMessage(
					"%nExpected response text:%n  <%s>%nto be semantically similar (cosine similarity >= %s) to any of:%n  <%s>%nbut similarities were:%n  <%s>",
					primaryText(), threshold, candidates, similarities);
		}
		return this;
	}

	private void requireEmbeddingModel() {
		if (this.embeddingModel == null) {
			failWithMessage(
					"Must call usingEmbeddingModel(...) before isSemanticallySimilarTo(...)/isSemanticallySimilarToAnyOf(...)");
		}
	}

	private double similarityTo(String expectedText) {
		float[] actualVector = this.embeddingModel.embed(primaryText());
		float[] expectedVector = this.embeddingModel.embed(expectedText);
		return cosineSimilarity(actualVector, expectedVector, expectedText);
	}

	/**
	 * {@code dot(a, b) / (‖a‖ · ‖b‖)}. No Spring AI helper for this exists — confirmed,
	 * not assumed, by inspecting every jar already on this project's classpath (see
	 * {@code docs/A2-SEMANTIC-ASSERTIONS-PRD.md} section 4) — so this is a direct, ~10
	 * line implementation rather than a new dependency for one formula.
	 */
	private double cosineSimilarity(float[] a, float[] b, String expectedTextForMessage) {
		if (a.length != b.length) {
			failWithMessage(
					"%nCannot compare embeddings of different dimensions: the response text embedded to <%s> dimensions, but <%s> embedded to <%s>",
					a.length, expectedTextForMessage, b.length);
		}
		double dot = 0;
		double normA = 0;
		double normB = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			normA += a[i] * a[i];
			normB += b[i] * b[i];
		}
		if (normA == 0.0 || normB == 0.0) {
			failWithMessage(
					"%nCannot compute cosine similarity against a zero vector -- the embedding model returned an all-zero vector for either the response text or <%s>",
					expectedTextForMessage);
		}
		return dot / (Math.sqrt(normA) * Math.sqrt(normB));
	}

	private Generation primaryGeneration() {
		List<Generation> results = this.actual.getResults();
		if (results == null || results.isEmpty()) {
			failWithMessage("Expected at least one generation but found none");
		}
		return this.actual.getResult();
	}

	private String primaryText() {
		return primaryGeneration().getOutput().getText();
	}

	private List<AssistantMessage.ToolCall> toolCalls() {
		List<AssistantMessage.ToolCall> calls = primaryGeneration().getOutput().getToolCalls();
		return (calls == null) ? List.of() : calls;
	}

	private AssistantMessage.ToolCall requireToolCall(String name) {
		List<AssistantMessage.ToolCall> calls = toolCalls();
		for (AssistantMessage.ToolCall call : calls) {
			if (call.name().equals(name)) {
				return call;
			}
		}
		failWithMessage("%nExpected a tool call named <%s> but found:%n  <%s>", name, toolCallNames(calls));
		return null; // unreachable -- failWithMessage always throws
	}

	private static List<String> toolCallNames(List<AssistantMessage.ToolCall> calls) {
		return calls.stream().map(AssistantMessage.ToolCall::name).toList();
	}

	private Map<String, Object> parseArguments(AssistantMessage.ToolCall call) {
		String arguments = call.arguments();
		if (arguments == null || arguments.isBlank()) {
			return Map.of();
		}
		try {
			return JSON_MAPPER.readValue(arguments, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (RuntimeException ex) {
			failWithMessage("%nTool call <%s> arguments were not a valid JSON object:%n  <%s>%n(%s)", call.name(),
					arguments, ex.getMessage());
			return Map.of(); // unreachable -- failWithMessage always throws
		}
	}

	private JsonNode responseJson() {
		String text = primaryText();
		try {
			return JSON_MAPPER.readTree(text);
		}
		catch (RuntimeException ex) {
			failWithMessage("%nExpected response text to be valid JSON but parsing failed for:%n  <%s>%n(%s)", text,
					ex.getMessage());
			return null; // unreachable -- failWithMessage always throws
		}
	}

	private static boolean valueMatches(JsonNode node, Object expected) {
		if (expected == null) {
			return node.isNull();
		}
		if (expected instanceof String stringValue) {
			return node.isTextual() && node.asString().equals(stringValue);
		}
		if (expected instanceof Boolean booleanValue) {
			return node.isBoolean() && node.asBoolean() == booleanValue;
		}
		if (expected instanceof Number numberValue) {
			return node.isNumber() && node.decimalValue().compareTo(new BigDecimal(numberValue.toString())) == 0;
		}
		return node.toString().equals(String.valueOf(expected));
	}

}
