package io.github.rifatcakir.springai.testtools.recorder.stream;

import java.util.List;

import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;

/**
 * The on-disk fixture format for a cached {@code ChatClient...stream(...)} call (R3) —
 * the streaming counterpart of {@link VcrTrack}.
 *
 * <p>A separate top-level type, not a field added to {@link VcrTrack}: a stream is an
 * ordered <em>sequence</em> of response chunks, structurally different from {@code
 * VcrTrack}'s single request/single response shape — forcing either onto the other
 * would leave one of them full of fields that never apply to it, the same reasoning
 * that already justified {@code VcrEmbeddingTrack} as its own type (R4). {@link
 * #CURRENT_SCHEMA_VERSION} is this type's own independent version counter; bumping it
 * never touches {@code VcrTrack.CURRENT_SCHEMA_VERSION} or any existing fixture.
 *
 * <p><strong>The request side is not duplicated.</strong> A streaming call's request —
 * the same {@code Prompt}, the same {@code ChatClientRequest.context()} — is shaped
 * identically to a blocking call's, so {@link #request} reuses {@link
 * VcrTrack.RequestSnapshot} directly rather than a second, parallel copy of the same
 * messages/tools/structured-output shape. Only the response side is genuinely new.
 *
 * <p><strong>The response side stores both the raw chunk sequence and a computed,
 * review-only aggregate</strong> — a deliberate decision, not the simplest option
 * available: {@link ResponseSnapshot#chunks()} is the actual replay source of truth
 * (required for chunk-level fidelity — a consumer's own aggregation logic, or an
 * assertion on chunk boundaries specifically, must see the same chunks live or
 * replayed), while {@link ResponseSnapshot#aggregateText()} (and the other {@code
 * aggregate*} fields) exist purely so a fixture reviewer sees the final answer at a
 * glance in a PR diff, without having to mentally concatenate N small chunk fragments —
 * the same "recorded for reviewability, never read back for replay" role {@code
 * VcrTrack.canonicalRequest} already plays.
 *
 * @param schemaVersion format version for this fixture family, independent of {@code
 * VcrTrack.CURRENT_SCHEMA_VERSION}
 * @param hash the SHA-256 key this fixture is filed under — computed by {@code
 * VcrCacheKeyGenerator#generateForStream}, which differs from the call-path hash only in
 * its canonical-form header, specifically so a stream and a call sharing the exact same
 * prompt can never collide on one fixture file
 * @param recordedAt ISO-8601 instant the recording was made, for human triage only
 * @param canonicalRequest the exact normalized string the hash was computed over
 * @param request a human-readable snapshot of what was sent — identical in shape to a
 * blocking call's own request snapshot
 * @param response the replayable chunk sequence, plus a review-only aggregate
 * @author Rifat Cakir
 */
public record VcrStreamTrack(String schemaVersion, String hash, String recordedAt, String canonicalRequest,
		VcrTrack.RequestSnapshot request, ResponseSnapshot response) {

	/**
	 * {@code "1"}: the initial format. Independent of, and not comparable to, {@code
	 * VcrTrack.CURRENT_SCHEMA_VERSION} — the two fixture families evolve separately.
	 */
	public static final String CURRENT_SCHEMA_VERSION = "1";

	/**
	 * One emission on the live {@code Flux<ChatResponse>} — everything needed to
	 * reconstruct that exact chunk on replay, no more and no less. Confirmed via
	 * bytecode (R3's own diagnosis against real {@code llama3.2:1b}) that a genuine tool
	 * call arrives whole in a single chunk for this provider, never fragmented across
	 * chunks — so {@code toolCalls} here needs no special partial/fragment handling: a
	 * chunk either carries zero or more complete tool calls, exactly like a blocking
	 * call's single response does.
	 * @param id the chunk's own response id, or {@code null} if this chunk carried none
	 * (most chunks don't; typically only the final one does)
	 * @param model the model name on this chunk's metadata, or {@code null} if absent
	 * @param text this chunk's text content — typically a small delta (a few tokens),
	 * never the full accumulated answer; empty, not {@code null}, when a chunk carries
	 * no text (e.g. a tool-call-only chunk)
	 * @param finishReason this chunk's finish reason, or {@code null} if this chunk
	 * didn't carry one (typically only the final chunk does)
	 * @param toolCalls tool calls carried on this specific chunk, empty if none
	 * @param usage token accounting on this chunk's metadata, or {@code null} if absent
	 * (typically only the final chunk carries usage)
	 */
	public record ChunkSnapshot(String id, String model, String text, String finishReason,
			List<VcrTrack.ToolCallSnapshot> toolCalls, VcrTrack.UsageSnapshot usage) {
	}

	/**
	 * @param chunks every chunk emitted by the live stream, in original order — the
	 * actual replay source of truth; never re-derived from the aggregate fields below
	 * @param aggregateText every chunk's text concatenated in order, computed once at
	 * record time — for human review only, never read back for replay
	 * @param aggregateFinishReason the last non-null finish reason across all chunks,
	 * mirroring {@code org.springframework.ai.chat.model.MessageAggregator}'s own
	 * "last one wins" behavior — for human review only
	 * @param aggregateToolCalls every chunk's tool calls concatenated in order,
	 * mirroring {@code MessageAggregator}'s own plain {@code List.addAll} merge — for
	 * human review only
	 */
	public record ResponseSnapshot(List<ChunkSnapshot> chunks, String aggregateText, String aggregateFinishReason,
			List<VcrTrack.ToolCallSnapshot> aggregateToolCalls) {
	}

}
