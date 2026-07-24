package io.github.rifatcakir.springai.testtools.recorder.track;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.rifatcakir.springai.testtools.recorder.VcrPromptNormalizer;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKey;

import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.Assert;

/**
 * Translates between Spring AI's response domain and the serialisable {@link VcrTrack}.
 *
 * <p>Both directions are lossy by design. Recording drops the provider's native usage
 * object and any non-portable metadata; replaying rebuilds only what a test can assert on.
 * If a test needs to assert against something that does not survive this round trip, the
 * honest answer is that it is not an integration test that should be cached — run it
 * against the real model in {@link io.github.rifatcakir.springai.testtools.recorder.VcrMode#BYPASS}.
 *
 * @author Rifat Cakir
 */
public final class VcrTrackMapper {

	private final List<VcrPromptNormalizer> normalizers;

	public VcrTrackMapper() {
		this(List.of());
	}

	public VcrTrackMapper(List<VcrPromptNormalizer> normalizers) {
		Assert.notNull(normalizers, "normalizers must not be null");
		this.normalizers = List.copyOf(normalizers);
	}

	// ---------------------------------------------------------------------
	// Record
	// ---------------------------------------------------------------------

	/**
	 * Capture a live exchange as a fixture with no request-scoped context — equivalent to
	 * {@link #toTrack(VcrCacheKey, Prompt, Map, ChatResponse)} with an empty map.
	 * @param key the computed cache key
	 * @param prompt the request that was sent
	 * @param chatResponse the response that came back
	 * @return a fully serialisable fixture
	 */
	public VcrTrack toTrack(VcrCacheKey key, Prompt prompt, ChatResponse chatResponse) {
		return toTrack(key, prompt, Map.of(), chatResponse);
	}

	/**
	 * Capture a live exchange as a fixture, also recording any structured-output format
	 * instructions/schema found in {@code ChatClientRequest}'s request-scoped context — see
	 * {@code VcrCacheKeyGenerator#generate(Prompt, Map)} for why this needs to be captured
	 * at all.
	 * @param key the computed cache key
	 * @param prompt the request that was sent
	 * @param context {@code ChatClientRequest.context()} at the point this was recorded
	 * @param chatResponse the response that came back
	 * @return a fully serialisable fixture
	 */
	public VcrTrack toTrack(VcrCacheKey key, Prompt prompt, Map<String, Object> context, ChatResponse chatResponse) {
		Assert.notNull(key, "key must not be null");
		Assert.notNull(prompt, "prompt must not be null");
		Assert.notNull(context, "context must not be null");
		Assert.notNull(chatResponse, "chatResponse must not be null");

		return new VcrTrack(VcrTrack.CURRENT_SCHEMA_VERSION, key.hash(), Instant.now().toString(),
				key.canonicalRequest(), toRequestSnapshot(prompt, context), toResponseSnapshot(chatResponse));
	}

	/**
	 * Build the request-side snapshot alone, from a {@link Prompt} and {@code
	 * ChatClientRequest.context()} — public (not just used internally by {@link
	 * #toTrack}) because {@code
	 * io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackMapper}
	 * reuses it directly rather than duplicating this logic: a streaming call's request
	 * side is shaped identically to a blocking call's (same {@link Prompt}, same
	 * context), only the *response* side differs (a chunk sequence, not one response),
	 * so there is nothing stream-specific to redo here.
	 * @param prompt the request that was, or is about to be, sent
	 * @param context {@code ChatClientRequest.context()} at the point this was recorded
	 * @return the request-side snapshot
	 */
	public VcrTrack.RequestSnapshot toRequestSnapshot(Prompt prompt, Map<String, Object> context) {
		ChatOptions options = prompt.getOptions();

		List<VcrTrack.MessageSnapshot> messages = new ArrayList<>();
		if (prompt.getInstructions() != null) {
			for (Message message : prompt.getInstructions()) {
				String type = (message.getMessageType() == null) ? "UNKNOWN" : message.getMessageType().getValue();
				// Normalized, not raw: a committed fixture must not leak the live value
				// that the normalizer was configured to redact.
				messages.add(new VcrTrack.MessageSnapshot(type, normalize(message.getText()),
						toMessageToolCalls(message), toMessageToolResponses(message)));
			}
		}

		return new VcrTrack.RequestSnapshot(options == null ? null : options.getModel(),
				options == null ? null : options.getTemperature(), options == null ? null : options.getTopP(),
				options == null ? null : options.getTopK(), options == null ? null : options.getMaxTokens(),
				(options == null || options.getStopSequences() == null) ? List.of()
						: List.copyOf(options.getStopSequences()),
				messages, toToolSnapshots(options), toStructuredOutputSnapshot(context));
	}

