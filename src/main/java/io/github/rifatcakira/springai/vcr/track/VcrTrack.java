package io.github.rifatcakira.springai.vcr.track;

import java.util.List;
import java.util.Map;

/**
 * The on-disk fixture format — one "track" on the tape.
 *
 * <p>Spring AI's response domain is not safe to serialise directly. {@code ChatResponse},
 * {@code Generation} and {@code AssistantMessage} lack public no-arg constructors,
 * {@code AssistantMessage}'s full constructor is {@code protected}, and
 * {@code ChatResponseMetadata} carries a {@code nativeUsage} field holding the raw
 * provider SDK object, which drags an unbounded and provider-specific object graph into
 * the JSON. Round-tripping any of that through Jackson produces either a crash or a fixture
 * that only deserialises on the machine that wrote it.
 *
 * <p>So this record is a deliberate, narrow projection: everything a test can meaningfully
 * assert on, and nothing else. Fields are flat, all types are JSON primitives or records,
 * and every component is reconstructible through Spring AI's public builders.
 *
 * <p>It is also a security boundary. Because interception happens at the advisor layer
 * rather than the HTTP layer, no {@code Authorization} header, bearer token or API key
 * ever reaches this type — there is nothing to filter, unlike VCR.py where redacting
 * {@code sk-...} from committed cassettes is a required manual step.
 *
 * @param schemaVersion format version, so a future change can migrate rather than crash
 * @param hash the SHA-256 key this fixture is filed under
 * @param recordedAt ISO-8601 instant the recording was made, for human triage only
 * @param canonicalRequest the exact normalized string the hash was computed over
 * @param request a human-readable snapshot of what was sent
 * @param response the replayable payload
 * @author Rifat Cakira
 */
public record VcrTrack(String schemaVersion, String hash, String recordedAt, String canonicalRequest,
		RequestSnapshot request, ResponseSnapshot response) {

	public static final String CURRENT_SCHEMA_VERSION = "1";

	/**
	 * What went to the model. Recorded for reviewability — a fixture diff in a pull
	 * request should be readable — and never used during replay, which keys purely off
	 * the filename hash.
	 */
	public record RequestSnapshot(String model, Double temperature, Double topP, Integer topK, Integer maxTokens,
			List<String> stopSequences, List<MessageSnapshot> messages, List<ToolDefinitionSnapshot> tools) {
	}

	public record MessageSnapshot(String type, String text) {
	}

	/**
	 * Tool definitions are part of the hash because changing a tool's name, description or
	 * schema changes what the model does, even when the prompt text is untouched.
	 */
	public record ToolDefinitionSnapshot(String name, String description, String inputSchema) {
	}

	/**
	 * What came back, reduced to the parts that survive a round trip.
	 */
	public record ResponseSnapshot(String id, String model, List<GenerationSnapshot> generations, UsageSnapshot usage,
			Map<String, String> metadata) {
	}

	public record GenerationSnapshot(String text, String finishReason, List<ToolCallSnapshot> toolCalls) {
	}

	/**
	 * Mirrors {@code AssistantMessage.ToolCall}.
	 */
	public record ToolCallSnapshot(String id, String type, String name, String arguments) {
	}

	public record UsageSnapshot(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
	}

}
