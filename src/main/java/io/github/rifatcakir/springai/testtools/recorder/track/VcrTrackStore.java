package io.github.rifatcakir.springai.testtools.recorder.track;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.util.Assert;

/**
 * Reads and writes {@link VcrTrack} fixtures as one JSON file per request hash.
 *
 * <p>Fixtures are pretty-printed rather than compact. They are committed to version
 * control and reviewed in pull requests, so a readable diff is worth more than a smaller
 * file — when someone changes a system prompt, the reviewer should be able to see what the
 * model said about it.
 *
 * <p>Writes are atomic: content goes to a temporary file in the same directory and is then
 * moved into place. Surefire runs test classes in parallel by default in many setups, and
 * a half-written fixture that survives to the next run is a corrupt cache entry that fails
 * mysteriously days later.
 *
 * <p>Uses Jackson 3 ({@code tools.jackson.*}), the baseline in Spring AI 2.0 / Spring Boot
 * 4. Jackson 3 throws unchecked exceptions, so there is no checked {@code IOException} to
 * launder on the mapper calls — only the filesystem operations need wrapping.
 *
 * @author Rifat Cakir
 */
public class VcrTrackStore {

	private static final Logger logger = LoggerFactory.getLogger(VcrTrackStore.class);

	private static final String FILE_EXTENSION = ".json";

	private final Path cacheDirectory;

	private final JsonMapper jsonMapper;

	public VcrTrackStore(Path cacheDirectory) {
		this(cacheDirectory, defaultJsonMapper());
	}

	public VcrTrackStore(Path cacheDirectory, JsonMapper jsonMapper) {
		Assert.notNull(cacheDirectory, "cacheDirectory must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		this.cacheDirectory = cacheDirectory.toAbsolutePath().normalize();
		this.jsonMapper = jsonMapper;
	}

	/**
	 * A mapper tuned for fixture durability.
	 *
	 * <p>Unknown properties are tolerated so that a fixture written by a newer version of
	 * this library, carrying a field the running version has never heard of, still
	 * replays instead of failing the build.
	 */
	public static JsonMapper defaultJsonMapper() {
		return JsonMapper.builder()
			.enable(SerializationFeature.INDENT_OUTPUT)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();
	}

	/**
	 * Resolve the fixture path for a hash.
	 * @param hash the SHA-256 hex digest
	 * @return the absolute path the fixture would live at, whether or not it exists
	 */
	public Path pathFor(String hash) {
		Assert.hasText(hash, "hash must not be empty");
		return this.cacheDirectory.resolve(hash + FILE_EXTENSION);
	}

	/**
	 * Load the fixture for a hash, if one exists.
	 *
	 * <p>A malformed fixture is reported and treated as absent rather than thrown. A
	 * corrupt file — half-written by a killed process, or mangled by a bad merge — should
	 * degrade to a re-record in {@code RECORD_OR_REPLAY}, not wedge the build. In
	 * {@code REPLAY_ONLY} the caller turns the resulting absence into a loud failure
	 * anyway, so nothing is silently swallowed.
	 * @param hash the SHA-256 hex digest
	 * @return the fixture, or empty if missing or unreadable
	 */
	public Optional<VcrTrack> read(String hash) {
		Path path = pathFor(hash);
		if (!Files.isRegularFile(path)) {
			logger.debug("VCR no fixture at {}", path);
			return Optional.empty();
		}
		try {
			String json = Files.readString(path, StandardCharsets.UTF_8);
			VcrTrack track = this.jsonMapper.readValue(json, VcrTrack.class);
			logger.debug("VCR read fixture {} ({} bytes)", path.getFileName(), json.length());
			return Optional.of(track);
		}
		catch (IOException ex) {
			logger.warn("VCR could not read fixture {}; treating as a cache miss", path, ex);
			return Optional.empty();
		}
		catch (RuntimeException ex) {
			logger.warn("VCR fixture {} is malformed; treating as a cache miss. Delete it and re-record.", path, ex);
			return Optional.empty();
		}
	}

	/**
	 * Persist a fixture, creating the cache directory if needed.
	 * @param track the fixture to write
	 * @throws UncheckedIOException if the fixture cannot be written
	 */
	public void write(VcrTrack track) {
		Assert.notNull(track, "track must not be null");
		Path path = pathFor(track.hash());
		try {
			Files.createDirectories(this.cacheDirectory);
			String json = this.jsonMapper.writeValueAsString(track);

			Path temp = Files.createTempFile(this.cacheDirectory, track.hash(), ".tmp");
			try {
				Files.writeString(temp, json, StandardCharsets.UTF_8);
				moveIntoPlace(temp, path);
			}
			finally {
				Files.deleteIfExists(temp);
			}

			logger.info("VCR RECORDED  [{}] -> {}", shortHash(track.hash()), path);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("VCR failed to write fixture to " + path, ex);
		}
	}

	private void moveIntoPlace(Path temp, Path target) throws IOException {
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException ex) {
			// Some Windows and network filesystems refuse atomic moves. A non-atomic
			// replace is still better than writing in place.
			logger.debug("VCR atomic move unsupported at {}; falling back to replace", target);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Delete the fixture for a hash, if present.
	 * @param hash the SHA-256 hex digest
	 * @return {@code true} if a file was deleted
	 */
	public boolean delete(String hash) {
		Path path = pathFor(hash);
		try {
			boolean deleted = Files.deleteIfExists(path);
			if (deleted) {
				logger.info("VCR deleted fixture {}", path);
			}
			return deleted;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("VCR failed to delete fixture at " + path, ex);
		}
	}

	public Path getCacheDirectory() {
		return this.cacheDirectory;
	}

	public static String shortHash(String hash) {
		return (hash == null || hash.length() < 12) ? String.valueOf(hash) : hash.substring(0, 12);
	}

}