	/**
	 * {@code null} whenever neither {@link ChatClientAttributes#OUTPUT_FORMAT} nor
	 * {@link ChatClientAttributes#STRUCTURED_OUTPUT_SCHEMA} is present — the overwhelming
	 * majority of requests, which never called {@code entity()} at all.
	 */
	private VcrTrack.StructuredOutputSnapshot toStructuredOutputSnapshot(Map<String, Object> context) {
		if (context == null || context.isEmpty()) {
			return null;
		}
		Object format = context.get(ChatClientAttributes.OUTPUT_FORMAT.getKey());
		Object schema = context.get(ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey());
		if (format == null && schema == null) {
			return null;
		}
		return new VcrTrack.StructuredOutputSnapshot(format == null ? null : normalizeLineEndings(format.toString()),
				schema == null ? null : normalizeLineEndings(schema.toString()));
	}

	/**
	 * Collapses {@code CRLF} and lone {@code CR} to {@code LF}, mirroring {@code
	 * VcrCacheKeyGenerator}'s normalization of the same text for hashing (see that
	 * class's Javadoc for the full rationale: a schema is rendered by Jackson's pretty
	 * printer, which embeds the recording JVM's own {@code System.lineSeparator()}).
	 * Applied here too, not just to the hash, so a committed fixture never shows a
	 * schema whose line endings quietly disagree with the one that was actually hashed
	 * — confusing for a reviewer, even though nothing in this library ever reads these
	 * fields back after deserialization. Deliberately not applied to message text or to
	 * response content, for the same reason {@code VcrCacheKeyGenerator} does not: those
	 * are not generated by this library's own toolchain, so a line-ending difference in
	 * them is not provably meaningless the way it is in an auto-rendered schema.
	 */
	private static String normalizeLineEndings(String text) {
		return (text == null) ? null : text.replace("\r\n", "\n").replace("\r", "\n");
	}

