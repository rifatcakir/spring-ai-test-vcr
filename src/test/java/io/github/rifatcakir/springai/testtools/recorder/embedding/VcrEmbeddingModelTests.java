package io.github.rifatcakir.springai.testtools.recorder.embedding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The behavioural contract that matters, mirroring {@code DeterministicVcrAdvisorTests}
 * for the embedding side: on a hit, the delegate is never touched, and a replayed
 * vector is exactly (not "approximately", not "same length") what was recorded.
 *
 * @author Rifat Cakir
 */
class VcrEmbeddingModelTests {

	@TempDir
	Path cacheDirectory;

	private VcrEmbeddingTrackStore store;

	private VcrEmbeddingTrackMapper mapper;

	private VcrEmbeddingCacheKeyGenerator keyGenerator;

	private final AtomicInteger modelInvocations = new AtomicInteger();

	@BeforeEach
	void setUp() {
		this.store = new VcrEmbeddingTrackStore(this.cacheDirectory);
		this.mapper = new VcrEmbeddingTrackMapper();
		this.keyGenerator = new VcrEmbeddingCacheKeyGenerator();
		this.modelInvocations.set(0);
	}

	private VcrEmbeddingModel vcrModel(EmbeddingModel delegate, VcrMode mode) {
		return new VcrEmbeddingModel(delegate, this.keyGenerator, this.store, this.mapper, mode);
	}

	private static EmbeddingRequest request(String... inputs) {
		return new EmbeddingRequest(List.of(inputs), EmbeddingOptions.builder().model("test-embed").build());
	}

	private EmbeddingModel delegateReturning(float[]... vectors) {
		EmbeddingModel delegate = mock(EmbeddingModel.class);
		given(delegate.call(any())).willAnswer(invocation -> {
			this.modelInvocations.incrementAndGet();
			return liveResponse(vectors);
		});
		return delegate;
	}

	private static EmbeddingResponse liveResponse(float[]... vectors) {
		List<Embedding> embeddings = new ArrayList<>();
		for (int i = 0; i < vectors.length; i++) {
			embeddings.add(new Embedding(vectors[i], i));
		}
		EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata("test-embed", null);
		return new EmbeddingResponse(embeddings, metadata);
	}

	@Test
	@DisplayName("RECORD_OR_REPLAY: first call hits the model and writes a fixture")
	void firstCallRecords() {
		EmbeddingModel delegate = delegateReturning(new float[] { 0.1f, 0.2f, 0.3f });

		EmbeddingResponse response = vcrModel(delegate, VcrMode.RECORD_OR_REPLAY).call(request("hello world"));

		assertThat(this.modelInvocations).hasValue(1);
		assertThat(response.getResult().getOutput()).isEqualTo(new float[] { 0.1f, 0.2f, 0.3f });
		assertThat(this.cacheDirectory.toFile().listFiles()).hasSize(1);
	}

	@Test
	@DisplayName("RECORD_OR_REPLAY: second identical call never reaches the model, and the replayed vector is exact")
	void secondCallReplaysExactVector() {
		float[] vector = { 0.123456f, -0.654321f, 0.0f, 1.0f, -1.0f };
		VcrEmbeddingModel vcrModel = vcrModel(delegateReturning(vector), VcrMode.RECORD_OR_REPLAY);

		vcrModel.call(request("hello world"));
		EmbeddingResponse replayed = vcrModel.call(request("hello world"));

		assertThat(this.modelInvocations).as("the model must not be called on a cache hit").hasValue(1);
		assertThat(replayed.getResult().getOutput()).as("the replayed vector must be exactly the recorded one, "
				+ "not merely the same length or approximately equal").isEqualTo(vector);
	}

	@Test
	@DisplayName("a replayed response carries usage and model metadata, and a vcr.replayed marker")
	void replayPreservesMetadata() {
		VcrEmbeddingModel vcrModel = vcrModel(delegateReturning(new float[] { 0.1f }), VcrMode.RECORD_OR_REPLAY);

		vcrModel.call(request("hello world"));
		EmbeddingResponse replayed = vcrModel.call(request("hello world"));

		assertThat(replayed.getMetadata().getModel()).isEqualTo("test-embed");
		assertThat((Boolean) replayed.getMetadata().getOrDefault("vcr.replayed", Boolean.FALSE)).isTrue();
	}

	@Test
	@DisplayName("a batch of inputs each replay with their own exact vector, in order")
	void batchReplaysEachVectorInOrder() {
		float[] first = { 0.1f, 0.2f };
		float[] second = { 0.9f, 0.8f };
		VcrEmbeddingModel vcrModel = vcrModel(delegateReturning(first, second), VcrMode.RECORD_OR_REPLAY);

		vcrModel.call(request("alpha", "beta"));
		EmbeddingResponse replayed = vcrModel.call(request("alpha", "beta"));

		assertThat(replayed.getResults()).hasSize(2);
		assertThat(replayed.getResults().get(0).getOutput()).isEqualTo(first);
		assertThat(replayed.getResults().get(1).getOutput()).isEqualTo(second);
	}

	@Test
	@DisplayName("a changed input busts the cache and re-records")
	void changedInputMisses() {
		VcrEmbeddingModel vcrModel = vcrModel(delegateReturning(new float[] { 0.1f }), VcrMode.RECORD_OR_REPLAY);

		vcrModel.call(request("hello world"));
		vcrModel.call(request("hello world!"));

		assertThat(this.modelInvocations).hasValue(2);
		assertThat(this.cacheDirectory.toFile().listFiles()).hasSize(2);
	}

