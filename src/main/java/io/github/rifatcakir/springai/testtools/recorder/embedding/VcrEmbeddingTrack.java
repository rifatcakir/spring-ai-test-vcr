package io.github.rifatcakir.springai.testtools.recorder.embedding;

import java.util.List;

/**
 * The on-disk fixture format for a cached embedding call — the embedding counterpart of
 * {@code io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack}.
 *
 * <p>A separate type rather than an extension of {@code VcrTrack}: an embedding request
 * (a batch of input strings plus options) and its response (one float vector per input)
 * share no structural overlap with a chat {@code Prompt}/{@code ChatResponse} (messages,
 * tool calls, structured output, finish reasons) — forcing either shape onto the other
 * would leave a fixture full of fields that never apply to it. {@link #CURRENT_SCHEMA_VERSION}
 * is this type's own, independent version counter, tracking this fixture family's own
 * evolution rather than {@code VcrTrack}'s.
 *
 * <p><strong>This is the one fixture type in this project that is not meaningfully
 * human-reviewable the way every other one is</strong> (see {@code
 * docs/R4-EMBEDDING-INTERCEPTION.md} section 3): a vector is hundreds to thousands of
 * floating-point values, and no reviewer parses a diff of them the way they'd read a
 * changed prompt or tool schema. Storing the full vector, not a hash of it, is not
 * optional despite that: a hash is one-way and could never actually replay a usable
 * vector, which is the entire reason this fixture type exists (it is the prerequisite
 * for A2's semantic/embedding assertions). The vector is rendered with this project's
 * ordinary pretty-printer — which, for a {@code float[]} specifically, Jackson 3 writes
 * as a single compact line rather than one element per line the way it would for a
 * {@code List}, confirmed against a real recorded fixture (22 lines total, not
 * thousands) rather than assumed from Jackson's general array-indentation behavior. A
 * deliberate, explicitly accepted departure from the "readable diff" framing design rule
 * #5 gives every other fixture type either way, not an oversight.
 *
 * @param schemaVersion format version for this fixture family, independent of {@code
 * VcrTrack.CURRENT_SCHEMA_VERSION}
 * @param hash the SHA-256 key this fixture is filed under
 * @param recordedAt ISO-8601 instant the recording was made, for human triage only
 * @param canonicalRequest the exact normalized string the hash was computed over
 * @param request a human-readable snapshot of what was sent
 * @param response the replayable payload
 * @author Rifat Cakir
 */
public record VcrEmbeddingTrack(String schemaVersion, String hash, String recordedAt, String canonicalRequest,
		RequestSnapshot request, ResponseSnapshot response) {

	/**
	 * {@code "1"}: the initial format. Independent of, and not comparable to,
	 * {@code VcrTrack.CURRENT_SCHEMA_VERSION} — the two fixture families evolve
	 * separately.
	 */
	public static final String CURRENT_SCHEMA_VERSION = "1";

	/**
	 * What went to the embedding model. Recorded for reviewability and never used during
	 * replay, which keys purely off the filename hash.
	 * @param model the model name requested, or {@code null} if the request carried no
	 * options
	 * @param dimensions the requested output dimensionality, or {@code null} if unset
	 * @param inputs every input text, in the original batch order — order is semantic,
	 * since each input's embedding comes back at the same index in {@code response}
	 */
	public record RequestSnapshot(String model, Integer dimensions, List<String> inputs) {
	}

	/**
	 * What came back, reduced to the parts that survive a round trip.
	 * @param model the model that actually answered, or {@code null} if the response
	 * carried no metadata
	 * @param embeddings one vector per input, in the same order as {@code
	 * RequestSnapshot.inputs}
	 * @param usage token accounting, or {@code null} if the response carried none
	 */
	public record ResponseSnapshot(String model, List<EmbeddingSnapshot> embeddings, UsageSnapshot usage) {
	}

	/**
	 * One input's embedding.
	 * @param index the position this embedding was returned at, mirroring {@code
	 * Embedding#getIndex()}
	 * @param vector the embedding vector, exactly as the provider returned it — not
	 * normalized, truncated or otherwise altered
	 */
	public record EmbeddingSnapshot(Integer index, float[] vector) {
	}

	/**
	 * Token accounting for one response. Mirrors {@code VcrTrack.UsageSnapshot}; never
	 * contains a provider's raw, non-portable usage object.
	 * @param promptTokens tokens consumed by the request, or {@code null} if the provider
	 * didn't report it
	 * @param completionTokens tokens generated in the response, or {@code null} if the
	 * provider didn't report it (embedding calls have no completion, so this is
	 * typically {@code null} or zero)
	 * @param totalTokens the provider-reported total, or {@code null} if not reported
	 */
	public record UsageSnapshot(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
	}

}