	/**
	 * An {@code AssistantMessage} in conversation history that made tool calls has empty
	 * {@code getText()} — the calls themselves live only in {@code getToolCalls()}, which
	 * this captures explicitly rather than silently dropping.
	 */
	private List<VcrTrack.ToolCallSnapshot> toMessageToolCalls(Message message) {
		if (!(message instanceof AssistantMessage assistantMessage) || assistantMessage.getToolCalls() == null) {
			return List.of();
		}
		List<VcrTrack.ToolCallSnapshot> toolCalls = new ArrayList<>();
		for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
			toolCalls.add(new VcrTrack.ToolCallSnapshot(call.id(), call.type(), call.name(), call.arguments()));
		}
		return toolCalls;
	}

	/**
	 * {@code ToolResponseMessage.getText()} is always empty — its actual content is the
	 * list of tool results in {@code getResponses()}, captured here explicitly.
	 */
	private List<VcrTrack.ToolResponseSnapshot> toMessageToolResponses(Message message) {
		if (!(message instanceof ToolResponseMessage toolResponseMessage) || toolResponseMessage.getResponses() == null) {
			return List.of();
		}
		List<VcrTrack.ToolResponseSnapshot> responses = new ArrayList<>();
		for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
			responses.add(new VcrTrack.ToolResponseSnapshot(response.id(), response.name(), response.responseData()));
		}
		return responses;
	}

	private List<VcrTrack.ToolDefinitionSnapshot> toToolSnapshots(ChatOptions options) {
		List<VcrTrack.ToolDefinitionSnapshot> tools = new ArrayList<>();
		if (options instanceof ToolCallingChatOptions toolOptions && toolOptions.getToolCallbacks() != null) {
			for (ToolCallback callback : toolOptions.getToolCallbacks()) {
				ToolDefinition definition = callback.getToolDefinition();
				tools.add(new VcrTrack.ToolDefinitionSnapshot(definition.name(),
						normalizeLineEndings(definition.description()), normalizeLineEndings(definition.inputSchema())));
			}
		}
		return tools;
	}

	private VcrTrack.ResponseSnapshot toResponseSnapshot(ChatResponse chatResponse) {
		ChatResponseMetadata metadata = chatResponse.getMetadata();

		List<VcrTrack.GenerationSnapshot> generations = new ArrayList<>();
		if (chatResponse.getResults() != null) {
			for (Generation generation : chatResponse.getResults()) {
				generations.add(toGenerationSnapshot(generation));
			}
		}

		VcrTrack.UsageSnapshot usage = null;
		if (metadata != null && metadata.getUsage() != null) {
			Usage source = metadata.getUsage();
			// Deliberately excludes getNativeUsage(): it holds the raw provider SDK
			// object, which is neither portable nor safely serialisable.
			usage = new VcrTrack.UsageSnapshot(source.getPromptTokens(), source.getCompletionTokens(),
					source.getTotalTokens());
		}

		return new VcrTrack.ResponseSnapshot(metadata == null ? null : metadata.getId(),
				metadata == null ? null : metadata.getModel(), generations, usage, Map.of());
	}

	private VcrTrack.GenerationSnapshot toGenerationSnapshot(Generation generation) {
		AssistantMessage output = generation.getOutput();

		List<VcrTrack.ToolCallSnapshot> toolCalls = new ArrayList<>();
		if (output != null && output.getToolCalls() != null) {
			for (AssistantMessage.ToolCall call : output.getToolCalls()) {
				toolCalls.add(new VcrTrack.ToolCallSnapshot(call.id(), call.type(), call.name(), call.arguments()));
			}
		}

		String finishReason = (generation.getMetadata() == null) ? null : generation.getMetadata().getFinishReason();

		return new VcrTrack.GenerationSnapshot(output == null ? null : output.getText(), finishReason, toolCalls);
	}

	// ---------------------------------------------------------------------
	// Replay
	// ---------------------------------------------------------------------

	/**
	 * Rebuild a {@link ChatResponse} from a fixture.
	 *
	 * <p>Uses only public builders. {@code AssistantMessage}'s multi-argument constructor
	 * is {@code protected}, so tool calls can only be restored via
	 * {@code AssistantMessage.builder()}.
	 * @param track the fixture read from disk
	 * @return a response indistinguishable from a live one for assertion purposes
	 */
	public ChatResponse toChatResponse(VcrTrack track) {
		Assert.notNull(track, "track must not be null");
		Assert.notNull(track.response(), "track.response must not be null");

		VcrTrack.ResponseSnapshot snapshot = track.response();

		List<Generation> generations = new ArrayList<>();
		for (VcrTrack.GenerationSnapshot generation : nullSafe(snapshot.generations())) {
			generations.add(toGeneration(generation));
		}

		ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
		if (snapshot.id() != null) {
			metadata.id(snapshot.id());
		}
		if (snapshot.model() != null) {
			metadata.model(snapshot.model());
		}
		if (snapshot.usage() != null) {
			metadata.usage(toUsage(snapshot.usage()));
		}
		// Marks the response as replayed, so a test or a custom advisor downstream can
		// tell a fixture from a live call without inspecting timing.
		metadata.keyValue("vcr.replayed", Boolean.TRUE);
		metadata.keyValue("vcr.hash", track.hash());
		metadata.keyValue("vcr.recordedAt", track.recordedAt());

		return ChatResponse.builder().generations(generations).metadata(metadata.build()).build();
	}

	private Generation toGeneration(VcrTrack.GenerationSnapshot snapshot) {
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

		return new Generation(message, generationMetadata);
	}

	private Usage toUsage(VcrTrack.UsageSnapshot snapshot) {
		return new DefaultUsage(snapshot.promptTokens(), snapshot.completionTokens(), snapshot.totalTokens());
	}

	private String normalize(String text) {
		String result = (text == null) ? "" : text;
		for (VcrPromptNormalizer normalizer : this.normalizers) {
			result = normalizer.normalize(result);
		}
		return result;
	}

	private static <T> List<T> nullSafe(List<T> list) {
		return (list == null) ? List.of() : list;
	}

}
