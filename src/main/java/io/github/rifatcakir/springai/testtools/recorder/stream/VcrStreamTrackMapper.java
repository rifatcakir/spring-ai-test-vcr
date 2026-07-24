package io.github.rifatcakir.springai.testtools.recorder.stream;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.rifatcakir.springai.testtools.recorder.VcrPromptNormalizer;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKey;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.Assert;

/**
 * Translates between a live {@code Flux<ChatResponse>}'s collected chunks and the
 * serialisable {@link VcrStreamTrack} — the streaming counterpart of {@code
 * io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper}.
 *
 * <p>Delegates request-side snapshot building to an internal {@link VcrTrackMapper}
 * instance ({@link VcrTrackMapper#toRequestSnapshot(Prompt, Map)}) rather than
 * duplicating it: a streaming call's request is shaped identically to a blocking call's,
 * so there is nothing stream-specific to redo there — see {@link VcrStreamTrack}'s
 * Javadoc.
 *
 * @author Rifat Cakir
 */
public final class VcrStreamTrackMapper {

	private final VcrTrackMapper trackMapper;

	public VcrStreamTrackMapper() {
		this(List.of());
	}

	public VcrStreamTrackMapper(List<VcrPromptNormalizer> normalizers) {
		Assert.notNull(normalizers, "normalizers must not be null");
		this.trackMapper = new VcrTrackMapper(normalizers);
	}

	// ---------------------------------------------------------------------
	// Record
	// ---------------------------------------------------------------------

	/**
	 * Capture a live stream's collected chunks as a fixture.
	 * @param key the computed cache key ({@code VcrCacheKeyGenerator#generateForStream})
	 * @param prompt the request that was sent
	 * @param context {@code ChatClientRequest.context()} at the point this was recorded
	 * @param chunks every chunk the live stream emitted, in order
	 * @return a fully serialisable fixture
	 */
	public VcrStreamTrack toTrack(VcrCacheKey key, Prompt prompt, Map<String, Object> context,
			List<ChatResponse> chunks) {
		Assert.notNull(key, "key must not be null");
		Assert.notNull(prompt, "prompt must not be null");
		Assert.notNull(context, "context must not be null");
		Assert.notNull(chunks, "chunks must not be null");

		return new VcrStreamTrack(VcrStreamTrack.CURRENT_SCHEMA_VERSION, key.hash(), Instant.now().toString(),
				key.canonicalRequest(), this.trackMapper.toRequestSnapshot(prompt, context), toResponseSnapshot(chunks));
	}

	private VcrStreamTrack.ResponseSnapshot toResponseSnapshot(List<ChatResponse> chunks) {
		List<VcrStreamTrack.ChunkSnapshot> chunkSnapshots = new ArrayList<>();
		StringBuilder aggregateText = new StringBuilder();
		String aggregateFinishReason = null;
		List<VcrTrack.ToolCallSnapshot> aggregateToolCalls = new ArrayList<>();

		for (ChatResponse chunk : chunks) {
			Generation generation = chunk.getResult();
			AssistantMessage output = (generation == null) ? null : generation.getOutput();
			String text = (output == null) ? null : output.getText();
			String finishReason = (generation == null || generation.getMetadata() == null) ? null
					: generation.getMetadata().getFinishReason();
			List<VcrTrack.ToolCallSnapshot> toolCalls = toToolCallSnapshots(output);

			ChatResponseMetadata metadata = chunk.getMetadata();
			String id = (metadata == null) ? null : metadata.getId();
			String model = (metadata == null) ? null : metadata.getModel();
			VcrTrack.UsageSnapshot usage = null;
			if (metadata != null && metadata.getUsage() != null) {
				Usage source = metadata.getUsage();
				usage = new VcrTrack.UsageSnapshot(source.getPromptTokens(), source.getCompletionTokens(),
						source.getTotalTokens());
			}

			chunkSnapshots.add(new VcrStreamTrack.ChunkSnapshot(id, model, text, finishReason, toolCalls, usage));

			// Mirrors org.springframework.ai.chat.model.MessageAggregator's own
			// behavior (confirmed via bytecode, docs/R3-STREAMING-PRD.md section 1):
			// text concatenated in order, finish reason "last non-null wins", tool
			// calls concatenated via plain addAll -- for review only, never replayed.
			if (text != null) {
				aggregateText.append(text);
			}
			if (finishReason != null) {
				aggregateFinishReason = finishReason;
			}
			aggregateToolCalls.addAll(toolCalls);
		}

		return new VcrStreamTrack.ResponseSnapshot(chunkSnapshots, aggregateText.toString(), aggregateFinishReason,
				aggregateToolCalls);
	}

