package io.github.rifatcakir.springai.testtools.recorder.advisor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.VcrScope;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * R3's behavioural contract for {@link DeterministicVcrAdvisor#adviseStream}, mirroring
 * {@link DeterministicVcrAdvisorTests}'s own discipline exactly: assert on chain
 * invocation counts, not just payloads, and — the property specific to streaming — on
 * <em>chunk-for-chunk</em> equality between what was recorded and what replays, not
 * just that the aggregated text matches. A test that only checked the final aggregate
 * would pass even if chunk boundaries were reshuffled or merged, which is exactly the
 * failure mode storing raw chunks (rather than only an aggregate) exists to catch.
 *
 * @author Rifat Cakir
 */
class DeterministicVcrAdvisorStreamTests {

	@TempDir
	Path cacheDirectory;

	private VcrTrackStore store;

	private VcrTrackMapper mapper;

	private VcrStreamTrackStore streamStore;

	private VcrStreamTrackMapper streamMapper;

	private VcrCacheKeyGenerator keyGenerator;

	private final AtomicInteger modelSubscriptions = new AtomicInteger();

	@BeforeEach
	void setUp() {
		this.store = new VcrTrackStore(this.cacheDirectory);
		this.mapper = new VcrTrackMapper();
		this.streamStore = new VcrStreamTrackStore(this.cacheDirectory);
		this.streamMapper = new VcrStreamTrackMapper();
		this.keyGenerator = new VcrCacheKeyGenerator();
		this.modelSubscriptions.set(0);
	}

	private DeterministicVcrAdvisor streamingAdvisor(VcrMode mode) {
		return new DeterministicVcrAdvisor(this.keyGenerator, this.store, this.mapper, mode,
				VcrScope.OUTSIDE_TOOL_LOOP, List.of(), this.streamStore, this.streamMapper);
	}

	private DeterministicVcrAdvisor nonStreamingAdvisor(VcrMode mode) {
		return new DeterministicVcrAdvisor(this.keyGenerator, this.store, this.mapper, mode,
				VcrScope.OUTSIDE_TOOL_LOOP);
	}

	private static ChatClientRequest request(String userText) {
		Prompt prompt = new Prompt(List.of(new UserMessage(userText)),
				ChatOptions.builder().model("llama3.2").temperature(0.0).build());
		return new ChatClientRequest(prompt, Map.of("trace", "abc"));
	}

	/**
	 * A live stream chain returning one chunk per given text, each as its own {@code
	 * ChatResponse} -- mirrors a real provider's small-delta-per-chunk shape. Counts
	 * subscriptions, not chunks, since "the model was invoked" means "the live Flux was
	 * subscribed to," not "N chunks flowed."
	 */
	private StreamAdvisorChain chainReturning(String... chunkTexts) {
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		given(chain.nextStream(any())).willAnswer(invocation -> Flux.fromIterable(List.of(chunkTexts))
			.map(text -> new ChatClientResponse(chunkResponse(text, null), Map.of("trace", "abc")))
			.doOnSubscribe(subscription -> this.modelSubscriptions.incrementAndGet()));
		return chain;
	}

	private static ChatResponse chunkResponse(String text, String finishReason) {
		ChatGenerationMetadata generationMetadata = (finishReason == null) ? ChatGenerationMetadata.NULL
				: ChatGenerationMetadata.builder().finishReason(finishReason).build();
		Generation generation = new Generation(new AssistantMessage(text), generationMetadata);
		return ChatResponse.builder().generations(List.of(generation)).build();
	}

	private static List<String> chunkTexts(Flux<ChatClientResponse> flux) {
		return flux.map(response -> response.chatResponse().getResult().getOutput().getText())
			.collectList()
			.block();
	}

	@Test
	@DisplayName("RECORD_OR_REPLAY: first stream subscribes to the model and writes a fixture with every chunk")
	void firstStreamRecords() {
		StreamAdvisorChain chain = chainReturning("Hel", "lo", "!");

		List<String> chunks = chunkTexts(streamingAdvisor(VcrMode.RECORD_OR_REPLAY).adviseStream(request("hi"), chain));

		assertThat(this.modelSubscriptions).hasValue(1);
		assertThat(chunks).containsExactly("Hel", "lo", "!");
		assertThat(this.cacheDirectory.toFile().listFiles()).hasSize(1);
	}

