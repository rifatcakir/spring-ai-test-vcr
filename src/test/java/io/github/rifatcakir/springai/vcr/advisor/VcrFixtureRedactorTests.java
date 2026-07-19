package io.github.rifatcakir.springai.vcr.advisor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.rifatcakir.springai.vcr.VcrFixtureRedactor;
import io.github.rifatcakir.springai.vcr.VcrMode;
import io.github.rifatcakir.springai.vcr.VcrScope;
import io.github.rifatcakir.springai.vcr.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.vcr.track.VcrTrack;
import io.github.rifatcakir.springai.vcr.track.VcrTrackMapper;
import io.github.rifatcakir.springai.vcr.track.VcrTrackStore;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link VcrFixtureRedactor} is a second, narrower hook than {@link VcrCacheKeyGenerator}'s
 * normalizers: it can change what a reviewer sees in a committed fixture without being able to
 * change which fixture a prompt resolves to. Every test here is built around proving that
 * separation holds, not just that redaction "works":
 *
 * <ul>
 * <li>with no redactor registered, behaviour is bit-for-bit identical to before this feature
 * existed;</li>
 * <li>the hash a fixture is filed under cannot be changed by a redactor, even one that actively
 * tries to;</li>
 * <li>a redactor only ever touches what is written, never what a live caller or a replay
 * receives;</li>
 * <li>multiple redactors compose in registration order;</li>
 * <li>a throwing redactor fails loudly rather than shipping a partially-redacted fixture.</li>
 * </ul>
 *
 * @author Rifat Cakira
 */
class VcrFixtureRedactorTests {

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

	private DeterministicVcrAdvisor advisor(List<VcrFixtureRedactor> redactors) {
		return new DeterministicVcrAdvisor(this.keyGenerator, this.store, this.mapper, VcrMode.RECORD_OR_REPLAY,
				VcrScope.OUTSIDE_TOOL_LOOP, redactors);
	}

	private static ChatClientRequest request(String userText) {
		return requestWithModel(userText, "llama3.2");
	}

