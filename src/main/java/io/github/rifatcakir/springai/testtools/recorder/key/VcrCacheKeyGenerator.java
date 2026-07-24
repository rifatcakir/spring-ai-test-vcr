package io.github.rifatcakir.springai.testtools.recorder.key;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.rifatcakir.springai.testtools.recorder.VcrPromptNormalizer;

import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.util.Assert;

/**
 * Computes the deterministic SHA-256 cache key for a {@link Prompt}.
 *
 * <p>Two properties matter more than anything else here, and both are easy to lose by
 * accident:
 *
 * <ol>
 * <li><strong>Stability across JVM runs.</strong> The canonical form is assembled by hand
 * from explicitly named fields. It deliberately avoids {@code toString()},
 * {@code hashCode()}, reflection and Jackson serialisation of options, all of which vary
 * with iteration order, identity hashes, JDK version or an added field in a Spring AI
 * point release. A fixture recorded today must still resolve in eighteen months.</li>
 * <li><strong>Sensitivity to anything the model sees.</strong> Message text and role,
 * model name, sampling parameters and the full tool schema all feed the digest. If it can
 * change the model's behaviour, it must be able to bust the cache — otherwise the library
 * quietly serves a stale answer for a prompt nobody reviewed, which is precisely the
 * failure mode that makes semantic caching unusable for tests.</li>
 * </ol>
 *
 * <p>Ordering is preserved for messages, because conversation order is semantic. Ordering
 * is normalised to sorted for tool definitions and stop sequences, because those are sets
 * whose iteration order is an implementation detail.
 *
 * @author Rifat Cakir
 */
public class VcrCacheKeyGenerator {

	private static final String FIELD_SEPARATOR = "\n";

	private static final String NULL_TOKEN = " null";

	private static final String CALL_CANONICAL_HEADER = "vcr-canonical-form/v1";

	private static final String STREAM_CANONICAL_HEADER = "vcr-stream-canonical-form/v1";

	private final List<VcrPromptNormalizer> normalizers;

	public VcrCacheKeyGenerator() {
		this(List.of());
	}

	public VcrCacheKeyGenerator(List<VcrPromptNormalizer> normalizers) {
		Assert.notNull(normalizers, "normalizers must not be null");
		this.normalizers = List.copyOf(normalizers);
	}

	/**
	 * Compute the cache key for a prompt with no request-scoped context — equivalent to
	 * {@link #generate(Prompt, Map)} with an empty map. Prompts driven purely by {@code
	 * ChatClient.prompt()...call()} (no {@code entity()}/structured output) never carry
	 * anything the other overload would find anyway, so the two are interchangeable for
	 * that case.
	 * @param prompt the prompt about to be sent to the model
	 * @return the digest and the canonical string it was derived from
	 */
	public VcrCacheKey generate(Prompt prompt) {
		return generate(prompt, Map.of());
	}

	/**
	 * Compute the cache key for a prompt, also accounting for {@code ChatClientRequest}'s
	 * request-scoped context map.
	 *
	 * <p>Structured output (a {@code ChatClient...entity(Class)} call) is the reason this
	 * overload exists: {@code ChatModelCallAdvisor} — the terminal advisor,
	 * {@code getOrder() == Integer.MAX_VALUE} — splices the format instructions and JSON
	 * schema into the actual message text only after every other advisor, including this
	 * library's, has already run. Before this overload, {@link #generate(Prompt)} saw only
	 * the un-augmented {@link Prompt} and had no way to see {@code entity()}'s target type
	 * at all, so two structurally different {@code entity()} calls sharing the same prompt
	 * text canonicalized identically and collided on one fixture — confirmed against a
	 * real model in {@code OllamaStructuredOutputEndToEndTests}, not assumed.
	 * @param prompt the prompt about to be sent to the model
	 * @param context {@code ChatClientRequest.context()} at the point this advisor
	 * intercepts the call; only the two {@link ChatClientAttributes} keys relevant to
	 * structured output are read from it, everything else is ignored
	 * @return the digest and the canonical string it was derived from
	 */
	public VcrCacheKey generate(Prompt prompt, Map<String, Object> context) {
		Assert.notNull(prompt, "prompt must not be null");
		Assert.notNull(context, "context must not be null");
		String canonical = canonicalize(prompt, context);
		return new VcrCacheKey(sha256Hex(canonical), canonical);
	}