	@Test
	@DisplayName("RECORD_OR_REPLAY: second identical stream never subscribes to the model again")
	void secondStreamReplays() {
		DeterministicVcrAdvisor advisor = streamingAdvisor(VcrMode.RECORD_OR_REPLAY);
		StreamAdvisorChain chain = chainReturning("Hel", "lo", "!");

		chunkTexts(advisor.adviseStream(request("hi"), chain));
		List<String> replayed = chunkTexts(advisor.adviseStream(request("hi"), chain));

		assertThat(this.modelSubscriptions).as("the model must not be subscribed to on a cache hit").hasValue(1);
		verify(chain, times(1)).nextStream(any());
		assertThat(replayed).containsExactly("Hel", "lo", "!");
	}

	@Test
	@DisplayName("replay reproduces the exact chunk sequence, not just a matching aggregate -- chunk count and order both verified")
	void replayIsChunkForChunkExact() {
		DeterministicVcrAdvisor advisor = streamingAdvisor(VcrMode.RECORD_OR_REPLAY);
		StreamAdvisorChain chain = chainReturning("The", " answer", " is", " 42.");

		List<String> original = chunkTexts(advisor.adviseStream(request("what is the answer?"), chain));
		List<String> replayed = chunkTexts(advisor.adviseStream(request("what is the answer?"), chain));

		assertThat(replayed).as("chunk-for-chunk equal to the original, in the same order -- not just the same "
				+ "concatenated text").containsExactlyElementsOf(original);
		assertThat(replayed).hasSize(4);
	}

	@Test
	@DisplayName("a replayed chunk carries finish-reason metadata on the chunk that had it")
	void replayPreservesChunkMetadata() {
		DeterministicVcrAdvisor advisor = streamingAdvisor(VcrMode.RECORD_OR_REPLAY);
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		given(chain.nextStream(any())).willAnswer(invocation -> Flux
			.just(new ChatClientResponse(chunkResponse("42", null), Map.of()),
					new ChatClientResponse(chunkResponse("", "stop"), Map.of()))
			.doOnSubscribe(s -> this.modelSubscriptions.incrementAndGet()));

		advisor.adviseStream(request("meaning of life?"), chain).collectList().block();
		List<ChatClientResponse> replayed = advisor.adviseStream(request("meaning of life?"), chain)
			.collectList()
			.block();

		assertThat(replayed).hasSize(2);
		assertThat(replayed.get(0).chatResponse().getResult().getMetadata().getFinishReason()).isNull();
		assertThat(replayed.get(1).chatResponse().getResult().getMetadata().getFinishReason()).isEqualTo("stop");
		assertThat((Boolean) replayed.get(0).chatResponse().getMetadata().getOrDefault("vcr.replayed", Boolean.FALSE))
			.isTrue();
	}

	@Test
	@DisplayName("a streamed tool call replays with the exact same id/name/arguments -- R3 v1 includes tool-call chunks (diagnosis confirmed Ollama delivers them whole, not fragmented)")
	void replayPreservesAStreamedToolCall() {
		DeterministicVcrAdvisor advisor = streamingAdvisor(VcrMode.RECORD_OR_REPLAY);
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call_1", "function", "getOrderStatus",
				"{\"orderId\":\"ORD-4471\"}");
		AssistantMessage toolCallMessage = AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
		given(chain.nextStream(any())).willAnswer(invocation -> Flux
			.just(new ChatClientResponse(
					ChatResponse.builder().generations(List.of(new Generation(toolCallMessage))).build(), Map.of()))
			.doOnSubscribe(s -> this.modelSubscriptions.incrementAndGet()));

		advisor.adviseStream(request("check my order"), chain).collectList().block();
		ChatClientResponse replayed = advisor.adviseStream(request("check my order"), chain).blockFirst();

