package io.github.rifatcakir.springai.testtools.recorder.stream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKey;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
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
 * Proves {@link VcrStreamTrack} round-trips through disk chunk-for-chunk — the streaming
 * counterpart of {@code VcrTrackStoreRoundTripTests}. The property that matters here and
 * has no analogue in the blocking-call tests: the raw chunk sequence, not just the
 * aggregate, must survive the trip unchanged, in order.
 *
 * @author Rifat Cakir
 */
class VcrStreamTrackStoreRoundTripTests {

	@TempDir
	Path cacheDirectory;

	private final VcrStreamTrackMapper mapper = new VcrStreamTrackMapper();

	private final VcrCacheKeyGenerator keyGenerator = new VcrCacheKeyGenerator();

	private Prompt prompt() {
		return new Prompt(List.of(new UserMessage("what is the weather in Ankara?")),
				ChatOptions.builder().model("llama3.2").temperature(0.0).build());
	}

	private VcrCacheKey key() {
		return this.keyGenerator.generateForStream(prompt());
	}

	private static ChatResponse chunk(String text, String finishReason) {
		ChatGenerationMetadata generationMetadata = (finishReason == null) ? ChatGenerationMetadata.NULL
				: ChatGenerationMetadata.builder().finishReason(finishReason).build();
		return ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage(text), generationMetadata)))
			.build();
	}

	@Test
	@DisplayName("a multi-chunk text stream round-trips chunk-for-chunk, in order")
	void multiChunkTextStreamRoundTrips() {
		VcrStreamTrackStore store = new VcrStreamTrackStore(this.cacheDirectory);
		VcrCacheKey key = key();
		List<ChatResponse> original = List.of(chunk("The", null), chunk(" answer", null), chunk(" is", null),
				chunk(" 42.", "STOP"));

		store.write(this.mapper.toTrack(key, prompt(), java.util.Map.of(), original));

		Optional<VcrStreamTrack> reloaded = store.read(key.hash());
		assertThat(reloaded).isPresent();

		List<ChatResponse> replayed = this.mapper.toChatResponseChunks(reloaded.get());
		assertThat(replayed).hasSize(4);
		assertThat(replayed.stream().map(r -> r.getResult().getOutput().getText()).toList()).containsExactly("The",
				" answer", " is", " 42.");
		assertThat(replayed.get(3).getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(replayed.get(0).getResult().getMetadata().getFinishReason()).isNull();
	}

	@Test
	@DisplayName("the review-only aggregate fields are computed correctly at record time")
	void aggregateFieldsAreComputed() {
		VcrStreamTrackStore store = new VcrStreamTrackStore(this.cacheDirectory);
		VcrCacheKey key = key();
		List<ChatResponse> original = List.of(chunk("The", null), chunk(" answer", null), chunk(" is 42.", "STOP"));

		store.write(this.mapper.toTrack(key, prompt(), java.util.Map.of(), original));

		VcrStreamTrack track = store.read(key.hash()).orElseThrow();
		assertThat(track.response().aggregateText()).isEqualTo("The answer is 42.");
		assertThat(track.response().aggregateFinishReason()).isEqualTo("STOP");
		assertThat(track.response().aggregateToolCalls()).isEmpty();
	}

	@Test
	@DisplayName("a streamed tool call round-trips whole, including id, name and raw arguments")
	void toolCallChunkRoundTrips() {
		VcrStreamTrackStore store = new VcrStreamTrackStore(this.cacheDirectory);
		VcrCacheKey key = key();
		AssistantMessage toolCallMessage = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "getWeather",
					"{\"city\":\"Ankara\"}")))
			.build();
		ChatResponse toolCallChunk = ChatResponse.builder().generations(List.of(new Generation(toolCallMessage))).build();

		store.write(this.mapper.toTrack(key, prompt(), java.util.Map.of(), List.of(toolCallChunk)));

		VcrStreamTrack track = store.read(key.hash()).orElseThrow();
		List<ChatResponse> replayed = this.mapper.toChatResponseChunks(track);
		AssistantMessage.ToolCall replayedCall = replayed.get(0).getResult().getOutput().getToolCalls().get(0);

		assertThat(replayedCall.id()).isEqualTo("call_1");
		assertThat(replayedCall.name()).isEqualTo("getWeather");
		assertThat(replayedCall.arguments()).isEqualTo("{\"city\":\"Ankara\"}");
		assertThat(track.response().aggregateToolCalls()).singleElement()
			.satisfies(call -> assertThat(call.name()).isEqualTo("getWeather"));
	}

	@Test
	@DisplayName("a single-chunk stream round-trips")
	void singleChunkStreamRoundTrips() {
		VcrStreamTrackStore store = new VcrStreamTrackStore(this.cacheDirectory);
		VcrCacheKey key = key();

		store.write(this.mapper.toTrack(key, prompt(), java.util.Map.of(), List.of(chunk("hi", "STOP"))));

		List<ChatResponse> replayed = this.mapper.toChatResponseChunks(store.read(key.hash()).orElseThrow());
		assertThat(replayed).singleElement().satisfies(response -> {
			assertThat(response.getResult().getOutput().getText()).isEqualTo("hi");
			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		});
	}

	@Test
	@DisplayName("an empty stream (zero chunks) round-trips to an empty chunk list, not null")
	void emptyStreamRoundTrips() {
		VcrStreamTrackStore store = new VcrStreamTrackStore(this.cacheDirectory);
		VcrCacheKey key = key();

		store.write(this.mapper.toTrack(key, prompt(), java.util.Map.of(), List.of()));

		VcrStreamTrack track = store.read(key.hash()).orElseThrow();
		assertThat(track.response().chunks()).isEmpty();
		assertThat(track.response().aggregateText()).isEmpty();
		assertThat(track.response().aggregateFinishReason()).isNull();
		assertThat(this.mapper.toChatResponseChunks(track)).isEmpty();
	}

	@Test
	@DisplayName("a chunk with empty text round-trips as empty, not null")
	void emptyTextChunkRoundTrips() {
		VcrStreamTrackStore store = new VcrStreamTrackStore(this.cacheDirectory);
		VcrCacheKey key = key();

		store.write(this.mapper.toTrack(key, prompt(), java.util.Map.of(), List.of(chunk("", null), chunk("hi", "STOP"))));

		List<ChatResponse> replayed = this.mapper.toChatResponseChunks(store.read(key.hash()).orElseThrow());
		assertThat(replayed).hasSize(2);
		assertThat(replayed.get(0).getResult().getOutput().getText()).isEmpty();
		assertThat(replayed.get(1).getResult().getOutput().getText()).isEqualTo("hi");
	}

	@Test
	@DisplayName("chunk-level usage and id/model metadata round-trip")
	void chunkMetadataRoundTrips() {
		VcrStreamTrackStore store = new VcrStreamTrackStore(this.cacheDirectory);
		VcrCacheKey key = key();
		ChatResponse finalChunk = ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage("hi"),
					ChatGenerationMetadata.builder().finishReason("STOP").build())))
			.metadata(ChatResponseMetadata.builder()
				.id("chatcmpl-77")
				.model("llama3.2")
				.usage(new DefaultUsage(18, 9, 27))
				.build())
			.build();

		store.write(this.mapper.toTrack(key, prompt(), java.util.Map.of(), List.of(finalChunk)));

		ChatResponse replayed = this.mapper.toChatResponseChunks(store.read(key.hash()).orElseThrow()).get(0);
		assertThat(replayed.getMetadata().getId()).isEqualTo("chatcmpl-77");
		assertThat(replayed.getMetadata().getModel()).isEqualTo("llama3.2");
		assertThat(replayed.getMetadata().getUsage().getPromptTokens()).isEqualTo(18);
		assertThat(replayed.getMetadata().getUsage().getCompletionTokens()).isEqualTo(9);
	}

	@Test
	@DisplayName("a stream fixture never collides with a call fixture for the identical prompt")
	void streamHashNeverCollidesWithCallHash() {
		VcrCacheKeyGenerator generator = new VcrCacheKeyGenerator();
		VcrCacheKey callKey = generator.generate(prompt());
		VcrCacheKey streamKey = generator.generateForStream(prompt());

		assertThat(streamKey.hash()).isNotEqualTo(callKey.hash());
	}

	@Test
	@DisplayName("fixtures are human-readable, because they get reviewed in pull requests")
	void fixturesArePrettyPrinted() throws Exception {
		VcrStreamTrackStore store = new VcrStreamTrackStore(this.cacheDirectory);
		VcrCacheKey key = key();

		store.write(this.mapper.toTrack(key, prompt(), java.util.Map.of(), List.of(chunk("hi", "STOP"))));

		String json = Files.readString(store.pathFor(key.hash()));
		assertThat(json.lines().count()).isGreaterThan(5);
		assertThat(json).contains("\"canonicalRequest\"")
			.contains("\"schemaVersion\" : \"" + VcrStreamTrack.CURRENT_SCHEMA_VERSION + "\"")
			.contains("\"aggregateText\"");
	}

	@Test
	@DisplayName("a corrupt stream fixture degrades to a miss instead of failing the build")
	void corruptFixtureIsTreatedAsMissing() throws Exception {
		VcrStreamTrackStore store = new VcrStreamTrackStore(this.cacheDirectory);
		String hash = "a".repeat(64);
		Files.writeString(store.pathFor(hash), "{ this is not json");

		assertThat(store.read(hash)).isEmpty();
	}

	@Test
	@DisplayName("an unknown field written by a newer version still replays")
	void unknownFieldsAreTolerated() throws Exception {
		VcrStreamTrackStore store = new VcrStreamTrackStore(this.cacheDirectory);
		String hash = "b".repeat(64);
		Files.writeString(store.pathFor(hash), """
				{
				  "schemaVersion" : "1",
				  "hash" : "%s",
				  "recordedAt" : "2026-07-24T12:00:00Z",
				  "canonicalRequest" : "irrelevant",
				  "someFieldFromTheFuture" : { "nested" : true },
				  "request" : null,
				  "response" : {
				    "chunks" : [ { "id" : "x", "model" : "llama3.2", "text" : "hi", "finishReason" : "STOP", "toolCalls" : [], "usage" : null } ],
				    "aggregateText" : "hi",
				    "aggregateFinishReason" : "STOP",
				    "aggregateToolCalls" : []
				  }
				}
				""".formatted(hash));

		VcrStreamTrack track = store.read(hash).orElseThrow();
		List<ChatResponse> replayed = this.mapper.toChatResponseChunks(track);
		assertThat(replayed).singleElement()
			.satisfies(response -> assertThat(response.getResult().getOutput().getText()).isEqualTo("hi"));
	}

}