	/**
	 * Compute the cache key for a {@code ChatClient...stream(...)} request with no
	 * request-scoped context — equivalent to {@link #generateForStream(Prompt, Map)} with
	 * an empty map.
	 * @param prompt the prompt about to be streamed
	 * @return the digest and the canonical string it was derived from
	 */
	public VcrCacheKey generateForStream(Prompt prompt) {
		return generateForStream(prompt, Map.of());
	}

	/**
	 * Compute the cache key for a {@code ChatClient...stream(...)} request (R3) — same
	 * canonicalization as {@link #generate(Prompt, Map)} in every respect (same fields,
	 * same ordering, same normalization) except the leading header line, which is {@code
	 * "vcr-stream-canonical-form/v1"} instead of {@code "vcr-canonical-form/v1"}.
	 *
	 * <p>This is deliberate, not an oversight of "just reuse {@link #generate(Prompt,
	 * Map)}": a call and a stream sharing the exact same {@link Prompt}/context (the same
	 * messages, options, tools) would otherwise canonicalize identically and collide on
	 * one filename in the fixture directory — a {@code VcrTrack} (one response) and a
	 * {@code VcrStreamTrack} (a chunk sequence) are structurally different fixture types
	 * that must never share a hash. The header-only difference guarantees this without
	 * touching any other field, which is why {@link #generate(Prompt, Map)} and every
	 * existing golden hash test for it are completely unaffected — verified, not merely
	 * argued, by every prior golden hash test still passing unchanged.
	 * @param prompt the prompt about to be streamed
	 * @param context {@code ChatClientRequest.context()} at the point this advisor
	 * intercepts the call; same two structured-output keys as {@link #generate(Prompt,
	 * Map)} reads
	 * @return the digest and the canonical string it was derived from
	 */
	public VcrCacheKey generateForStream(Prompt prompt, Map<String, Object> context) {
		Assert.notNull(prompt, "prompt must not be null");
		Assert.notNull(context, "context must not be null");
		String canonical = canonicalize(prompt, context, STREAM_CANONICAL_HEADER);
		return new VcrCacheKey(sha256Hex(canonical), canonical);
	}

	/**
	 * Build the canonical, line-oriented representation of a prompt with no request-scoped
	 * context — equivalent to {@link #canonicalize(Prompt, Map)} with an empty map.
	 *
	 * <p>Exposed as {@code protected} so a project with an exotic requirement can override
	 * the contract, but overriding it changes every existing hash. Prefer a
	 * {@link VcrPromptNormalizer} for anything less than a total redefinition.
	 */
	protected String canonicalize(Prompt prompt) {
		return canonicalize(prompt, Map.of());
	}

	/**
	 * Build the canonical, line-oriented representation of a prompt, also accounting for
	 * {@code ChatClientRequest}'s request-scoped context map. See
	 * {@link #generate(Prompt, Map)} for why this overload exists.
	 *
	 * <p>Deliberately conditional: when {@code context} carries neither structured-output
	 * key, nothing about the canonical form changes from {@link #canonicalize(Prompt)} —
	 * not even an explicit "absent" marker line — so every prompt recorded before this
	 * overload existed keeps hashing exactly as it always did. Only a real {@code
	 * entity()}/structured-output call, which is the only caller that ever populates these
	 * two context keys, changes the canonical form at all.
	 */
	protected String canonicalize(Prompt prompt, Map<String, Object> context) {
		return canonicalize(prompt, context, CALL_CANONICAL_HEADER);
	}

