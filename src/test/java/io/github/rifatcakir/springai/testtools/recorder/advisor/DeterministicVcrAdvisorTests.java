package io.github.rifatcakir.springai.testtools.recorder.advisor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.VcrScope;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
 * The behavioural contract that matters: on a hit, the downstream chain is never touched.
 *
 * <p>Asserting "the response text was correct" would pass even if the advisor called the
 * real model every time, so every test here counts chain invocations rather than trusting
 * the payload.
 *
 * @author Rifat Cakir
 */
class DeterministicVcrAdvisorTests {

	@TempDir
	Path cacheDirectory;

	private VcrTrackStore store;

	private VcrTrackMapper mapper;

	private VcrCacheKeyGenerator keyGenerator;

	private final AtomicInteger modelInvocations = new AtomicInteger();

	@BeforeEach
	void setUp() {
		this.store = new VcrTrackStore(this.cacheDirectory);
		this.mapper = new VcrTrackMapper();
		this.keyGenerator = new VcrCacheKeyGenerator();
		this.modelInvocations.set(0);
	}

	private DeterministicVcrAdvisor advisor(VcrMode mode) {
		return new DeterministicVcrAdvisor(this.keyGenerator, this.store, this.mapper, mode,
				VcrScope.OUTSIDE_TOOL_LOOP);
	}

	private static ChatClientRequest request(String userText) {
		Prompt prompt = new Prompt(List.of(new UserMessage(userText)),
				ChatOptions.builder().model("llama3.2").temperature(0.0).build());
		return new ChatClientRequest(prompt, Map.of("trace", "abc"));
	}

	private CallAdvisorChain chainReturning(String answer) {
		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		given(chain.nextCall(any())).willAnswer(invocation -> {
			this.modelInvocations.incrementAndGet();
			return new ChatClientResponse(liveResponse(answer), Map.of("trace", "abc"));
		});
		return chain;
	}

	private static ChatResponse liveResponse(String text) {
		Generation generation = new Generation(new AssistantMessage(text));
		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
			.id("resp-1")
			.model("llama3.2")
			.usage(new DefaultUsage(11, 22, 33))
			.build();
		return ChatResponse.builder().generations(List.of(generation)).metadata(metadata).build();
	}

	@Test
	@DisplayName("RECORD_OR_REPLAY: first call hits the model and writes a fixture")
	void firstCallRecords() {
		CallAdvisorChain chain = chainReturning("42");

		ChatClientResponse response = advisor(VcrMode.RECORD_OR_REPLAY).adviseCall(request("meaning of life?"), chain);

		assertThat(this.modelInvocations).hasValue(1);
		assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("42");
		assertThat(this.cacheDirectory.toFile().listFiles()).hasSize(1);
	}

	@Test
	@DisplayName("RECORD_OR_REPLAY: second identical call never reaches the model")
	void secondCallReplays() {
		DeterministicVcrAdvisor advisor = advisor(VcrMode.RECORD_OR_REPLAY);
		CallAdvisorChain chain = chainReturning("42");

		advisor.adviseCall(request("meaning of life?"), chain);
		ChatClientResponse replayed = advisor.adviseCall(request("meaning of life?"), chain);

		assertThat(this.modelInvocations).as("the model must not be called on a cache hit").hasValue(1);
		verify(chain, times(1)).nextCall(any());
		assertThat(replayed.chatResponse().getResult().getOutput().getText()).isEqualTo("42");
	}

	@Test
	@DisplayName("a replayed response carries usage and finish metadata")
	void replayPreservesMetadata() {
		DeterministicVcrAdvisor advisor = advisor(VcrMode.RECORD_OR_REPLAY);
		CallAdvisorChain chain = chainReturning("42");

		advisor.adviseCall(request("meaning of life?"), chain);
		ChatClientResponse replayed = advisor.adviseCall(request("meaning of life?"), chain);

		ChatResponseMetadata metadata = replayed.chatResponse().getMetadata();
		assertThat(metadata.getModel()).isEqualTo("llama3.2");
		assertThat(metadata.getUsage().getTotalTokens()).isEqualTo(33);
		assertThat((Boolean) metadata.getOrDefault("vcr.replayed", Boolean.FALSE)).isTrue();
	}

