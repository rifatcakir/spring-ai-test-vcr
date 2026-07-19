package io.github.rifatcakir.springai.vcr.key;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import io.github.rifatcakir.springai.vcr.VcrPromptNormalizer;

import org.springframework.ai.chat.messages.Message;
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
 * @author Rifat Cakira
 */
public class VcrCacheKeyGenerator {

	private static final String FIELD_SEPARATOR = "\n";

	private static final String NULL_TOKEN = " null";

	private final List<VcrPromptNormalizer> normalizers;

	public VcrCacheKeyGenerator() {
		this(List.of());
	}

	public VcrCacheKeyGenerator(List<VcrPromptNormalizer> normalizers) {
		Assert.notNull(normalizers, "normalizers must not be null");
		this.normalizers = List.copyOf(normalizers);
	}

	/**
	 * Compute the cache key for a prompt.
	 * @param prompt the prompt about to be sent to the model
	 * @return the digest and the canonical string it was derived from
	 */
	public VcrCacheKey generate(Prompt prompt) {
		Assert.notNull(prompt, "prompt must not be null");
		String canonical = canonicalize(prompt);
		return new VcrCacheKey(sha256Hex(canonical), canonical);
	}

	/**
	 * Build the canonical, line-oriented representation of a prompt.
	 *
	 * <p>Exposed as {@code protected} so a project with an exotic requirement can override
	 * the contract, but overriding it changes every existing hash. Prefer a
	 * {@link VcrPromptNormalizer} for anything less than a total redefinition.
	 */
	protected String canonicalize(Prompt prompt) {
		StringBuilder sb = new StringBuilder(512);

		sb.append("vcr-canonical-form/v1").append(FIELD_SEPARATOR);

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
			}
		}

		return sb.toString();
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
			descriptors.add(value(definition.name()) + "|" + value(definition.description()) + "|"
					+ value(definition.inputSchema()));
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
