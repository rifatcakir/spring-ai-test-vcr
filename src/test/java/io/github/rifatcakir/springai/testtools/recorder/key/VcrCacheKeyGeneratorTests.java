package io.github.rifatcakir.springai.testtools.recorder.key;

import java.util.List;
import java.util.Map;

import io.github.rifatcakir.springai.testtools.recorder.RegexPromptNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key generator is the load-bearing component: if it is unstable the cache never hits,
 * and if it is insensitive the cache serves answers for prompts nobody reviewed. These
 * tests pin both directions.
 *
 * @author Rifat Cakir
 */
class VcrCacheKeyGeneratorTests {

	private final VcrCacheKeyGenerator generator = new VcrCacheKeyGenerator();

	private static Prompt prompt(String userText, ChatOptions options) {
		return new Prompt(List.of(new SystemMessage("You are terse."), new UserMessage(userText)), options);
	}

	private static ChatOptions options(String model, Double temperature) {
		return ChatOptions.builder().model(model).temperature(temperature).build();
	}

	@Test
	@DisplayName("produces a 64-character lowercase hex digest")
	void producesWellFormedDigest() {
		VcrCacheKey key = this.generator.generate(prompt("hello", options("llama3", 0.0)));

		assertThat(key.hash()).hasSize(64).matches("[0-9a-f]{64}");
		assertThat(key.canonicalRequest()).contains("model=llama3").contains("temperature=0.0");
	}

	@Test
	@DisplayName("is stable across repeated invocations")
	void isStable() {
		Prompt prompt = prompt("hello", options("llama3", 0.0));

		assertThat(this.generator.generate(prompt).hash()).isEqualTo(this.generator.generate(prompt).hash());
	}

	@Test
	@DisplayName("a single character of prompt drift busts the cache")
	void singleCharacterBustsTheCache() {
		String a = this.generator.generate(prompt("Summarise the backlog.", options("llama3", 0.0))).hash();
		String b = this.generator.generate(prompt("Summarise the backlog!", options("llama3", 0.0))).hash();

		assertThat(a).isNotEqualTo(b);
	}

	@Test
	@DisplayName("model and temperature participate in the hash")
	void optionsParticipate() {
		String base = this.generator.generate(prompt("hello", options("llama3", 0.0))).hash();

		assertThat(this.generator.generate(prompt("hello", options("gemma2", 0.0))).hash()).isNotEqualTo(base);
		assertThat(this.generator.generate(prompt("hello", options("llama3", 0.7))).hash()).isNotEqualTo(base);
	}

	@Test
	@DisplayName("message role participates in the hash")
	void roleParticipates() {
		ChatOptions options = options("llama3", 0.0);
		String asUser = this.generator.generate(new Prompt(List.of(new UserMessage("ping")), options)).hash();
		String asSystem = this.generator.generate(new Prompt(List.of(new SystemMessage("ping")), options)).hash();

		assertThat(asUser).isNotEqualTo(asSystem);
	}

	@Test
	@DisplayName("message order participates in the hash")
	void orderParticipates() {
		ChatOptions options = options("llama3", 0.0);
		String forward = this.generator
			.generate(new Prompt(List.of(new UserMessage("a"), new UserMessage("b")), options))
			.hash();
		String reversed = this.generator
			.generate(new Prompt(List.of(new UserMessage("b"), new UserMessage("a")), options))
			.hash();

		assertThat(forward).isNotEqualTo(reversed);
	}

	@Test
	@DisplayName("an embedded newline cannot forge a canonical field")
	void messageTextCannotForgeFields() {
		ChatOptions options = options("llama3", 0.0);
		String injected = this.generator
			.generate(new Prompt(List.of(new UserMessage("x\nmodel=evil")), options))
			.hash();
		String literal = this.generator.generate(new Prompt(List.of(new UserMessage("x\\nmodel=evil")), options))
			.hash();

		// Both escape to distinct canonical forms; neither introduces a second
		// "model=" line into the pre-image.
		assertThat(injected).isNotEqualTo(literal);
		assertThat(this.generator.generate(new Prompt(List.of(new UserMessage("x\nmodel=evil")), options))
			.canonicalRequest()).doesNotContain("\nmodel=evil");
	}

