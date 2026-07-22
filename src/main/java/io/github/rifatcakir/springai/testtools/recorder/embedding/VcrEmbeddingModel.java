package io.github.rifatcakir.springai.testtools.recorder.embedding;

import java.util.Optional;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.util.Assert;

/**
 * Intercepts {@code EmbeddingModel.call(EmbeddingRequest)} and serves it from a local
 * JSON fixture when one exists — the embedding counterpart of {@code
 * io.github.rifatcakir.springai.testtools.recorder.advisor.DeterministicVcrAdvisor}.
 *
 * <h2>Why a decorator and not an advisor</h2>
 *
 * <p>Unlike {@code ChatClient}, {@code EmbeddingModel} has no advisor chain at all — it
 * is a bare {@code Model<EmbeddingRequest, EmbeddingResponse>} with one {@code call()}
 * method, and Spring AI 2.0 has no {@code EmbeddingModelBuilderCustomizer} equivalent to
 * {@code ChatClientBuilderCustomizer} (confirmed: no such type exists). So the only
 * provider-agnostic interception point is the {@code EmbeddingModel} bean itself — this
 * class wraps a delegate and is installed by a {@code BeanPostProcessor}
 * (see the autoconfigure package), not attached to a builder.
 *
 * <h2>Scope: {@code call(EmbeddingRequest)} only</h2>
 *
 * <p>{@code embed(String)}, {@code embed(List<String>)} and {@code embedForResponse
 * (List<String>)} are all default methods on {@code EmbeddingModel} that route through
 * {@code call(EmbeddingRequest)}, so overriding just that one method caches all of them
 * for free. {@code embed(Document)} is separately abstract — not a template method —
 * and is deliberately left as a pure pass-through to the delegate, uncached: it is a
 * RAG/vector-store ingestion concern with its own id/metadata shape, out of scope for
 * what this class exists for (a text-in, vector-out call a test can assert on
 * deterministically).
 *
 * <h2>Fixture schema</h2>
 *
 * <p>Uses {@link VcrEmbeddingTrack}, a type independent of {@code VcrTrack} — see that
 * class's Javadoc and {@code docs/R4-EMBEDDING-INTERCEPTION.md} for why, including the
 * explicitly accepted tradeoff that an embedding fixture's vector is not meaningfully
 * human-reviewable the way every other fixture type in this project is.
 *
 * @author Rifat Cakir
 */
public class VcrEmbeddingModel implements EmbeddingModel {

	private static final Logger logger = LoggerFactory.getLogger(VcrEmbeddingModel.class);

	private final EmbeddingModel delegate;

	private final VcrEmbeddingCacheKeyGenerator keyGenerator;

	private final VcrEmbeddingTrackStore store;

	private final VcrEmbeddingTrackMapper mapper;

	private final VcrMode mode;

	public VcrEmbeddingModel(EmbeddingModel delegate, VcrEmbeddingCacheKeyGenerator keyGenerator,
			VcrEmbeddingTrackStore store, VcrEmbeddingTrackMapper mapper, VcrMode mode) {
		Assert.notNull(delegate, "delegate must not be null");
		Assert.notNull(keyGenerator, "keyGenerator must not be null");
		Assert.notNull(store, "store must not be null");
		Assert.notNull(mapper, "mapper must not be null");
		Assert.notNull(mode, "mode must not be null");
		this.delegate = delegate;
		this.keyGenerator = keyGenerator;
		this.store = store;
		this.mapper = mapper;
		this.mode = mode;
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		Assert.notNull(request, "request must not be null");

		if (this.mode == VcrMode.BYPASS) {
			logger.debug("VCR EMBEDDING BYPASS — delegating to the real model");
			return this.delegate.call(request);
		}

		VcrEmbeddingCacheKey key = this.keyGenerator.generate(request);
		String shortHash = VcrEmbeddingTrackStore.shortHash(key.hash());

		if (this.mode == VcrMode.RECORD_ALWAYS) {
			logger.info("VCR EMBEDDING RE-RECORD [{}] — mode is RECORD_ALWAYS, ignoring any existing fixture",
					shortHash);
			return recordAndReturn(request, key);
		}

		Optional<VcrEmbeddingTrack> existing = this.store.read(key.hash());

		if (existing.isPresent()) {
			logger.info("VCR EMBEDDING CACHE HIT  [{}] replaying {}", shortHash,
					this.store.pathFor(key.hash()).getFileName());
			return this.mapper.toEmbeddingResponse(existing.get());
		}

		if (this.mode == VcrMode.REPLAY_ONLY) {
			logger.error("VCR EMBEDDING CACHE MISS [{}] in REPLAY_ONLY — refusing to call the real model", shortHash);
			throw new VcrCacheMissException(key.hash(), this.store.pathFor(key.hash()), key.canonicalRequest());
		}

		logger.info("VCR EMBEDDING CACHE MISS [{}] invoking the real model and recording", shortHash);
		return recordAndReturn(request, key);
	}

	private EmbeddingResponse recordAndReturn(EmbeddingRequest request, VcrEmbeddingCacheKey key) {
		EmbeddingResponse response = this.delegate.call(request);
		this.store.write(this.mapper.toTrack(key, request, response));
		return response;
	}

	/**
	 * Uncached pass-through — see this class's Javadoc for why {@code embed(Document)}
	 * is out of scope for record/replay.
	 */
	@Override
	public float[] embed(Document document) {
		return this.delegate.embed(document);
	}

}
