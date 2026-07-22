package io.github.rifatcakir.springai.testtools.recorder.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.util.Assert;

/**
 * Computes the deterministic SHA-256 cache key for an {@code EmbeddingRequest} — the
 * embedding counterpart of {@code
 * io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator}.
 *
 * <p>Kept as a separate class rather than folded into the chat key generator: an
 * embedding request (a batch of input strings plus {@link EmbeddingOptions}) shares
 * nothing structurally with a chat {@code Prompt} (messages, tools, structured output),
 * so a shared canonicalization method would only be shared in name. The canonical
 * form's own header (see {@link #canonicalize}) guarantees an embedding hash can never
 * collide with a chat hash even if the two inputs happened to coincide byte-for-byte.
 *
 * <p>Same two properties as the chat key generator, for the same reasons: hand-assembled
 * from explicitly named fields (never {@code toString()}/reflection/Jackson), and
 * sensitive to anything that can change what the model computes — model name,
 * {@code dimensions}, and every input text, in the order given, since order is
 * semantic (each input's embedding is returned at the same index).
 *
 * @author Rifat Cakir
 */
public class VcrEmbeddingCacheKeyGenerator {

	private static final String FIELD_SEPARATOR = "\n";

	private static final String NULL_TOKEN = " null";

	/**
	 * Compute the cache key for an embedding request.
	 * @param request the request about to be sent to the model
	 * @return the digest and the canonical string it was derived from
	 */
	public VcrEmbeddingCacheKey generate(EmbeddingRequest request) {
		Assert.notNull(request, "request must not be null");
		String canonical = canonicalize(request);
		return new VcrEmbeddingCacheKey(sha256Hex(canonical), canonical);
	}

	/**
	 * Build the canonical, line-oriented representation of an embedding request.
	 *
	 * <p>Exposed as {@code protected} so a project with an exotic requirement can
	 * override the contract, but overriding it changes every existing embedding hash.
	 */
	protected String canonicalize(EmbeddingRequest request) {
		StringBuilder sb = new StringBuilder(256);

		sb.append("vcr-embedding-canonical-form/v1").append(FIELD_SEPARATOR);

		EmbeddingOptions options = request.getOptions();
		sb.append("model=").append(value(options == null ? null : options.getModel())).append(FIELD_SEPARATOR);
		sb.append("dimensions=")
			.append(value(options == null ? null : options.getDimensions()))
			.append(FIELD_SEPARATOR);

		// Order preserved, not sorted: each input's embedding is returned at the same
		// index in the response, so the order is semantic, not an incidental detail.
		List<String> inputs = request.getInstructions();
		if (inputs != null) {
			for (int i = 0; i < inputs.size(); i++) {
				sb.append("input[").append(i).append("]=").append(escape(value(inputs.get(i)))).append(FIELD_SEPARATOR);
			}
		}

		return sb.toString();
	}

	/**
	 * Distinguish {@code null} from the empty string, so that an absent option and a
	 * value that stringifies to nothing cannot collide.
	 */
	private static String value(Object value) {
		return (value == null) ? NULL_TOKEN : String.valueOf(value);
	}

	/**
	 * Prevent a newline inside an input string from forging an extra canonical field —
	 * same reasoning as {@code VcrCacheKeyGenerator#escape}.
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
