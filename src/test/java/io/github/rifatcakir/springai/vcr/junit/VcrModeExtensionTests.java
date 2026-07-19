package io.github.rifatcakir.springai.vcr.junit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.rifatcakir.springai.vcr.VcrCacheMissException;
import io.github.rifatcakir.springai.vcr.VcrFixtureRedactor;
import io.github.rifatcakir.springai.vcr.VcrMode;
import io.github.rifatcakir.springai.vcr.VcrModeOverride;
import io.github.rifatcakir.springai.vcr.VcrScope;
import io.github.rifatcakir.springai.vcr.advisor.DeterministicVcrAdvisor;
import io.github.rifatcakir.springai.vcr.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.vcr.track.VcrTrack;
import io.github.rifatcakir.springai.vcr.track.VcrTrackMapper;
import io.github.rifatcakir.springai.vcr.track.VcrTrackStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

/**
 * {@code @Vcr} is the escape hatch for a suite sealed in {@link VcrMode#REPLAY_ONLY}: one
 * test opts out, the rest of the run stays sealed. Every test here is built around proving
 * the "rest of the run stays sealed" half, not just that the override works in isolation —
 * that's the property that actually matters for CI.
 *
 * @author Rifat Cakira
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VcrModeExtensionTests {

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
		return new ChatClientRequest(prompt, Map.of());
	}

	private CallAdvisorChain chainReturning(String answer) {
		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		given(chain.nextCall(any())).willAnswer(invocation -> {
			this.modelInvocations.incrementAndGet();
			return new ChatClientResponse(liveResponse(answer), Map.of());
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
	@Order(1)
	@Vcr(mode = VcrMode.BYPASS)
	@DisplayName("@Vcr(mode = BYPASS) lets one test bypass a REPLAY_ONLY advisor entirely")
	void vcrAnnotationOverridesConfiguredMode() {
		CallAdvisorChain chain = chainReturning("42");

		ChatClientResponse response = advisor(VcrMode.REPLAY_ONLY).adviseCall(request("never recorded"), chain);

		assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("42");
		assertThat(this.modelInvocations).hasValue(1);
		assertThat(this.cacheDirectory.toFile().listFiles()).as("BYPASS never reads or writes").isEmpty();
	}

	@Test
	@Order(2)
	@DisplayName("a later, unannotated test proves the override from test 1 did not survive")
	void defaultBehaviorUnaffectedAfterAnAnnotatedTestRan() {
		assertThat(VcrModeOverride.current()).as("no override should be active entering this test").isEmpty();

		CallAdvisorChain chain = chainReturning("42");

		assertThatExceptionOfType(VcrCacheMissException.class)
			.isThrownBy(() -> advisor(VcrMode.REPLAY_ONLY).adviseCall(request("never recorded"), chain));
		assertThat(this.modelInvocations).as("REPLAY_ONLY must still refuse to call the model here")
			.hasValue(0);
	}

	@Test
	@Order(3)
	@Vcr(mode = VcrMode.RECORD_OR_REPLAY)
	@DisplayName("an override still runs the full record path, including a registered redactor")
	void overrideComposesWithRedactor() {
		VcrFixtureRedactor redactSecret = track -> new VcrTrack(track.schemaVersion(), track.hash(),
				track.recordedAt(), track.canonicalRequest(),
				new VcrTrack.RequestSnapshot(track.request().model(), track.request().temperature(),
						track.request().topP(), track.request().topK(), track.request().maxTokens(),
						track.request().stopSequences(),
						track.request()
							.messages()
							.stream()
							.map(message -> new VcrTrack.MessageSnapshot(message.type(),
									message.text().replace("SECRET", "[REDACTED]")))
							.toList(),
						track.request().tools()),
				track.response());

		DeterministicVcrAdvisor advisor = new DeterministicVcrAdvisor(this.keyGenerator, this.store, this.mapper,
				VcrMode.REPLAY_ONLY, VcrScope.OUTSIDE_TOOL_LOOP, List.of(redactSecret));

		// Configured mode is REPLAY_ONLY — this miss would throw without the @Vcr override
		// on this test method making the effective mode RECORD_OR_REPLAY instead.
		ChatClientResponse response = advisor.adviseCall(request("my SECRET value"), chainReturning("42"));

		assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("42");
		var files = this.cacheDirectory.toFile().listFiles((dir, name) -> name.endsWith(".json"));
		assertThat(files).hasSize(1);
		Optional<VcrTrack> written = this.store.read(files[0].getName().replace(".json", ""));
		assertThat(written).isPresent();
		assertThat(written.get().request().messages().get(0).text())
			.as("the redactor must still run when the effective mode came from an override, not the advisor's own configuration")
			.isEqualTo("my [REDACTED] value");
	}

	@Nested
	@Vcr(mode = VcrMode.BYPASS)
	@DisplayName("a class-level @Vcr")
	class ClassLevelOverride {

		@Test
		@DisplayName("applies to a method with no annotation of its own")
		void appliesWithoutMethodAnnotation() {
			CallAdvisorChain chain = chainReturning("42");

			ChatClientResponse response = advisor(VcrMode.REPLAY_ONLY).adviseCall(request("never recorded"), chain);

			assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("42");
			assertThat(VcrModeExtensionTests.this.modelInvocations).hasValue(1);
		}

		@Test
		@Vcr(mode = VcrMode.REPLAY_ONLY)
		@DisplayName("a method-level @Vcr overrides the class-level one")
		void methodLevelOverridesClassLevel() {
			CallAdvisorChain chain = chainReturning("42");

			assertThatExceptionOfType(VcrCacheMissException.class)
				.isThrownBy(() -> advisor(VcrMode.REPLAY_ONLY).adviseCall(request("never recorded"), chain));
		}

	}

}