	@Test
	@DisplayName("a changed prompt busts the cache and re-records")
	void changedPromptMisses() {
		DeterministicVcrAdvisor advisor = advisor(VcrMode.RECORD_OR_REPLAY);
		CallAdvisorChain chain = chainReturning("42");

		advisor.adviseCall(request("meaning of life?"), chain);
		advisor.adviseCall(request("meaning of life???"), chain);

		assertThat(this.modelInvocations).hasValue(2);
		assertThat(this.cacheDirectory.toFile().listFiles()).hasSize(2);
	}

	@Test
	@DisplayName("REPLAY_ONLY: a miss fails loudly and never calls the model")
	void replayOnlyThrowsOnMiss() {
		CallAdvisorChain chain = chainReturning("42");
		ChatClientRequest request = request("never recorded");

		assertThatExceptionOfType(VcrCacheMissException.class)
			.isThrownBy(() -> advisor(VcrMode.REPLAY_ONLY).adviseCall(request, chain))
			.satisfies(ex -> {
				assertThat(ex.getHash()).hasSize(64);
				assertThat(ex.getCanonicalRequest()).contains("never recorded");
				assertThat(ex.getMessage()).contains("REPLAY_ONLY").contains("re-record");
			});

		verify(chain, never()).nextCall(any());
		assertThat(this.modelInvocations).hasValue(0);
	}

	@Test
	@DisplayName("REPLAY_ONLY: a hit replays normally")
	void replayOnlyServesHits() {
		CallAdvisorChain chain = chainReturning("42");
		advisor(VcrMode.RECORD_OR_REPLAY).adviseCall(request("meaning of life?"), chain);

		ChatClientResponse response = advisor(VcrMode.REPLAY_ONLY).adviseCall(request("meaning of life?"), chain);

		assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("42");
		assertThat(this.modelInvocations).hasValue(1);
	}

	@Test
	@DisplayName("RECORD_ALWAYS: ignores an existing fixture and overwrites it")
	void recordAlwaysOverwrites() {
		CallAdvisorChain first = chainReturning("42");
		advisor(VcrMode.RECORD_OR_REPLAY).adviseCall(request("meaning of life?"), first);

		CallAdvisorChain second = chainReturning("43");
		ChatClientResponse response = advisor(VcrMode.RECORD_ALWAYS).adviseCall(request("meaning of life?"), second);

		assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("43");
		assertThat(this.cacheDirectory.toFile().listFiles()).hasSize(1);

		// The overwrite stuck: a subsequent replay serves the new answer.
		ChatClientResponse replayed = advisor(VcrMode.REPLAY_ONLY).adviseCall(request("meaning of life?"), second);
		assertThat(replayed.chatResponse().getResult().getOutput().getText()).isEqualTo("43");
	}

	@Test
	@DisplayName("BYPASS: always delegates and writes nothing")
	void bypassWritesNothing() {
		CallAdvisorChain chain = chainReturning("42");
		DeterministicVcrAdvisor advisor = advisor(VcrMode.BYPASS);

		advisor.adviseCall(request("meaning of life?"), chain);
		advisor.adviseCall(request("meaning of life?"), chain);

		assertThat(this.modelInvocations).hasValue(2);
		assertThat(this.cacheDirectory.toFile().listFiles()).isEmpty();
	}

	@Test
	@DisplayName("the advise-context survives a replay")
	void replayPreservesContext() {
		DeterministicVcrAdvisor advisor = advisor(VcrMode.RECORD_OR_REPLAY);
		CallAdvisorChain chain = chainReturning("42");

		advisor.adviseCall(request("meaning of life?"), chain);
		ChatClientResponse replayed = advisor.adviseCall(request("meaning of life?"), chain);

		assertThat(replayed.context()).containsEntry("trace", "abc");
	}

	@Test
	@DisplayName("scope maps to an order on the correct side of ToolCallingAdvisor")
	void scopeMapsToOrder() {
		int toolLoop = DeterministicVcrAdvisor.TOOL_CALLING_ADVISOR_ORDER;

		assertThat(new DeterministicVcrAdvisor(this.keyGenerator, this.store, this.mapper, VcrMode.RECORD_OR_REPLAY,
				VcrScope.OUTSIDE_TOOL_LOOP)
			.getOrder()).isLessThan(toolLoop);

		assertThat(new DeterministicVcrAdvisor(this.keyGenerator, this.store, this.mapper, VcrMode.RECORD_OR_REPLAY,
				VcrScope.INSIDE_TOOL_LOOP)
			.getOrder()).isGreaterThan(toolLoop);
	}

}
