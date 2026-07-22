package io.github.rifatcakir.springai.testtools.recorder.embedding;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.util.Assert;

/**
 * Translates between Spring AI's embedding domain and the serialisable
 * {@link VcrEmbeddingTrack} — the embedding counterpart of {@code
 * io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper}.
 *
 * <p>Unlike the chat mapper, {@code EmbeddingResponse}/{@code Embedding}/{@code
 * EmbeddingResponseMetadata} all have ordinary public constructors — there is no
 * protected-constructor workaround needed here the way {@code AssistantMessage} forced
 * on the chat side.
 *
 * @author Rifat Cakir
 */
public final class VcrEmbeddingTrackMapper {

	// ---------------------------------------------------------------------
	// Record
	// ---------------------------------------------------------------------

	/**
	 * Capture a live exchange as a fixture.
	 * @param key the computed cache key
	 * @param request the request that was sent
	 * @param response the response that came back
	 * @return a fully serialisable fixture
	 */
	public VcrEmbeddingTrack toTrack(VcrEmbeddingCacheKey key, EmbeddingRequest request, EmbeddingResponse response) {
		Assert.notNull(key, "key must not be null");
		Assert.notNull(request, "request must not be null");
		Assert.notNull(response, "response must not be null");

		return new VcrEmbeddingTrack(VcrEmbeddingTrack.CURRENT_SCHEMA_VERSION, key.hash(), Instant.now().toString(),
				key.canonicalRequest(), toRequestSnapshot(request), toResponseSnapshot(response));
	}

	private VcrEmbeddingTrack.RequestSnapshot toRequestSnapshot(EmbeddingRequest request) {
		EmbeddingOptions options = request.getOptions();
		List<String> inputs = (request.getInstructions() == null) ? List.of() : List.copyOf(request.getInstructions());
		return new VcrEmbeddingTrack.RequestSnapshot(options == null ? null : options.getModel(),
				options == null ? null : options.getDimensions(), inputs);
	}

	private VcrEmbeddingTrack.ResponseSnapshot toResponseSnapshot(EmbeddingResponse response) {
		EmbeddingResponseMetadata metadata = response.getMetadata();

		List<VcrEmbeddingTrack.EmbeddingSnapshot> embeddings = new ArrayList<>();
		if (response.getResults() != null) {
			for (Embedding embedding : response.getResults()) {
				embeddings.add(new VcrEmbeddingTrack.EmbeddingSnapshot(embedding.getIndex(), embedding.getOutput()));
			}
		}

		VcrEmbeddingTrack.UsageSnapshot usage = null;
		if (metadata != null && metadata.getUsage() != null) {
			Usage source = metadata.getUsage();
			usage = new VcrEmbeddingTrack.UsageSnapshot(source.getPromptTokens(), source.getCompletionTokens(),
					source.getTotalTokens());
		}

		return new VcrEmbeddingTrack.ResponseSnapshot(metadata == null ? null : metadata.getModel(), embeddings, usage);
	}

	// ---------------------------------------------------------------------
	// Replay
	// ---------------------------------------------------------------------

	/**
	 * Rebuild an {@link EmbeddingResponse} from a fixture.
	 * @param track the fixture read from disk
	 * @return a response indistinguishable from a live one for assertion purposes
	 */
	public EmbeddingResponse toEmbeddingResponse(VcrEmbeddingTrack track) {
		Assert.notNull(track, "track must not be null");
		Assert.notNull(track.response(), "track.response must not be null");

		VcrEmbeddingTrack.ResponseSnapshot snapshot = track.response();

		List<Embedding> embeddings = new ArrayList<>();
		for (VcrEmbeddingTrack.EmbeddingSnapshot embedding : nullSafe(snapshot.embeddings())) {
			embeddings.add(new Embedding(embedding.vector(), embedding.index()));
		}

		Usage usage = (snapshot.usage() == null) ? null
				: new DefaultUsage(snapshot.usage().promptTokens(), snapshot.usage().completionTokens(),
						snapshot.usage().totalTokens());

		// Marks the response as replayed, mirroring VcrTrackMapper#toChatResponse, so a
		// test or a custom consumer downstream can tell a fixture from a live call
		// without inspecting timing.
		EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata(snapshot.model(), usage,
				Map.of("vcr.replayed", Boolean.TRUE, "vcr.hash", track.hash(), "vcr.recordedAt", track.recordedAt()));

		return new EmbeddingResponse(embeddings, metadata);
	}

	private static <T> List<T> nullSafe(List<T> list) {
		return (list == null) ? List.of() : list;
	}

}