	/**
	 * Shared implementation behind both {@link #canonicalize(Prompt, Map)} (calls) and
	 * {@link #generateForStream(Prompt, Map)} (streams) — identical in every field except
	 * the leading {@code header} line, which is what keeps the two from ever colliding.
	 * See {@link #generateForStream(Prompt, Map)}'s Javadoc for why that header-only
	 * difference is the whole mechanism.
	 */
	private String canonicalize(Prompt prompt, Map<String, Object> context, String header) {
		StringBuilder sb = new StringBuilder(512);

		sb.append(header).append(FIELD_SEPARATOR);

		ChatOptions options = prompt.getOptions();
		sb.append("model=").append(value(options == null ? null : options.getModel())).append(FIELD_SEPARATOR);
		sb.append("temperature=")
			.append(value(options == null ? null : options.getTemperature()))
			.append(FIELD_SEPARATOR);
		sb.append("topP=").append(value(options == null ? null : options.getTopP())).append(FIELD_SEPARATOR);
		sb.append("topK=").append(value(options == null ? null : options.getTopK())).append(FIELD_SEPARATOR);
		sb.append("maxTokens=").append(value(options == null ? null : options.getMaxTokens())).append(FIELD_SEPARATOR);
		sb.append("frequencyPenalty=")
			.append(value(options == null ? null : options.getFrequencyPenalty()))
			.append(FIELD_SEPARATOR);
		sb.append("presencePenalty=")
			.append(value(options == null ? null : options.getPresencePenalty()))
			.append(FIELD_SEPARATOR);

		// Sorted: stop sequences are order-insensitive to the provider.
		List<String> stops = (options == null || options.getStopSequences() == null) ? new ArrayList<>()
				: new ArrayList<>(options.getStopSequences());
		stops.sort(Comparator.naturalOrder());
		sb.append("stop=").append(String.join(",", stops)).append(FIELD_SEPARATOR);

		// Tool schemas change model behaviour even when the prompt does not.
		for (String tool : canonicalToolDescriptors(options)) {
			sb.append("tool=").append(tool).append(FIELD_SEPARATOR);
		}

		appendStructuredOutput(sb, context);

		// Order preserved: conversation sequence is semantic.
		List<Message> messages = prompt.getInstructions();
		if (messages != null) {
			for (Message message : messages) {
				String type = (message.getMessageType() == null) ? "UNKNOWN" : message.getMessageType().getValue();
				sb.append("message[")
					.append(type)
					.append("]=")
					.append(escape(normalize(message.getText())))
					.append(FIELD_SEPARATOR);
				appendMessageToolCalls(sb, message);
				appendMessageToolResponses(sb, message);
			}
		}

		return sb.toString();
	}

	/**
	 * {@code ChatClient...entity(Class)} stashes its format instructions and JSON schema on
	 * {@code ChatClientRequest.context()}, under {@link ChatClientAttributes#OUTPUT_FORMAT}
	 * and {@link ChatClientAttributes#STRUCTURED_OUTPUT_SCHEMA} respectively —
	 * {@code ChatModelCallAdvisor} only splices them into the actual message text
	 * afterwards, downstream of every other advisor. Without this, two different
	 * {@code entity()} target types sharing identical prompt text canonicalize identically.
	 *
	 * <p>Deliberately silent when neither key is present: this must not add so much as an
	 * "absent" marker line for a request that never touched {@code entity()}, or every
	 * fixture recorded before this method existed would hash differently.
	 */
	private void appendStructuredOutput(StringBuilder sb, Map<String, Object> context) {
		if (context == null || context.isEmpty()) {
			return;
		}
		Object outputFormat = context.get(ChatClientAttributes.OUTPUT_FORMAT.getKey());
		Object schema = context.get(ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey());
		if (outputFormat == null && schema == null) {
			return;
		}
		if (outputFormat != null) {
			sb.append("outputFormat=").append(escape(normalizeLineEndings(value(outputFormat)))).append(FIELD_SEPARATOR);
		}
		if (schema != null) {
			sb.append("structuredOutputSchema=")
				.append(escape(normalizeLineEndings(value(schema))))
				.append(FIELD_SEPARATOR);
		}
	}

	/**
	 * Collapses {@code CRLF} and lone {@code CR} to {@code LF} before hashing.
	 *
	 * <p>A tool's input schema and an {@code entity()} call's format instructions/JSON
	 * schema are rendered by Jackson's pretty printer, which embeds the JVM's own {@code
	 * System.lineSeparator()} — {@code \r\n} on Windows, {@code \n} on Linux/macOS.
	 * Without this, a fixture recorded on Windows and replayed in CI on Linux would hash
	 * the same logical schema differently and miss for a reason with nothing to do with
	 * the actual request — confirmed for real: a fixture recorded on Windows failed to
	 * replay on a Linux GitHub Actions runner until this normalization was added.
	 *
	 * <p>Deliberately scoped to schema/format text only, not message text (see {@link
	 * #canonicalToolDescriptors} and {@link #appendStructuredOutput}, the only two
	 * callers): a schema is generated by this library's own toolchain, so {@code CRLF}
	 * versus {@code LF} in it is provably meaningless noise from whichever machine
	 * recorded it. A user's own prompt text is not generated by us, and "if it can change
	 * the model's behaviour, it must be able to bust the cache" (see this class's
	 * top-level Javadoc) argues for leaving it exactly as authored — collapsing its line
	 * endings could in principle suppress a real difference a model actually sees.
	 */
	private static String normalizeLineEndings(String text) {
		return (text == null) ? null : text.replace("\r\n", "\n").replace("\r", "\n");
	}

