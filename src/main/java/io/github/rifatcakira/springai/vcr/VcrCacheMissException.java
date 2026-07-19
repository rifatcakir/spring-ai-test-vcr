package io.github.rifatcakira.springai.vcr;

import java.nio.file.Path;

/**
 * Thrown in {@link VcrMode#REPLAY_ONLY} when no fixture exists for the computed hash.
 *
 * <p>The message is deliberately verbose. This exception surfaces in CI, usually to
 * someone who did not write the prompt that changed, so it carries everything needed to
 * diagnose it without re-running anything: the hash, the file that was looked for, the
 * exact canonical request that produced the hash, and the command to re-record.
 *
 * @author Rifat Cakira
 */
public class VcrCacheMissException extends RuntimeException {

	private final String hash;

	private final transient Path expectedPath;

	private final String canonicalRequest;

	public VcrCacheMissException(String hash, Path expectedPath, String canonicalRequest) {
		super(buildMessage(hash, expectedPath, canonicalRequest));
		this.hash = hash;
		this.expectedPath = expectedPath;
		this.canonicalRequest = canonicalRequest;
	}

	private static String buildMessage(String hash, Path expectedPath, String canonicalRequest) {
		return """
				No VCR fixture for this request, and mode is REPLAY_ONLY so the real model was not called.

				  hash     : %s
				  expected : %s

				This almost always means a prompt, a model option or a tool definition changed \
				without the fixture being re-recorded. That is the check working as intended: the \
				request reaching the model is not the one that was reviewed.

				To re-record locally (requires a reachable model):
				  mvn test -Dspring.ai.test.vcr.mode=RECORD_OR_REPLAY
				then commit the new file under the cache directory.

				Canonical request that produced the hash:
				%s"""
			.formatted(hash, expectedPath, indent(canonicalRequest));
	}

	private static String indent(String value) {
		return value.lines().map(line -> "  | " + line).reduce((a, b) -> a + System.lineSeparator() + b).orElse("  | ");
	}

	/**
	 * The SHA-256 hex digest that was looked up.
	 */
	public String getHash() {
		return this.hash;
	}

	/**
	 * The fixture path that was expected to exist.
	 */
	public Path getExpectedPath() {
		return this.expectedPath;
	}

	/**
	 * The canonical, normalized request string the hash was computed over. Useful for
	 * diffing against a fixture's recorded {@code canonicalRequest} to see exactly which
	 * character changed.
	 */
	public String getCanonicalRequest() {
		return this.canonicalRequest;
	}

}