	private List<VcrTrack.ToolCallSnapshot> toToolCallSnapshots(AssistantMessage output) {
		List<VcrTrack.ToolCallSnapshot> toolCalls = new ArrayList<>();
		if (output != null && output.getToolCalls() != null) {
			for (AssistantMessage.ToolCall call : output.getToolCalls()) {
				toolCalls.add(new VcrTrack.ToolCallSnapshot(call.id(), call.type(), call.name(), call.arguments()));
			}
		}
		return toolCalls;
	}

	// ---------------------------------------------------------------------
	// Replay
	// ---------------------------------------------------------------------

	/**
	 * Rebuild the exact ordered {@link ChatResponse} chunk sequence a fixture recorded —
	 * chunk-for-chunk, not the aggregate. Uses only public builders, same reasoning as
	 * {@code VcrTrackMapper#toChatResponse}.
	 * @param track the fixture read from disk
	 * @return the chunks, in original order, indistinguishable from a live stream's for
	 * assertion purposes
	 */
	public List<ChatResponse> toChatResponseChunks(VcrStreamTrack track) {
		Assert.notNull(track, "track must not be null");
		Assert.notNull(track.response(), "track.response must not be null");

		List<ChatResponse> chunks = new ArrayList<>();
		for (VcrStreamTrack.ChunkSnapshot snapshot : track.response().chunks()) {
			chunks.add(toChatResponseChunk(track, snapshot));
		}
		return chunks;
	}

	private ChatResponse toChatResponseChunk(VcrStreamTrack track, VcrStreamTrack.ChunkSnapshot snapshot) {
		List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
		for (VcrTrack.ToolCallSnapshot call : nullSafe(snapshot.toolCalls())) {
			toolCalls.add(new AssistantMessage.ToolCall(call.id(), call.type(), call.name(), call.arguments()));
		}

		AssistantMessage message = AssistantMessage.builder()
			.content(snapshot.text() == null ? "" : snapshot.text())
			.properties(Map.of())
			.toolCalls(toolCalls)
			.media(List.of())
			.build();

		ChatGenerationMetadata generationMetadata = (snapshot.finishReason() == null) ? ChatGenerationMetadata.NULL
				: ChatGenerationMetadata.builder().finishReason(snapshot.finishReason()).build();

		Generation generation = new Generation(message, generationMetadata);

		ChatResponseMetadata.Builder metadataBuilder = ChatResponseMetadata.builder();
		if (snapshot.id() != null) {
			metadataBuilder.id(snapshot.id());
		}
		if (snapshot.model() != null) {
			metadataBuilder.model(snapshot.model());
		}
		if (snapshot.usage() != null) {
			metadataBuilder.usage(toUsage(snapshot.usage()));
		}
		// Marks each replayed chunk, mirroring VcrTrackMapper#toChatResponse, so a test
		// or a custom advisor downstream can tell a fixture from a live call without
		// inspecting timing.
		metadataBuilder.keyValue("vcr.replayed", Boolean.TRUE);
		metadataBuilder.keyValue("vcr.hash", track.hash());
		metadataBuilder.keyValue("vcr.recordedAt", track.recordedAt());

		return ChatResponse.builder().generations(List.of(generation)).metadata(metadataBuilder.build()).build();
	}

	private Usage toUsage(VcrTrack.UsageSnapshot snapshot) {
		return new DefaultUsage(snapshot.promptTokens(), snapshot.completionTokens(), snapshot.totalTokens());
	}

	private static <T> List<T> nullSafe(List<T> list) {
		return (list == null) ? List.of() : list;
	}

}