	@Test
	@DisplayName("a normalizer collapses volatile values so the hash stays stable")
	void normalizerStabilisesVolatileValues() {
		VcrCacheKeyGenerator normalizing = new VcrCacheKeyGenerator(List.of(RegexPromptNormalizer.ISO_DATE));
		ChatOptions options = options("llama3", 0.0);

		String monday = normalizing.generate(prompt("Today is 2026-07-19. Plan the week.", options)).hash();
		String tuesday = normalizing.generate(prompt("Today is 2026-07-20. Plan the week.", options)).hash();

		assertThat(monday).isEqualTo(tuesday);

		// Without the normalizer, the same two prompts would miss forever.
		assertThat(this.generator.generate(prompt("Today is 2026-07-19. Plan the week.", options)).hash())
			.isNotEqualTo(this.generator.generate(prompt("Today is 2026-07-20. Plan the week.", options)).hash());
	}

	@Test
	@DisplayName("normalizing dates does not make genuinely different prompts collide")
	void normalizerRemainsSensitiveElsewhere() {
		VcrCacheKeyGenerator normalizing = new VcrCacheKeyGenerator(List.of(RegexPromptNormalizer.ISO_DATE));
		ChatOptions options = options("llama3", 0.0);

		assertThat(normalizing.generate(prompt("Today is 2026-07-19. Plan the week.", options)).hash())
			.isNotEqualTo(normalizing.generate(prompt("Today is 2026-07-19. Plan the month.", options)).hash());
	}

	@Test
	@DisplayName("absent options do not blow up")
	void toleratesNullOptions() {
		VcrCacheKey key = this.generator.generate(new Prompt(List.of(new UserMessage("hello")), null));

		assertThat(key.hash()).hasSize(64);
		assertThat(key.canonicalRequest()).contains("model= null");
	}

	private static AssistantMessage assistantToolCall(String id, String name, String arguments) {
		return AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
			.build();
	}