		assertThat(this.modelSubscriptions).as("the second call must be served from the fixture, not the live model")
			.hasValue(1);
		List<AssistantMessage.ToolCall> replayedToolCalls = replayed.chatResponse()
			.getResult()
			.getOutput()
			.getToolCalls();
		assertThat(replayedToolCalls).hasSize(1);
		assertThat(replayedToolCalls.get(0).id()).isEqualTo("call_1");
		assertThat(replayedToolCalls.get(0).name()).isEqualTo("getOrderStatus");
		assertThat(replayedToolCalls.get(0).arguments()).isEqualTo("{\"orderId\":\"ORD-4471\"}");
	}

	@Test
	@DisplayName("a changed prompt busts the stream cache and re-records")
	void changedPromptMisses() {
		DeterministicVcrAdvisor advisor = streamingAdvisor(VcrMode.RECORD_OR_REPLAY);
		StreamAdvisorChain chain = chainReturning("42");

		chunkTexts(advisor.adviseStream(request("meaning of life?"), chain));
		chunkTexts(advisor.adviseStream(request("meaning of life???"), chain));

		assertThat(this.modelSubscriptions).hasValue(2);
		assertThat(this.cacheDirectory.toFile().listFiles()).hasSize(2);
	}

	@Test
	@DisplayName("REPLAY_ONLY: a miss fails loudly and never subscribes to the model")
	void replayOnlyThrowsOnMiss() {
		StreamAdvisorChain chain = chainReturning("42");
		ChatClientRequest request = request("never recorded");

		assertThatExceptionOfType(VcrCacheMissException.class)
			.isThrownBy(() -> streamingAdvisor(VcrMode.REPLAY_ONLY).adviseStream(request, chain))
			.satisfies(ex -> {
				assertThat(ex.getHash()).hasSize(64);
				assertThat(ex.getCanonicalRequest()).contains("never recorded");
				assertThat(ex.getMessage()).contains("REPLAY_ONLY").contains("re-record");
			});

		verify(chain, never()).nextStream(any());
		assertThat(this.modelSubscriptions).hasValue(0);
	}

	@Test
	@DisplayName("REPLAY_ONLY: a hit replays normally")
	void replayOnlyServesHits() {
		StreamAdvisorChain chain = chainReturning("42");
		chunkTexts(streamingAdvisor(VcrMode.RECORD_OR_REPLAY).adviseStream(request("meaning of life?"), chain));

		List<String> replayed = chunkTexts(streamingAdvisor(VcrMode.REPLAY_ONLY).adviseStream(request("meaning of life?"), chain));

		assertThat(replayed).containsExactly("42");
		assertThat(this.modelSubscriptions).hasValue(1);
	}

	@Test
	@DisplayName("RECORD_ALWAYS: ignores an existing fixture and overwrites it")
	void recordAlwaysOverwrites() {
		StreamAdvisorChain first = chainReturning("42");
		chunkTexts(streamingAdvisor(VcrMode.RECORD_OR_REPLAY).adviseStream(request("meaning of life?"), first));

		StreamAdvisorChain second = chainReturning("43");
		List<String> chunks = chunkTexts(streamingAdvisor(VcrMode.RECORD_ALWAYS).adviseStream(request("meaning of life?"), second));

		assertThat(chunks).containsExactly("43");
		assertThat(this.cacheDirectory.toFile().listFiles()).hasSize(1);

		List<String> replayed = chunkTexts(streamingAdvisor(VcrMode.REPLAY_ONLY).adviseStream(request("meaning of life?"), second));
		assertThat(replayed).containsExactly("43");
	}

	@Test
	@DisplayName("BYPASS: always delegates and writes nothing")
	void bypassWritesNothing() {
		StreamAdvisorChain chain = chainReturning("42");
		DeterministicVcrAdvisor advisor = streamingAdvisor(VcrMode.BYPASS);

		chunkTexts(advisor.adviseStream(request("meaning of life?"), chain));
		chunkTexts(advisor.adviseStream(request("meaning of life?"), chain));

		assertThat(this.modelSubscriptions).hasValue(2);
		assertThat(this.cacheDirectory.toFile().listFiles()).isEmpty();
	}

	@Test
	@DisplayName("the advise-context survives a replay")
	void replayPreservesContext() {
		DeterministicVcrAdvisor advisor = streamingAdvisor(VcrMode.RECORD_OR_REPLAY);
		StreamAdvisorChain chain = chainReturning("42");

		advisor.adviseStream(request("meaning of life?"), chain).blockLast();
		ChatClientResponse replayed = advisor.adviseStream(request("meaning of life?"), chain).blockFirst();

		assertThat(replayed.context()).containsEntry("trace", "abc");
	}

	@Test
	@DisplayName("streaming is inert on an advisor built without a VcrStreamTrackStore/VcrStreamTrackMapper -- passes through live, fully backward compatible")
	void streamingInertWithoutStreamStoreAndMapper() {
		StreamAdvisorChain chain = chainReturning("42");
		DeterministicVcrAdvisor advisor = nonStreamingAdvisor(VcrMode.REPLAY_ONLY);

		// REPLAY_ONLY would normally throw on a miss -- but streaming isn't configured
		// on this advisor at all, so it must pass straight through instead of even
		// attempting to look up a fixture.
		List<String> chunks = chunkTexts(advisor.adviseStream(request("anything"), chain));

		assertThat(chunks).containsExactly("42");
		assertThat(this.modelSubscriptions).hasValue(1);
		assertThat(this.cacheDirectory.toFile().listFiles()).isEmpty();
	}

}
