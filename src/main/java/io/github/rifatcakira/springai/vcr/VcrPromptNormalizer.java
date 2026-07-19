package io.github.rifatcakira.springai.vcr;

/**
 * Rewrites message text before it is fed into the cache key hash.
 *
 * <p>This is the Java analogue of a custom VCR.py request matcher, inverted. Rather than
 * asking "does this incoming request match a recorded one?" against every stored cassette
 * — which would require scanning the whole cache directory — we canonicalise the request
 * first and let the SHA-256 do the matching in O(1) via a filename lookup.
 *
 * <p>The problem it solves is the one that kills naive prompt hashing. Given:
 *
 * <pre>{@code
 * chatClient.prompt()
 *     .user("Today is " + LocalDate.now() + ". Summarise the backlog.")
 *     .call();
 * }</pre>
 *
 * the raw text differs every midnight, so the hash differs, so the cache misses forever
 * and the fixture directory grows without bound. A normalizer that replaces ISO dates
 * with a fixed token makes the request stable:
 *
 * <pre>{@code
 * @Bean
 * VcrPromptNormalizer ignoreDates() {
 *     return RegexPromptNormalizer.ISO_DATE;
 * }
 * }</pre>
 *
 * <p>Normalizers are applied in {@link org.springframework.core.Ordered} sequence to every
 * message in the prompt, and affect the hash <em>only</em>. The text sent to the real
 * model on a cache miss is always the original, unmodified prompt. Recorded fixtures store
 * the normalized request text so that a committed fixture never leaks a live value into
 * version control.
 *
 * <p>Beware the obvious failure mode: normalizing away something the model actually
 * conditions on will make two genuinely different requests collide on one fixture. Redact
 * the volatile parts, not the meaningful ones.
 *
 * @author Rifat Cakira
 * @see RegexPromptNormalizer
 */
@FunctionalInterface
public interface VcrPromptNormalizer {

	/**
	 * Canonicalise a single message's text.
	 * @param text the raw message text, never {@code null}
	 * @return the text to hash, never {@code null}
	 */
	String normalize(String text);

	/**
	 * Compose this normalizer with another, applying this one first.
	 * @param next the normalizer to apply to this one's output
	 * @return a composed normalizer
	 */
	default VcrPromptNormalizer andThen(VcrPromptNormalizer next) {
		return text -> next.normalize(this.normalize(text));
	}

}
