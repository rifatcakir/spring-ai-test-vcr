package io.github.rifatcakir.springai.vcr.track;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.github.rifatcakir.springai.vcr.key.VcrCacheKey;
import io.github.rifatcakir.springai.vcr.key.VcrCacheKeyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Jackson workaround actually works.
 *
 * <p>The premise of {@link VcrTrack} is that Spring AI's response domain cannot survive a
 * JSON round trip but a narrow projection of it can. These tests hold that claim to
 * account, including the tool-call path, which is the case that forces the use of
 * {@code AssistantMessage.builder()} because the multi-argument constructor is
 * {@code protected}.
 *
 * @author Rifat Cakir
 */
class VcrTrackStoreRoundTripTests {

	@TempDir
	Path cacheDirectory;

	private final VcrTrackMapper mapper = new VcrTrackMapper();

	private final VcrCacheKeyGenerator keyGenerator = new VcrCacheKeyGenerator();

	private VcrCacheKey key() {
		return this.keyGenerator.generate(new Prompt(List.of(new UserMessage("what is the weather in Ankara?")),
				ChatOptions.builder().model("llama3.2").temperature(0.0).build()));
	}

	private Prompt prompt() {
		return new Prompt(List.of(new UserMessage("what is the weather in Ankara?")),
				ChatOptions.builder().model("llama3.2").temperature(0.0).build());
	}

	@Test
	@DisplayName("a plain text response round-trips through disk")
	void textRoundTrips() {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory);
		ChatResponse original = ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage("It is 31 degrees and sunny."),
					ChatGenerationMetadata.builder().finishReason("STOP").build())))
			.metadata(ChatResponseMetadata.builder()
				.id("chatcmpl-77")
				.model("llama3.2")
				.usage(new DefaultUsage(18, 9, 27))
				.build())
			.build();

		VcrCacheKey key = key();
		store.write(this.mapper.toTrack(key, prompt(), original));

		Optional<VcrTrack> reloaded = store.read(key.hash());
		assertThat(reloaded).isPresent();

		ChatResponse replayed = this.mapper.toChatResponse(reloaded.get());
		assertThat(replayed.getResult().getOutput().getText()).isEqualTo("It is 31 degrees and sunny.");
		assertThat(replayed.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(replayed.getMetadata().getId()).isEqualTo("chatcmpl-77");
		assertThat(replayed.getMetadata().getUsage().getPromptTokens()).isEqualTo(18);
		assertThat(replayed.getMetadata().getUsage().getCompletionTokens()).isEqualTo(9);
	}

	@Test
	@DisplayName("tool calls round-trip, including id, name and raw arguments")
	void toolCallsRoundTrip() {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory);
		AssistantMessage withToolCall = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "getWeather",
					"{\"city\":\"Ankara\"}")))
			.build();

		ChatResponse original = ChatResponse.builder()
			.generations(List.of(new Generation(withToolCall,
					ChatGenerationMetadata.builder().finishReason("TOOL_CALLS").build())))
			.metadata(ChatResponseMetadata.builder().model("llama3.2").build())
			.build();

		VcrCacheKey key = key();
		store.write(this.mapper.toTrack(key, prompt(), original));

		ChatResponse replayed = this.mapper.toChatResponse(store.read(key.hash()).orElseThrow());
		AssistantMessage output = replayed.getResult().getOutput();

		assertThat(output.hasToolCalls()).isTrue();
		assertThat(output.getToolCalls()).singleElement().satisfies(call -> {
			assertThat(call.id()).isEqualTo("call_1");
			assertThat(call.name()).isEqualTo("getWeather");
			assertThat(call.arguments()).isEqualTo("{\"city\":\"Ankara\"}");
		});
	}

	@Test
	@DisplayName("fixtures are human-readable, because they get reviewed in pull requests")
	void fixturesArePrettyPrinted() throws Exception {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory);
		VcrCacheKey key = key();
		store.write(this.mapper.toTrack(key, prompt(),
				ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("hi")))).build()));

		String json = Files.readString(store.pathFor(key.hash()));

		assertThat(json).contains(System.lineSeparator().equals("\r\n") ? "\n" : "\n");
		assertThat(json.lines().count()).isGreaterThan(5);
		assertThat(json).contains("\"canonicalRequest\"").contains("\"schemaVersion\" : \"1\"");
	}

	@Test
	@DisplayName("no API key or authorization header can appear in a fixture")
	void fixtureCarriesNoTransportSecrets() throws Exception {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory);
		VcrCacheKey key = key();
		store.write(this.mapper.toTrack(key, prompt(),
				ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("hi")))).build()));

		String json = Files.readString(store.pathFor(key.hash()));

		// Interception happens above the HTTP layer, so unlike VCR.py there is no
		// header-filtering step to forget.
		assertThat(json).doesNotContain("Authorization").doesNotContain("Bearer ").doesNotContain("api-key");
	}

	@Test
	@DisplayName("a corrupt fixture degrades to a miss instead of failing the build")
	void corruptFixtureIsTreatedAsMissing() throws Exception {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory);
		String hash = "a".repeat(64);
		Files.writeString(store.pathFor(hash), "{ this is not json");

		assertThat(store.read(hash)).isEmpty();
	}

	@Test
	@DisplayName("an unknown field written by a newer version still replays")
	void unknownFieldsAreTolerated() throws Exception {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory);
		String hash = "b".repeat(64);
		Files.writeString(store.pathFor(hash), """
				{
				  "schemaVersion" : "1",
				  "hash" : "%s",
				  "recordedAt" : "2026-07-19T12:00:00Z",
				  "canonicalRequest" : "irrelevant",
				  "someFieldFromTheFuture" : { "nested" : true },
				  "request" : null,
				  "response" : {
				    "id" : "x",
				    "model" : "llama3.2",
				    "generations" : [ { "text" : "hello", "finishReason" : "STOP", "toolCalls" : [] } ],
				    "usage" : null,
				    "metadata" : {}
				  }
				}
				""".formatted(hash));

		VcrTrack track = store.read(hash).orElseThrow();
		assertThat(this.mapper.toChatResponse(track).getResult().getOutput().getText()).isEqualTo("hello");
	}

}