	private static ChatClientRequest requestWithModel(String userText, String model) {
		Prompt prompt = new Prompt(List.of(new UserMessage(userText)),
				ChatOptions.builder().model(model).temperature(0.0).build());
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
	@DisplayName("no redactor registered: fixture is identical to the pre-redactor constructor path")
	void noRedactorMeansUnchangedFixture() {
		// The constructor that existed before this feature — proves the new,
		// redactor-aware constructor changes nothing when handed an empty list.
		DeterministicVcrAdvisor legacyConstructor = new DeterministicVcrAdvisor(this.keyGenerator, this.store,
				this.mapper, VcrMode.RECORD_OR_REPLAY, VcrScope.OUTSIDE_TOOL_LOOP);
		legacyConstructor.adviseCall(request("my SECRET value"), chainReturning("42"));
		VcrTrack fromLegacyConstructor = readOnlyFixture();

		VcrTrackStore secondStore = new VcrTrackStore(this.cacheDirectory.resolve("second"));
		DeterministicVcrAdvisor explicitEmptyList = new DeterministicVcrAdvisor(this.keyGenerator, secondStore,
				this.mapper, VcrMode.RECORD_OR_REPLAY, VcrScope.OUTSIDE_TOOL_LOOP, List.of());
		explicitEmptyList.adviseCall(request("my SECRET value"), chainReturning("42"));
		Optional<VcrTrack> fromExplicitEmptyList = secondStore.read(fromLegacyConstructor.hash());

		assertThat(fromExplicitEmptyList).isPresent();
		assertThat(fromExplicitEmptyList.get().hash()).isEqualTo(fromLegacyConstructor.hash());
		assertThat(fromExplicitEmptyList.get().canonicalRequest()).isEqualTo(fromLegacyConstructor.canonicalRequest());
		assertThat(fromExplicitEmptyList.get().request()).isEqualTo(fromLegacyConstructor.request());
		assertThat(fromExplicitEmptyList.get().response()).isEqualTo(fromLegacyConstructor.response());
	}

	@Test
	@DisplayName("a redactor changes what is written, never what the live caller or a replay receives")
	void redactionNeverReachesAResponse() {
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

		DeterministicVcrAdvisor advisor = advisor(List.of(redactSecret));

		ChatClientResponse liveResponse = advisor.adviseCall(request("my SECRET value"), chainReturning("42"));
		assertThat(liveResponse.chatResponse().getResult().getOutput().getText()).isEqualTo("42");

		VcrTrack written = readOnlyFixture();
		assertThat(written.request().messages().get(0).text()).isEqualTo("my [REDACTED] value")
			.as("the committed fixture must show the redacted text, not the real one");

		ChatClientResponse replayed = advisor.adviseCall(request("my SECRET value"), chainReturning("should not run"));
		assertThat(replayed.chatResponse().getResult().getOutput().getText())
			.as("replay must still return the model's real recorded answer — redaction never touches the response")
			.isEqualTo("42");
	}

	@Test
	@DisplayName("a redactor cannot forge a different cache key, even trying to")
	void redactorCannotChangeTheHash() {
		VcrFixtureRedactor forgeHash = track -> new VcrTrack(track.schemaVersion(), "0".repeat(64), track.recordedAt(),
				"forged canonical request", track.request(), track.response());

		DeterministicVcrAdvisor withForgingRedactor = advisor(List.of(forgeHash));
		withForgingRedactor.adviseCall(request("meaning of life?"), chainReturning("42"));

		String realHash = this.keyGenerator.generate(request("meaning of life?").prompt()).hash();
		Optional<VcrTrack> written = this.store.read(realHash);

		assertThat(written).as("the fixture must be filed under the real hash, not the forged one").isPresent();
		assertThat(written.get().hash()).isEqualTo(realHash);
		assertThat(this.store.read("0".repeat(64))).as("nothing must exist under the forged hash").isEmpty();

		// And a completely separate advisor instance, with no redactor at all, still hits —
		// proving the redactor never touched what determines a cache hit.
		DeterministicVcrAdvisor plainAdvisor = new DeterministicVcrAdvisor(this.keyGenerator, this.store, this.mapper,
				VcrMode.REPLAY_ONLY, VcrScope.OUTSIDE_TOOL_LOOP);
		ChatClientResponse replayed = plainAdvisor.adviseCall(request("meaning of life?"),
				chainReturning("should not run"));
		assertThat(replayed.chatResponse().getResult().getOutput().getText()).isEqualTo("42");
	}

	@Test
	@DisplayName("multiple redactors apply in registration order")
	void multipleRedactorsApplyInOrder() {
		VcrFixtureRedactor aToB = renamingModel("a-model", "b-model");
		VcrFixtureRedactor bToC = renamingModel("b-model", "c-model");

		DeterministicVcrAdvisor aThenB = advisor(List.of(aToB, bToC));
		aThenB.adviseCall(requestWithModel("order test 1", "a-model"), chainReturning("42"));
		assertThat(readOnlyFixture().request().model()).as("a-model -> b-model -> c-model, applied in order")
			.isEqualTo("c-model");

		VcrTrackStore secondStore = new VcrTrackStore(this.cacheDirectory.resolve("reversed"));
		DeterministicVcrAdvisor bThenA = new DeterministicVcrAdvisor(this.keyGenerator, secondStore, this.mapper,
				VcrMode.RECORD_OR_REPLAY, VcrScope.OUTSIDE_TOOL_LOOP, List.of(bToC, aToB));
		bThenA.adviseCall(requestWithModel("order test 2", "a-model"), chainReturning("42"));
		Optional<VcrTrack> reversed = secondStore
			.read(this.keyGenerator.generate(requestWithModel("order test 2", "a-model").prompt()).hash());
		assertThat(reversed).isPresent();
		assertThat(reversed.get().request().model())
			.as("b-model -> c-model has no effect first (there is no b-model yet), then a-model -> b-model runs — "
					+ "ending at b-model, not c-model, proving order was respected rather than coincidentally correct")
			.isEqualTo("b-model");
	}

	private static VcrFixtureRedactor renamingModel(String from, String to) {
		return track -> new VcrTrack(track.schemaVersion(), track.hash(), track.recordedAt(),
				track.canonicalRequest(),
				new VcrTrack.RequestSnapshot(from.equals(track.request().model()) ? to : track.request().model(),
						track.request().temperature(), track.request().topP(), track.request().topK(),
						track.request().maxTokens(), track.request().stopSequences(), track.request().messages(),
						track.request().tools()),
				track.response());
	}

	@Test
	@DisplayName("a throwing redactor propagates and nothing is written")
	void throwingRedactorPropagatesAndWritesNothing() {
		VcrFixtureRedactor broken = track -> {
			throw new IllegalStateException("redactor is broken");
		};

		DeterministicVcrAdvisor advisor = advisor(List.of(broken));

		assertThatThrownBy(() -> advisor.adviseCall(request("meaning of life?"), chainReturning("42")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("redactor is broken");

		assertThat(this.cacheDirectory.toFile().listFiles())
			.as("a redactor failure must not leave a fixture behind")
			.isEmpty();
	}

	private VcrTrack readOnlyFixture() {
		var files = this.cacheDirectory.toFile().listFiles((dir, name) -> name.endsWith(".json"));
		assertThat(files).as("exactly one fixture must exist").hasSize(1);
		String hash = files[0].getName().replace(".json", "");
		Optional<VcrTrack> track = this.store.read(hash);
		assertThat(track).isPresent();
		return track.get();
	}

}
