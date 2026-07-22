package io.github.rifatcakir.springai.testtools.recorder;

/**
 * Operational strategy for the VCR cache.
 *
 * <p>These modes are deliberately coarse. There is no "refresh if older than N days" and
 * no similarity threshold, because a test fixture that silently drifts underneath you is
 * worse than no fixture at all. A cache entry either matches its computed hash exactly or
 * it does not exist.
 *
 * <p><strong>Mapping to VCR.py record modes:</strong>
 * <table border="1">
 * <caption>Record mode equivalence</caption>
 * <tr><th>VCR.py</th><th>This library</th></tr>
 * <tr><td>{@code once}</td><td>{@link #RECORD_OR_REPLAY}</td></tr>
 * <tr><td>{@code none}</td><td>{@link #REPLAY_ONLY}</td></tr>
 * <tr><td>{@code all}</td><td>{@link #RECORD_ALWAYS}</td></tr>
 * <tr><td>{@code new_episodes}</td><td>{@link #RECORD_OR_REPLAY}</td></tr>
 * </table>
 *
 * <p>VCR.py distinguishes {@code once} from {@code new_episodes} because a cassette is a
 * single file holding many ordered episodes. This library stores one file per request
 * hash, so "append a newly added call to an existing cassette" and "record what is
 * missing" are the same operation and collapse into {@link #RECORD_OR_REPLAY}.
 *
 * @author Rifat Cakir
 */
public enum VcrMode {

	/**
	 * Replay from disk when a fixture exists for the computed hash; otherwise invoke the
	 * real model and persist the result.
	 *
	 * <p>The local development default. First run is slow, every run after that is a file
	 * read. Existing fixtures are never overwritten.
	 */
	RECORD_OR_REPLAY,

	/**
	 * Replay from disk when a fixture exists; throw {@link VcrCacheMissException}
	 * immediately when it does not.
	 *
	 * <p>Intended for CI, where there is no Ollama container and no API key. A miss means
	 * someone changed a prompt without re-recording, and the build should say so loudly
	 * rather than reaching for the network and a billing event.
	 */
	REPLAY_ONLY,

	/**
	 * Ignore existing fixtures, invoke the real model for every call, and overwrite what
	 * is on disk.
	 *
	 * <p>The "re-record the tape" mode. Use it after a deliberate prompt change, or
	 * periodically to check that fixtures still resemble what the model actually returns.
	 * Destructive by design: it overwrites committed fixtures, so it belongs in a
	 * developer's hands and never in CI.
	 */
	RECORD_ALWAYS,

	/**
	 * Disable the cache entirely. Every call reaches the real model and nothing is read
	 * from or written to disk.
	 *
	 * <p>Differs from {@link #RECORD_ALWAYS} in that it leaves existing fixtures
	 * untouched.
	 */
	BYPASS

}