	@Test
	@DisplayName("REPLAY_ONLY: a miss fails loudly and never calls the model")
	void replayOnlyThrowsOnMiss() {
		EmbeddingModel delegate = delegateReturning(new float[] { 0.1f });
		EmbeddingRequest request = request("never recorded");

		assertThatExceptionOfType(VcrCacheMissException.class)
			.isThrownBy(() -> vcrModel(delegate, VcrMode.REPLAY_ONLY).call(request))
			.satisfies(ex -> {
				assertThat(ex.getHash()).hasSize(64);
				assertThat(ex.getCanonicalRequest()).contains("never recorded");
				assertThat(ex.getMessage()).contains("REPLAY_ONLY").contains("re-record");
			});

		assertThat(this.modelInvocations).hasValue(0);
	}

	@Test
	@DisplayName("REPLAY_ONLY: a hit replays normally")
	void replayOnlyServesHits() {
		float[] vector = { 0.1f, 0.2f, 0.3f };
		EmbeddingModel delegate = delegateReturning(vector);
		vcrModel(delegate, VcrMode.RECORD_OR_REPLAY).call(request("hello world"));

		EmbeddingResponse response = vcrModel(delegate, VcrMode.REPLAY_ONLY).call(request("hello world"));

		assertThat(response.getResult().getOutput()).isEqualTo(vector);
		assertThat(this.modelInvocations).hasValue(1);
	}

	@Test
	@DisplayName("RECORD_ALWAYS: ignores an existing fixture and overwrites it")
	void recordAlwaysOverwrites() {
		vcrModel(delegateReturning(new float[] { 0.1f }), VcrMode.RECORD_OR_REPLAY).call(request("hello world"));

		float[] newVector = { 0.9f };
		EmbeddingResponse response = vcrModel(delegateReturning(newVector), VcrMode.RECORD_ALWAYS)
			.call(request("hello world"));

		assertThat(response.getResult().getOutput()).isEqualTo(newVector);
		assertThat(this.cacheDirectory.toFile().listFiles()).hasSize(1);

		EmbeddingResponse replayed = vcrModel(delegateReturning(new float[] { 0.0f }), VcrMode.REPLAY_ONLY)
			.call(request("hello world"));
		assertThat(replayed.getResult().getOutput()).isEqualTo(newVector);
	}

	@Test
	@DisplayName("BYPASS: always delegates and writes nothing")
	void bypassWritesNothing() {
		EmbeddingModel delegate = delegateReturning(new float[] { 0.1f });
		VcrEmbeddingModel vcrModel = vcrModel(delegate, VcrMode.BYPASS);

		vcrModel.call(request("hello world"));
		vcrModel.call(request("hello world"));

		assertThat(this.modelInvocations).hasValue(2);
		assertThat(this.cacheDirectory.toFile().listFiles()).isEmpty();
	}

	@Test
	@DisplayName("embed(Document) is an uncached pass-through to the delegate")
	void embedDocumentPassesThroughUncached() {
		EmbeddingModel delegate = mock(EmbeddingModel.class);
		Document document = new Document("some content");
		given(delegate.embed(document)).willReturn(new float[] { 0.5f, 0.5f });

		VcrEmbeddingModel vcrModel = vcrModel(delegate, VcrMode.RECORD_OR_REPLAY);

		float[] first = vcrModel.embed(document);
		float[] second = vcrModel.embed(document);

		assertThat(first).isEqualTo(new float[] { 0.5f, 0.5f });
		assertThat(second).isEqualTo(new float[] { 0.5f, 0.5f });
		verify(delegate, times(2)).embed(document);
		assertThat(this.cacheDirectory.toFile().listFiles()).as("embed(Document) must never write a fixture")
			.isEmpty();
	}

	@Test
	@DisplayName("a bean that is already a VcrEmbeddingModel is not double-wrapped")
	void beanPostProcessorSkipsAlreadyWrapped() {
		VcrEmbeddingModelBeanPostProcessor postProcessor = new VcrEmbeddingModelBeanPostProcessor(this.keyGenerator,
				this.store, this.mapper, VcrMode.RECORD_OR_REPLAY);
		VcrEmbeddingModel alreadyWrapped = vcrModel(delegateReturning(new float[] { 0.1f }), VcrMode.BYPASS);

		Object result = postProcessor.postProcessAfterInitialization(alreadyWrapped, "embeddingModel");

		assertThat(result).isSameAs(alreadyWrapped);
	}

	@Test
	@DisplayName("a plain EmbeddingModel bean is wrapped by the post-processor")
	void beanPostProcessorWrapsPlainBean() {
		VcrEmbeddingModelBeanPostProcessor postProcessor = new VcrEmbeddingModelBeanPostProcessor(this.keyGenerator,
				this.store, this.mapper, VcrMode.RECORD_OR_REPLAY);
		EmbeddingModel plain = mock(EmbeddingModel.class);

		Object result = postProcessor.postProcessAfterInitialization(plain, "embeddingModel");

		assertThat(result).isInstanceOf(VcrEmbeddingModel.class).isNotSameAs(plain);
	}

	@Test
	@DisplayName("a non-EmbeddingModel bean passes through the post-processor untouched")
	void beanPostProcessorIgnoresUnrelatedBeans() {
		VcrEmbeddingModelBeanPostProcessor postProcessor = new VcrEmbeddingModelBeanPostProcessor(this.keyGenerator,
				this.store, this.mapper, VcrMode.RECORD_OR_REPLAY);
		Object unrelated = "not an embedding model";

		Object result = postProcessor.postProcessAfterInitialization(unrelated, "someOtherBean");

		assertThat(result).isSameAs(unrelated);
	}

}
