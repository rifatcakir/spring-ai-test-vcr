package io.github.rifatcakira.springai.vcr;

import java.util.regex.Pattern;

import org.springframework.util.Assert;

/**
 * A {@link VcrPromptNormalizer} that replaces every match of a regular expression with a
 * fixed placeholder.
 *
 * <p>Covers the common causes of hash instability: dates, timestamps, UUIDs and
 * correlation identifiers injected into prompt templates at runtime.
 *
 * @author Rifat Cakira
 */
public final class RegexPromptNormalizer implements VcrPromptNormalizer {

	/** Replaces ISO-8601 calendar dates, e.g. {@code 2026-07-19}. */
	public static final RegexPromptNormalizer ISO_DATE = of("\\d{4}-\\d{2}-\\d{2}", "<VCR:DATE>");

	/** Replaces ISO-8601 date-times, e.g. {@code 2026-07-19T18:58:03.271Z}. */
	public static final RegexPromptNormalizer ISO_DATE_TIME = of(
			"\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:[Zz]|[+-]\\d{2}:?\\d{2})?", "<VCR:DATETIME>");

	/** Replaces RFC-4122 UUIDs in any case. */
	public static final RegexPromptNormalizer UUID = of(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<VCR:UUID>");

	/** Replaces bare epoch-millisecond timestamps (13 consecutive digits). */
	public static final RegexPromptNormalizer EPOCH_MILLIS = of("\\b\\d{13}\\b", "<VCR:EPOCH>");

	private final Pattern pattern;

	private final String placeholder;

	private RegexPromptNormalizer(Pattern pattern, String placeholder) {
		Assert.notNull(pattern, "pattern must not be null");
		Assert.notNull(placeholder, "placeholder must not be null");
		this.pattern = pattern;
		this.placeholder = placeholder;
	}

	/**
	 * Create a normalizer from a regular expression.
	 * @param regex the pattern to redact
	 * @param placeholder the stable token to substitute
	 * @return a new normalizer
	 */
	public static RegexPromptNormalizer of(String regex, String placeholder) {
		Assert.hasText(regex, "regex must not be empty");
		return new RegexPromptNormalizer(Pattern.compile(regex), placeholder);
	}

	@Override
	public String normalize(String text) {
		return (text == null) ? "" : this.pattern.matcher(text).replaceAll(this.placeholder);
	}

	@Override
	public String toString() {
		return "RegexPromptNormalizer[" + this.pattern.pattern() + " -> " + this.placeholder + "]";
	}

}
