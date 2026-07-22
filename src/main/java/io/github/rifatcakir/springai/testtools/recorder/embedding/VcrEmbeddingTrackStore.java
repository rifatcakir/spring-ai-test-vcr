package io.github.rifatcakir.springai.testtools.recorder.embedding;

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
 * Reads and writes {@link VcrEmbeddingTrack} fixtures as one JSON file per request hash
 * — the embedding counterpart of {@code
 * io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore}, kept as a
 * separate class rather than a generalized {@code VcrTrackStore<T>} — one extra type
 * parameter for exactly two current callers would be the premature abstraction, not the
 * duplication.
 *
 * <p>Same durability properties as the chat store: pretty-printed and committed (see
 * {@code docs/R4-EMBEDDING-INTERCEPTION.md} section 3 for the one place this fixture
 * type's pretty-printing is not meaningfully human-reviewable, and why that's accepted
 * rather than worked around), atomic writes (temp file then move), and a malformed
 * fixture degrades to a cache miss rather than crashing the build.
 *
 * @author Rifat Cakir
 */
public class VcrEmbeddingTrackStore {

	private static final Logger logger = LoggerFactory.getLogger(VcrEmbeddingTrackStore.class);

	private static final String FILE_EXTENSION = ".json";

	private final Path cacheDirectory;

	private final JsonMapper jsonMapper;

	public VcrEmbeddingTrackStore(Path cacheDirectory) {
		this(cacheDirectory, defaultJsonMapper());
	}

	public VcrEmbeddingTrackStore(Path cacheDirectory, JsonMapper jsonMapper) {
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
	 * treated as absent rather than thrown, same reasoning as {@code VcrTrackStore#read}.
	 * @param hash the SHA-256 hex digest
	 * @return the fixture, or empty if missing or unreadable
	 */
	public Optional<VcrEmbeddingTrack> read(String hash) {
		Path path = pathFor(hash);
		if (!Files.isRegularFile(path)) {
			logger.debug("VCR EMBEDDING no fixture at {}", path);
			return Optional.empty();
		}
		try {
			String json = Files.readString(path, StandardCharsets.UTF_8);
			VcrEmbeddingTrack track = this.jsonMapper.readValue(json, VcrEmbeddingTrack.class);
			logger.debug("VCR EMBEDDING read fixture {} ({} bytes)", path.getFileName(), json.length());
			return Optional.of(track);
		}
		catch (IOException ex) {
			logger.warn("VCR EMBEDDING could not read fixture {}; treating as a cache miss", path, ex);
			return Optional.empty();
		}
		catch (RuntimeException ex) {
			logger.warn("VCR EMBEDDING fixture {} is malformed; treating as a cache miss. Delete it and re-record.",
					path, ex);
			return Optional.empty();
		}
	}

	/**
	 * Persist a fixture, creating the cache directory if needed.
	 * @param track the fixture to write
	 * @throws UncheckedIOException if the fixture cannot be written
	 */
	public void write(VcrEmbeddingTrack track) {
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

			logger.info("VCR EMBEDDING RECORDED  [{}] -> {}", shortHash(track.hash()), path);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("VCR EMBEDDING failed to write fixture to " + path, ex);
		}
	}

	private void moveIntoPlace(Path temp, Path target) throws IOException {
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException ex) {
			logger.debug("VCR EMBEDDING atomic move unsupported at {}; falling back to replace", target);
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
				logger.info("VCR EMBEDDING deleted fixture {}", path);
			}
			return deleted;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("VCR EMBEDDING failed to delete fixture at " + path, ex);
		}
	}

	public Path getCacheDirectory() {
		return this.cacheDirectory;
	}

	public static String shortHash(String hash) {
		return (hash == null || hash.length() < 12) ? String.valueOf(hash) : hash.substring(0, 12);
	}

}
