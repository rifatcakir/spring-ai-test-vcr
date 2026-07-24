package io.github.rifatcakir.springai.testtools.recorder.stream;

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
 * Reads and writes {@link VcrStreamTrack} fixtures as one JSON file per request hash —
 * the streaming counterpart of {@code
 * io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore}, kept as a
 * separate class rather than a generalized store for the same reason
 * {@code VcrEmbeddingTrackStore} (R4) is its own class: one extra type parameter for
 * three current callers would be the premature abstraction, not the duplication.
 *
 * <p>Same durability properties as every other store in this project: pretty-printed
 * and committed, atomic writes (temp file then move), and a malformed fixture degrades
 * to a cache miss rather than crashing the build.
 *
 * @author Rifat Cakir
 */
public class VcrStreamTrackStore {

	private static final Logger logger = LoggerFactory.getLogger(VcrStreamTrackStore.class);

	private static final String FILE_EXTENSION = ".json";

	private final Path cacheDirectory;

	private final JsonMapper jsonMapper;

	public VcrStreamTrackStore(Path cacheDirectory) {
		this(cacheDirectory, defaultJsonMapper());
	}

	public VcrStreamTrackStore(Path cacheDirectory, JsonMapper jsonMapper) {
		Assert.notNull(cacheDirectory, "cacheDirectory must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		this.cacheDirectory = cacheDirectory.toAbsolutePath().normalize();
		this.jsonMapper = jsonMapper;
	}

	/**
	 * A mapper tuned for fixture durability, identical configuration to {@code
	 * VcrTrackStore#defaultJsonMapper()}.
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
	 * Load the fixture for a hash, if one exists. A malformed fixture is reported and
	 * treated as absent rather than thrown, same reasoning as every other store in this
	 * project.
	 * @param hash the SHA-256 hex digest
	 * @return the fixture, or empty if missing or unreadable
	 */
	public Optional<VcrStreamTrack> read(String hash) {
		Path path = pathFor(hash);
		if (!Files.isRegularFile(path)) {
			logger.debug("VCR STREAM no fixture at {}", path);
			return Optional.empty();
		}
		try {
			String json = Files.readString(path, StandardCharsets.UTF_8);
			VcrStreamTrack track = this.jsonMapper.readValue(json, VcrStreamTrack.class);
			logger.debug("VCR STREAM read fixture {} ({} bytes)", path.getFileName(), json.length());
			return Optional.of(track);
		}
		catch (IOException ex) {
			logger.warn("VCR STREAM could not read fixture {}; treating as a cache miss", path, ex);
			return Optional.empty();
		}
		catch (RuntimeException ex) {
			logger.warn("VCR STREAM fixture {} is malformed; treating as a cache miss. Delete it and re-record.",
					path, ex);
			return Optional.empty();
		}
	}

	/**
	 * Persist a fixture, creating the cache directory if needed.
	 * @param track the fixture to write
	 * @throws UncheckedIOException if the fixture cannot be written
	 */
	public void write(VcrStreamTrack track) {
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

			logger.info("VCR STREAM RECORDED  [{}] -> {}", shortHash(track.hash()), path);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("VCR STREAM failed to write fixture to " + path, ex);
		}
	}

	private void moveIntoPlace(Path temp, Path target) throws IOException {
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException ex) {
			logger.debug("VCR STREAM atomic move unsupported at {}; falling back to replace", target);
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
				logger.info("VCR STREAM deleted fixture {}", path);
			}
			return deleted;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("VCR STREAM failed to delete fixture at " + path, ex);
		}
	}

	public Path getCacheDirectory() {
		return this.cacheDirectory;
	}

	public static String shortHash(String hash) {
		return (hash == null || hash.length() < 12) ? String.valueOf(hash) : hash.substring(0, 12);
	}

}