	private static ToolResponseMessage toolResponse(String id, String name, String responseData) {
		return ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse(id, name, responseData)))
			.build();
	}

	@Test
	@DisplayName("an assistant message's tool call participates in the hash, not just its (empty) text")
	void toolCallsParticipateInTheHash() {
		ChatOptions options = options("llama3.2", 0.0);
		Prompt base = new Prompt(
				List.of(new UserMessage("what is the weather in Ankara?"),
						assistantToolCall("call-1", "getWeather", "{\"city\":\"Ankara\"}")),
				options);
		Prompt differentArguments = new Prompt(
				List.of(new UserMessage("what is the weather in Ankara?"),
						assistantToolCall("call-1", "getWeather", "{\"city\":\"Istanbul\"}")),
				options);
		Prompt differentToolName = new Prompt(
				List.of(new UserMessage("what is the weather in Ankara?"),
						assistantToolCall("call-1", "getForecast", "{\"city\":\"Ankara\"}")),
				options);
		Prompt noToolCallAtAll = new Prompt(
				List.of(new UserMessage("what is the weather in Ankara?"), new AssistantMessage("")), options);

		String baseHash = this.generator.generate(base).hash();

		assertThat(this.generator.generate(differentArguments).hash())
			.as("a tool call with different arguments must not collide with the original")
			.isNotEqualTo(baseHash);
		assertThat(this.generator.generate(differentToolName).hash())
			.as("a different tool name must not collide with the original")
			.isNotEqualTo(baseHash);
		assertThat(this.generator.generate(noToolCallAtAll).hash())
			.as("a turn with no tool call at all must not collide with one that made a tool call — "
					+ "both have empty getText(), so only the tool-call-aware canonicalization tells them apart")
			.isNotEqualTo(baseHash);
		assertThat(this.generator.generate(base).hash()).as("stable across repeated invocations")
			.isEqualTo(baseHash);
	}

	@Test
	@DisplayName("a tool response message's result participates in the hash, not just its (always empty) text")
	void toolResponsesParticipateInTheHash() {
		ChatOptions options = options("llama3.2", 0.0);
		Prompt sunny = new Prompt(List.of(new UserMessage("what is the weather?"),
				assistantToolCall("call-1", "getWeather", "{\"city\":\"Ankara\"}"),
				toolResponse("call-1", "getWeather", "sunny, 28C")), options);
		Prompt rainy = new Prompt(List.of(new UserMessage("what is the weather?"),
				assistantToolCall("call-1", "getWeather", "{\"city\":\"Ankara\"}"),
				toolResponse("call-1", "getWeather", "rainy, 14C")), options);

		assertThat(this.generator.generate(sunny).hash()).as("under INSIDE_TOOL_LOOP this is exactly the case that "
				+ "must not collide: two different tool results feeding two different final answers")
			.isNotEqualTo(this.generator.generate(rainy).hash());
	}

	/**
	 * Characterisation test, not a behavioural one: these hashes were computed once by this
	 * exact generator and pinned as literals. Nothing about {@code canonicalize()} is allowed
	 * to change the hash for these known inputs without this test failing loudly — including
	 * changes that would otherwise look harmless (reordering canonical fields, changing a
	 * separator, adding a new field with a different default). If this test ever needs to be
	 * updated to make a change pass, that update belongs in the same commit as an explicit
	 * note that every existing committed fixture is about to become a cache miss, not a
	 * silent edit. See {@link io.github.rifatcakir.springai.testtools.recorder.VcrFixtureRedactor} for the
	 * companion guarantee that fixture <em>redaction</em> can never reach this hash either.
	 */
	@Test
	@DisplayName("pins the exact hash for known inputs — a golden-master regression guard")
	void hashIsPinnedForKnownInputs() {
		String helloHash = this.generator.generate(prompt("hello", options("llama3", 0.0))).hash();
		assertThat(helloHash).isEqualTo("9a7ff0e4563dbe15ec35f04cb18901204c48f9ff572df5b642784590bc86efc2");

		String noOptionsHash = this.generator.generate(new Prompt(List.of(new UserMessage("hello")), null)).hash();
		assertThat(noOptionsHash).isEqualTo("548e2d22355e22244dabcdfdc71bf31660a2adf262dd84053184086a124a4661");
	}

	/**
	 * Same golden-master guarantee as {@link #hashIsPinnedForKnownInputs()}, for the tool-call
	 * and tool-response canonicalization added alongside {@code VcrTrack} schema version "2".
	 * Pinned separately because these exercise the {@code appendMessageToolCalls}/
	 * {@code appendMessageToolResponses} code path in {@link VcrCacheKeyGenerator}, which the
	 * original golden test above predates entirely.
	 */
	@Test
	@DisplayName("pins the exact hash for a known tool call and a known tool response")
	void hashIsPinnedForKnownToolCallsAndResponses() {
		ChatOptions options = options("llama3.2", 0.0);

		String toolCallHash = this.generator
			.generate(new Prompt(List.of(new UserMessage("what is the weather in Ankara?"),
					assistantToolCall("call-1", "getWeather", "{\"city\":\"Ankara\"}")), options))
			.hash();
		assertThat(toolCallHash).isEqualTo("875f925b095a241fe6987e8cc9dd2ad3b81d4179b4bc539af0790a65177b599f");

		String toolResponseHash = this.generator
			.generate(new Prompt(List.of(new UserMessage("what is the weather in Ankara?"),
					assistantToolCall("call-1", "getWeather", "{\"city\":\"Ankara\"}"),
					toolResponse("call-1", "getWeather", "sunny, 28C")), options))
			.hash();
		assertThat(toolResponseHash).isEqualTo("80def403803879bcd822ed84c3bcfe22f37717a708ee0296ab4e685150af4073");
	}

	private static Map<String, Object> structuredOutputContext(String format, String jsonSchema) {
		return Map.of(ChatClientAttributes.OUTPUT_FORMAT.getKey(), format,
				ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey(), jsonSchema);
	}

	/**
	 * {@code generate(Prompt)} — the overload every call site used before {@code
	 * ChatClient...entity(Class)} support existed — must remain exactly equivalent to
	 * {@code generate(Prompt, Map.of())}. This is what guarantees every fixture recorded
	 * before this feature existed still resolves to the same hash: an empty or absent
	 * context must never add anything to the canonical form.
	 */
	@Test
	@DisplayName("no request-scoped context is exactly equivalent to an empty one")
	void noContextEqualsEmptyContext() {
		Prompt prompt = prompt("hello", options("llama3", 0.0));

		assertThat(this.generator.generate(prompt).hash()).isEqualTo(this.generator.generate(prompt, Map.of()).hash());
		assertThat(this.generator.generate(prompt).canonicalRequest())
			.isEqualTo(this.generator.generate(prompt, Map.of()).canonicalRequest());
	}

	/**
	 * The actual bug this test suite exists to close: before this fix, {@code
	 * ChatModelCallAdvisor} spliced {@code entity()}'s format instructions/schema into the
	 * message text strictly after {@code DeterministicVcrAdvisor} had already computed the
	 * hash from the un-augmented {@link Prompt} alone — so two structurally different
	 * {@code entity()} target types sharing identical prompt text canonicalized
	 * identically. Confirmed end to end against a real model in
	 * {@code OllamaStructuredOutputEndToEndTests} before this fix landed.
	 */
	@Test
	@DisplayName("structured-output format/schema participates in the hash — two different entity() types sharing "
			+ "the same prompt text must not collide")
	void structuredOutputParticipatesInTheHash() {
		Prompt samePromptText = prompt("Give me an example. Make up any reasonable data.", options("llama3.2", 0.0));

		String noStructuredOutput = this.generator.generate(samePromptText, Map.of()).hash();
		String cityWeatherSchema = this.generator
			.generate(samePromptText,
					structuredOutputContext("Respond in JSON matching this schema: {city, temperatureCelsius}",
							"{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},"
									+ "\"temperatureCelsius\":{\"type\":\"integer\"}}}"))
			.hash();
		String reminderSchema = this.generator
			.generate(samePromptText, structuredOutputContext("Respond in JSON matching this schema: {title, done}",
					"{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},"
							+ "\"done\":{\"type\":\"boolean\"}}}"))
			.hash();

		assertThat(cityWeatherSchema)
			.as("a structured-output call must not collide with the same prompt text asked without entity()")
			.isNotEqualTo(noStructuredOutput);
		assertThat(reminderSchema)
			.as("a structured-output call must not collide with the same prompt text asked without entity()")
			.isNotEqualTo(noStructuredOutput);
		assertThat(cityWeatherSchema)
			.as("BUG FIXED: two different entity() target types sharing identical prompt text must now hash "
					+ "differently instead of colliding on one fixture")
			.isNotEqualTo(reminderSchema);
	}

	/**
	 * Same golden-master guarantee as {@link #hashIsPinnedForKnownInputs()}, for the
	 * structured-output canonicalization added alongside {@code VcrTrack} schema version
	 * "3". Pinned separately because these exercise the structured-output-aware
	 * canonicalization path in {@link VcrCacheKeyGenerator}, which every golden test above
	 * predates.
	 */
	@Test
	@DisplayName("pins the exact hash for a known structured-output schema")
	void hashIsPinnedForKnownStructuredOutput() {
		Prompt samePromptText = prompt("Give me an example. Make up any reasonable data.", options("llama3.2", 0.0));

		String cityWeatherHash = this.generator
			.generate(samePromptText,
					structuredOutputContext("Respond in JSON matching this schema: {city, temperatureCelsius}",
							"{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},"
									+ "\"temperatureCelsius\":{\"type\":\"integer\"}}}"))
			.hash();
		assertThat(cityWeatherHash).isEqualTo("22232d5274962bb8619eb4837668e407c61e461f59d2acfff8f1cd886d798cd2");
	}

	private static ToolCallback toolCallback(String name, String description, String inputSchema) {
		ToolDefinition definition = ToolDefinition.builder()
			.name(name)
			.description(description)
			.inputSchema(inputSchema)
			.build();
		return new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public String call(String toolInput) {
				return "";
			}

		};
	}

	private static ChatOptions optionsWithTool(String model, ToolCallback callback) {
		return ToolCallingChatOptions.builder().model(model).temperature(0.0).toolCallbacks(callback).build();
	}

	/**
	 * The actual cross-platform bug this test (and {@link #structuredOutputFormatCrlfAndLfHashIdentically()})
	 * exist to close, found by actually pushing to a real Linux GitHub Actions runner, not
	 * hypothesized: a tool's input schema is rendered by Jackson's pretty printer, which
	 * embeds the recording JVM's own {@code System.lineSeparator()} — {@code \r\n} on
	 * Windows, {@code \n} on Linux/macOS. A fixture recorded on Windows and replayed in CI
	 * on Linux used to hash the same logical schema differently and miss for a reason with
	 * nothing to do with the actual request.
	 */
	@Test
	@DisplayName("BUG FIXED: a tool's input schema hashes identically whether it's pretty-printed with CRLF "
			+ "(Windows) or LF (Linux/macOS) line endings")
	void toolSchemaCrlfAndLfHashIdentically() {
		String schemaBody = "{\n  \"type\" : \"object\",\n  \"properties\" : {\n    \"city\" : {\n      \"type\" : "
				+ "\"string\"\n    }\n  }\n}";
		String schemaWithLf = schemaBody;
		String schemaWithCrlf = schemaBody.replace("\n", "\r\n");
		assertThat(schemaWithCrlf).as("sanity check: the two schema strings really do differ, byte for byte")
			.isNotEqualTo(schemaWithLf);

		Prompt withLfSchema = new Prompt(List.of(new UserMessage("what is the weather?")),
				optionsWithTool("llama3.2", toolCallback("getWeather", "Get the weather", schemaWithLf)));
		Prompt withCrlfSchema = new Prompt(List.of(new UserMessage("what is the weather?")),
				optionsWithTool("llama3.2", toolCallback("getWeather", "Get the weather", schemaWithCrlf)));

		assertThat(this.generator.generate(withCrlfSchema).hash())
			.as("BUG FIXED: the same logical schema, pretty-printed with Windows vs. Unix line endings, must hash "
					+ "identically -- recording on Windows and replaying on Linux CI must not be a cache miss")
			.isEqualTo(this.generator.generate(withLfSchema).hash());
	}

	@Test
	@DisplayName("BUG FIXED: an entity() call's format instructions and JSON schema hash identically whether "
			+ "they're pretty-printed with CRLF (Windows) or LF (Linux/macOS) line endings")
	void structuredOutputFormatCrlfAndLfHashIdentically() {
		Prompt samePromptText = prompt("Give me an example.", options("llama3.2", 0.0));
		String formatLf = "Your response should be in JSON format.\nHere is the schema:\n{\n  \"type\" : \"object\"\n}";
		String schemaLf = "{\n  \"type\" : \"object\",\n  \"properties\" : {\n    \"city\" : {\n      \"type\" : "
				+ "\"string\"\n    }\n  }\n}";

		String hashWithLf = this.generator.generate(samePromptText, structuredOutputContext(formatLf, schemaLf)).hash();
		String hashWithCrlf = this.generator
			.generate(samePromptText, structuredOutputContext(formatLf.replace("\n", "\r\n"), schemaLf.replace("\n", "\r\n")))
			.hash();

		assertThat(hashWithCrlf)
			.as("BUG FIXED: the same logical format instructions/schema, pretty-printed with Windows vs. Unix line "
					+ "endings, must hash identically")
			.isEqualTo(hashWithLf);
	}

	@Test
	@DisplayName("R3: generateForStream() produces a different hash than generate() for the identical prompt "
			+ "-- a VcrTrack and a VcrStreamTrack must never collide on one filename")
	void streamHashDiffersFromCallHashForTheIdenticalPrompt() {
		Prompt samePrompt = prompt("hello", options("llama3", 0.0));

		String callHash = this.generator.generate(samePrompt).hash();
		String streamHash = this.generator.generateForStream(samePrompt).hash();

		assertThat(streamHash).as("a stream and a call sharing the exact same prompt must not collide on one hash")
			.isNotEqualTo(callHash);
		assertThat(streamHash).hasSize(64).matches("[0-9a-f]{64}");
	}

	@Test
	@DisplayName("R3: generateForStream() is otherwise identical to generate() -- same sensitivity to prompt drift, "
			+ "same stability across repeated invocations")
	void streamHashIsOtherwiseStableAndSensitiveTheSameWay() {
		Prompt prompt = prompt("hello", options("llama3", 0.0));
		Prompt driftedPrompt = prompt("hello!", options("llama3", 0.0));

		assertThat(this.generator.generateForStream(prompt).hash())
			.as("stable across repeated invocations, same as generate()")
			.isEqualTo(this.generator.generateForStream(prompt).hash());
		assertThat(this.generator.generateForStream(driftedPrompt).hash())
			.as("a single character of prompt drift busts the stream cache too")
			.isNotEqualTo(this.generator.generateForStream(prompt).hash());
	}

	@Test
	@DisplayName("R3: generateForStream(prompt) with no context is equivalent to generateForStream(prompt, Map.of())")
	void streamHashNoContextOverloadMatchesEmptyContext() {
		Prompt prompt = prompt("hello", options("llama3", 0.0));

		assertThat(this.generator.generateForStream(prompt).hash())
			.isEqualTo(this.generator.generateForStream(prompt, Map.of()).hash());
	}

}
