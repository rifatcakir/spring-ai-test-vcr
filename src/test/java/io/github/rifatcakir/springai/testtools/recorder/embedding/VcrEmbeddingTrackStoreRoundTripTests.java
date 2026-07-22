package io.github.rifatcakir.springai.testtools.recorder.embedding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the embedding fixture round trip actually preserves a vector exactly — the
 * embedding counterpart of {@code VcrTrackStoreRoundTripTests}. The one property that
 * matters more than anything else here: a replayed vector must be bit-for-bit what was
 * recorded, not merely "the same length" or "close enough" -- an assertion built on top
 * of this (A2's future cosine-similarity check) would silently compute against the
 * wrong numbers otherwise.
 *
 * @author Rifat Cakir
 */
class VcrEmbeddingTrackStoreRoundTripTests {

	@TempDir
	Path cacheDirectory;

	private final VcrEmbeddingTrackMapper mapper = new VcrEmbeddingTrackMapper();

	private final VcrEmbeddingCacheKeyGenerator keyGenerator = new VcrEmbeddingCacheKeyGenerator();

	private VcrEmbeddingCacheKey key(String... inputs) {
		return this.keyGenerator
			.generate(new EmbeddingRequest(List.of(inputs), EmbeddingOptions.builder().model("nomic-embed-text").build()));
	}

	@Test
	@DisplayName("a single embedding round-trips with its exact vector values")
	void singleEmbeddingRoundTrips() {
		VcrEmbeddingTrackStore store = new VcrEmbeddingTrackStore(this.cacheDirectory);
		float[] vector = { 0.013228971f, -0.043682855f, 0.028705109f, 0.0f, -1.0f, 1.0f, 3.14159265f };
		EmbeddingResponse original = new EmbeddingResponse(List.of(new Embedding(vector, 0)),
				new EmbeddingResponseMetadata("nomic-embed-text", new DefaultUsage(3, null, 3)));

		VcrEmbeddingCacheKey key = key("hello world");
		store.write(this.mapper.toTrack(key, new EmbeddingRequest(List.of("hello world"), EmbeddingOptions.builder()
			.model("nomic-embed-text")
			.build()), original));

		Optional<VcrEmbeddingTrack> reloaded = store.read(key.hash());
		assertThat(reloaded).isPresent();

		EmbeddingResponse replayed = this.mapper.toEmbeddingResponse(reloaded.get());
		assertThat(replayed.getResult().getOutput()).as("bit-for-bit exact vector, not just same length")
			.isEqualTo(vector);
		assertThat(replayed.getMetadata().getModel()).isEqualTo("nomic-embed-text");
		assertThat(replayed.getMetadata().getUsage().getPromptTokens()).isEqualTo(3);
	}

	@Test
	@DisplayName("a batch of embeddings round-trips each vector at its own index, in order")
	void batchRoundTripsInOrder() {
		VcrEmbeddingTrackStore store = new VcrEmbeddingTrackStore(this.cacheDirectory);
		float[] first = { 0.1f, 0.2f, 0.3f };
		float[] second = { -0.9f, -0.8f, -0.7f };
		EmbeddingResponse original = new EmbeddingResponse(
				List.of(new Embedding(first, 0), new Embedding(second, 1)),
				new EmbeddingResponseMetadata("nomic-embed-text", null));

		VcrEmbeddingCacheKey key = key("alpha", "beta");
		EmbeddingRequest request = new EmbeddingRequest(List.of("alpha", "beta"),
				EmbeddingOptions.builder().model("nomic-embed-text").build());
		store.write(this.mapper.toTrack(key, request, original));

		EmbeddingResponse replayed = this.mapper.toEmbeddingResponse(store.read(key.hash()).orElseThrow());

		assertThat(replayed.getResults()).hasSize(2);
		assertThat(replayed.getResults().get(0).getIndex()).isZero();
		assertThat(replayed.getResults().get(0).getOutput()).isEqualTo(first);
		assertThat(replayed.getResults().get(1).getIndex()).isEqualTo(1);
		assertThat(replayed.getResults().get(1).getOutput()).isEqualTo(second);
	}

	@Test
	@DisplayName("the stored fixture records the request's inputs, model and dimensions for human review")
	void fixtureCapturesRequestForReview() {
		VcrEmbeddingTrackStore store = new VcrEmbeddingTrackStore(this.cacheDirectory);
		EmbeddingResponse original = new EmbeddingResponse(List.of(new Embedding(new float[] { 0.1f }, 0)),
				new EmbeddingResponseMetadata("nomic-embed-text", null));

		VcrEmbeddingCacheKey key = key("hello world");
		EmbeddingRequest request = new EmbeddingRequest(List.of("hello world"),
				EmbeddingOptions.builder().model("nomic-embed-text").dimensions(768).build());
		store.write(this.mapper.toTrack(key, request, original));

		VcrEmbeddingTrack track = store.read(key.hash()).orElseThrow();
		assertThat(track.schemaVersion()).isEqualTo("1");
		assertThat(track.request().model()).isEqualTo("nomic-embed-text");
		assertThat(track.request().dimensions()).isEqualTo(768);
		assertThat(track.request().inputs()).containsExactly("hello world");
	}

	@Test
	@DisplayName("a malformed fixture degrades to a cache miss rather than crashing")
	void malformedFixtureDegradesToMiss() throws Exception {
		VcrEmbeddingTrackStore store = new VcrEmbeddingTrackStore(this.cacheDirectory);
		Files.createDirectories(this.cacheDirectory);
		Files.writeString(this.cacheDirectory.resolve("deadbeef".repeat(8) + ".json"), "{ not valid json");

		Optional<VcrEmbeddingTrack> result = store.read("deadbeef".repeat(8));

		assertThat(result).isEmpty();
	}

}
