package io.github.rifatcakir.springai.testtools.recorder.track;

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
 * @author Rifat Cakir
 */
public record VcrTrack(String schemaVersion, String hash, String recordedAt, String canonicalRequest,
		RequestSnapshot request, ResponseSnapshot response) {

	/**
	 * {@code "2"}: {@link MessageSnapshot} gained {@code toolCalls} and
	 * {@code toolResponses}, capturing what {@code AssistantMessage.getToolCalls()} and
	 * {@code ToolResponseMessage.getResponses()} actually carry for a message in
	 * conversation history — previously silently dropped, because both
	 * {@code AssistantMessage.getText()} (when a turn is tool-calls-only) and
	 * {@code ToolResponseMessage.getText()} (always) return an empty string, and this
	 * fixture format and the hash it participates in were built entirely from
	 * {@code Message.getText()}. Two conversations differing only in which tool was
	 * called, or what a tool responded with, canonicalized identically and could replay
	 * the wrong turn under {@link io.github.rifatcakir.springai.testtools.recorder.VcrScope#INSIDE_TOOL_LOOP}
	 * — the one scope where the advisor ever sees a prompt containing these message
	 * types at all.
	 *
	 * <p>This bump is additive, not a hard compatibility gate: a {@code "1"} fixture
	 * still deserializes under schema {@code "2"} code — the two new
	 * {@code MessageSnapshot} fields simply come back {@code null} for a record that
	 * never had them, verified in
	 * {@code VcrTrackStoreRoundTripTests#schemaVersion1FixtureWithoutToolFieldsStillReplays}.
	 * That {@code null} is harmless rather than merely tolerated: nothing in this library
	 * ever reads {@code RequestSnapshot.messages()} back after deserialization — it exists
	 * for the hash (computed from the live {@code Prompt}, never from a stored fixture) and
	 * for human review, not for replay, which is driven entirely by {@code ResponseSnapshot}.
	 * There is deliberately no reader-side version check
	 * enforcing this; the version number here is provenance, telling a reviewer which
	 * behaviour produced a given fixture, not a migration gate this library runs.
	 */
	public static final String CURRENT_SCHEMA_VERSION = "2";

	/**
	 * What went to the model. Recorded for reviewability — a fixture diff in a pull
	 * request should be readable — and never used during replay, which keys purely off
	 * the filename hash.
	 * @param model the model name requested, or {@code null} if the request carried no options
	 * @param temperature sampling temperature, or {@code null} if unset
	 * @param topP nucleus sampling parameter, or {@code null} if unset
	 * @param topK top-k sampling parameter, or {@code null} if unset
	 * @param maxTokens the requested completion length limit, or {@code null} if unset
	 * @param stopSequences stop sequences, in the sorted order used to compute the hash — not
	 * necessarily the order the caller supplied them in
	 * @param messages every message in the prompt, in original conversation order
	 * @param tools tool definitions available to the model for this request
	 */
	public record RequestSnapshot(String model, Double temperature, Double topP, Integer topK, Integer maxTokens,
			List<String> stopSequences, List<MessageSnapshot> messages, List<ToolDefinitionSnapshot> tools) {
	}

	/**
	 * One message in a prompt or a tool-call round-trip.
	 *
	 * <p>{@code toolCalls} and {@code toolResponses} are mutually exclusive in practice —
	 * an {@code AssistantMessage} may carry the former, a {@code ToolResponseMessage} the
	 * latter, and every other message type carries neither — but both are always
	 * non-{@code null}, empty when not applicable, so a caller never needs a null check to
	 * iterate them.
	 * @param type the message role, e.g. {@code "user"}, {@code "system"}, {@code "assistant"}
	 * @param text the message content, after any {@code VcrPromptNormalizer}s have run;
	 * empty for a tool-calls-only assistant turn or for any {@code ToolResponseMessage},
	 * since neither carries plain text — see {@code toolCalls}/{@code toolResponses}
	 * @param toolCalls tools this message asked to have called — populated only for an
	 * {@code AssistantMessage} that made tool calls, empty otherwise
	 * @param toolResponses tool results this message is reporting back to the model —
	 * populated only for a {@code ToolResponseMessage}, empty otherwise
	 */
	public record MessageSnapshot(String type, String text, List<ToolCallSnapshot> toolCalls,
			List<ToolResponseSnapshot> toolResponses) {
	}

	/**
	 * Tool definitions are part of the hash because changing a tool's name, description or
	 * schema changes what the model does, even when the prompt text is untouched.
	 * @param name the tool's name, as the model sees it
	 * @param description the tool's description, as the model sees it
	 * @param inputSchema the tool's JSON Schema for its input, as a JSON string
	 */
	public record ToolDefinitionSnapshot(String name, String description, String inputSchema) {
	}

	/**
	 * What came back, reduced to the parts that survive a round trip.
	 * @param id the provider's response identifier, or {@code null} if none was present
	 * @param model the model that actually answered, or {@code null} if the response carried
	 * no metadata
	 * @param generations every candidate answer the model returned, usually exactly one
	 * @param usage token accounting, or {@code null} if the response carried none
	 * @param metadata additional string-valued response metadata beyond {@code id}/{@code model}/{@code usage}
	 */
	public record ResponseSnapshot(String id, String model, List<GenerationSnapshot> generations, UsageSnapshot usage,
			Map<String, String> metadata) {
	}

	/**
	 * One candidate answer.
	 * @param text the assistant's message text; empty, never {@code null}, if the turn was
	 * tool-calls-only
	 * @param finishReason why the model stopped, e.g. {@code "stop"} or {@code "tool_calls"};
	 * {@code null} if the provider didn't report one
	 * @param toolCalls tool calls the model requested in this turn, empty if none
	 */
	public record GenerationSnapshot(String text, String finishReason, List<ToolCallSnapshot> toolCalls) {
	}

	/**
	 * Mirrors {@code AssistantMessage.ToolCall}.
	 * @param id the provider's identifier for this specific tool call, used to correlate it
	 * with the tool's result on the next turn
	 * @param type the tool call type; {@code "function"} for every provider currently
	 * supported by Spring AI
	 * @param name the name of the tool being called
	 * @param arguments the tool's input arguments, as a JSON-encoded string exactly as the
	 * model returned them — not parsed or validated by this library
	 */
	public record ToolCallSnapshot(String id, String type, String name, String arguments) {
	}

	/**
	 * Mirrors {@code ToolResponseMessage.ToolResponse}. A single {@code ToolResponseMessage}
	 * can carry more than one of these — one {@code @Tool} method's result per parallel
	 * tool call the assistant made in the preceding turn.
	 * @param id the tool call id this response answers, correlating it back to the specific
	 * entry in the preceding assistant message's {@code toolCalls}
	 * @param name the tool's name
	 * @param responseData the tool's result exactly as it was returned, not parsed or
	 * validated by this library
	 */
	public record ToolResponseSnapshot(String id, String name, String responseData) {
	}

	/**
	 * Token accounting for one response. Never contains a provider's raw, non-portable usage
	 * object — see the class-level Javadoc.
	 * @param promptTokens tokens consumed by the request, or {@code null} if the provider
	 * didn't report it
	 * @param completionTokens tokens generated in the response, or {@code null} if the
	 * provider didn't report it
	 * @param totalTokens the provider-reported total, or {@code null} if not reported;
	 * not guaranteed to equal {@code promptTokens + completionTokens} for every provider
	 */
	public record UsageSnapshot(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
	}

}