	/**
	 * {@code AssistantMessage.getText()} is empty for a tool-calls-only turn — the calls
	 * themselves live only in {@code getToolCalls()}. Without this, two conversations
	 * differing only in which tool was called (or with what arguments) would canonicalize
	 * identically, which under {@link io.github.rifatcakir.springai.testtools.recorder.VcrScope#INSIDE_TOOL_LOOP}
	 * means the wrong turn's fixture could replay for the wrong tool call.
	 *
	 * <p>The tool call's own {@code id} participates too, in keeping with "if it can change
	 * the model's behaviour, it must be able to bust the cache": that id is what correlates
	 * this call to its result on the next turn, so it is part of what the model sees, not an
	 * incidental identifier. One consequence worth knowing: if a provider generates a fresh,
	 * non-deterministic id for "the same" tool call on every re-recording, re-recording that
	 * scenario can produce a new fixture file each time rather than overwriting the same one
	 * — noisy, but not a correctness problem, since a fixture once committed is deterministic
	 * on replay regardless of how it got its id.
	 */
	private void appendMessageToolCalls(StringBuilder sb, Message message) {
		if (!(message instanceof AssistantMessage assistantMessage) || assistantMessage.getToolCalls() == null) {
			return;
		}
		for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
			sb.append("message.toolCall=")
				.append(escape(value(call.id())))
				.append('|')
				.append(escape(value(call.type())))
				.append('|')
				.append(escape(value(call.name())))
				.append('|')
				.append(escape(value(call.arguments())))
				.append(FIELD_SEPARATOR);
		}
	}

	/**
	 * {@code ToolResponseMessage.getText()} is always empty — the actual tool result lives
	 * only in {@code getResponses()}. Without this, two conversations differing only in what
	 * a tool responded with — a real value versus an error, a cache hit versus a miss —
	 * would canonicalize identically, the same failure mode {@link #appendMessageToolCalls}
	 * exists to prevent on the request side.
	 */
	private void appendMessageToolResponses(StringBuilder sb, Message message) {
		if (!(message instanceof ToolResponseMessage toolResponseMessage) || toolResponseMessage.getResponses() == null) {
			return;
		}
		for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
			sb.append("message.toolResponse=")
				.append(escape(value(response.id())))
				.append('|')
				.append(escape(value(response.name())))
				.append('|')
				.append(escape(value(response.responseData())))
				.append(FIELD_SEPARATOR);
		}
	}

	/**
	 * Apply every configured normalizer, in registration order.
	 */
	protected String normalize(String text) {
		String result = (text == null) ? "" : text;
		for (VcrPromptNormalizer normalizer : this.normalizers) {
			result = normalizer.normalize(result);
		}
		return result;
	}

	/**
	 * Render tool definitions into a sorted, stable list of {@code name|description|schema}
	 * descriptors.
	 *
	 * <p>Sorted because callback registration order is not something a test author
	 * controls, and an incidental reordering must not invalidate every fixture.
	 *
	 * <p>Note that Spring AI 2.0 removed {@code ToolCallingChatOptions.getToolNames()};
	 * only resolved {@link ToolCallback} instances are reachable from the options, which
	 * is the better input anyway — it carries the full schema rather than a bare name.
	 */
	private Set<String> canonicalToolDescriptors(ChatOptions options) {
		Set<String> descriptors = new TreeSet<>();
		if (!(options instanceof ToolCallingChatOptions toolOptions) || toolOptions.getToolCallbacks() == null) {
			return descriptors;
		}
		for (ToolCallback callback : toolOptions.getToolCallbacks()) {
			ToolDefinition definition = callback.getToolDefinition();
			descriptors.add(value(definition.name()) + "|" + value(normalizeLineEndings(definition.description()))
					+ "|" + value(normalizeLineEndings(definition.inputSchema())));
		}
		return descriptors;
	}

	/**
	 * Distinguish {@code null} from the empty string, so that an absent temperature and a
	 * temperature that stringifies to nothing cannot collide.
	 */
	private static String value(Object value) {
		return (value == null) ? NULL_TOKEN : String.valueOf(value);
	}

	/**
	 * Prevent a newline inside a message from forging an extra canonical field. Without
	 * this, a user message containing {@code "\nmodel=gpt-4o"} would be indistinguishable
	 * from a real model declaration.
	 */
	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
	}

	private static String sha256Hex(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 is mandated by the JLS for every conforming JRE.
			throw new IllegalStateException("SHA-256 unavailable on this JVM", ex);
		}
	}

}
