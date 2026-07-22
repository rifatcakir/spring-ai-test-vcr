package io.github.rifatcakir.springai.testtools.recorder.embedding;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the embedding key generator's stability and sensitivity, the same discipline
 * {@code VcrCacheKeyGeneratorTests} applies to the chat side: if it is unstable the
 * cache never hits, and if it is insensitive it serves a vector for an input nobody
 * reviewed.
 *
 * @author Rifat Cakir
 */
class VcrEmbeddingCacheKeyGeneratorTests {

	private final VcrEmbeddingCacheKeyGenerator generator = new VcrEmbeddingCacheKeyGenerator();

	private static EmbeddingRequest request(EmbeddingOptions options, String... inputs) {
		return new EmbeddingRequest(List.of(inputs), options);
	}

	private static EmbeddingOptions options(String model, Integer dimensions) {
		return EmbeddingOptions.builder().model(model).dimensions(dimensions).build();
	}

	@Test
	@DisplayName("produces a 64-character lowercase hex digest")
	void producesWellFormedDigest() {
		VcrEmbeddingCacheKey key = this.generator.generate(request(options("nomic-embed-text", null), "hello world"));

		assertThat(key.hash()).hasSize(64).matches("[0-9a-f]{64}");
		assertThat(key.canonicalRequest()).contains("model=nomic-embed-text").contains("hello world");
	}

	@Test
	@DisplayName("is stable across repeated invocations")
	void isStable() {
		EmbeddingRequest request = request(options("nomic-embed-text", null), "hello world");

		assertThat(this.generator.generate(request).hash()).isEqualTo(this.generator.generate(request).hash());
	}

	@Test
	@DisplayName("a single character of input drift busts the cache")
	void singleCharacterBustsTheCache() {
		String a = this.generator.generate(request(options("m", null), "Summarise the backlog.")).hash();
		String b = this.generator.generate(request(options("m", null), "Summarise the backlog!")).hash();

		assertThat(a).isNotEqualTo(b);
	}

	@Test
	@DisplayName("model participates in the hash")
	void modelParticipates() {
		String a = this.generator.generate(request(options("model-a", null), "hello")).hash();
		String b = this.generator.generate(request(options("model-b", null), "hello")).hash();

		assertThat(a).isNotEqualTo(b);
	}

	@Test
	@DisplayName("dimensions participates in the hash")
	void dimensionsParticipates() {
		String a = this.generator.generate(request(options("m", 256), "hello")).hash();
		String b = this.generator.generate(request(options("m", 512), "hello")).hash();

		assertThat(a).isNotEqualTo(b);
	}

	@Test
	@DisplayName("input order participates in the hash -- each input's embedding comes back at its own index")
	void inputOrderParticipates() {
		String forward = this.generator.generate(request(options("m", null), "alpha", "beta")).hash();
		String reversed = this.generator.generate(request(options("m", null), "beta", "alpha")).hash();

		assertThat(forward).isNotEqualTo(reversed);
	}

	@Test
	@DisplayName("batch size participates in the hash -- one input and two inputs never collide")
	void batchSizeParticipates() {
		String single = this.generator.generate(request(options("m", null), "alpha")).hash();
		String batch = this.generator.generate(request(options("m", null), "alpha", "beta")).hash();

		assertThat(single).isNotEqualTo(batch);
	}

	@Test
	@DisplayName("an embedded newline cannot forge an extra canonical field")
	void embeddedNewlineCannotForgeAField() {
		String innocuous = this.generator.generate(request(options("real-model", null), "line one\nmodel=evil")).hash();
		String genuinelyDifferentModel = this.generator.generate(request(options("evil", null), "line one")).hash();

		assertThat(innocuous).isNotEqualTo(genuinelyDifferentModel);
	}

	@Test
	@DisplayName("null options do not throw and still produce a stable hash")
	void nullOptionsAreHandled() {
		EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);

		VcrEmbeddingCacheKey key = this.generator.generate(request);

		assertThat(key.hash()).hasSize(64);
		assertThat(this.generator.generate(request).hash()).isEqualTo(key.hash());
	}

	@Test
	@DisplayName("an embedding hash never collides with a chat hash's canonical-form header")
	void hasItsOwnCanonicalFormHeader() {
		VcrEmbeddingCacheKey key = this.generator.generate(request(options("m", null), "hello"));

		assertThat(key.canonicalRequest()).startsWith("vcr-embedding-canonical-form/v1");
	}

}
