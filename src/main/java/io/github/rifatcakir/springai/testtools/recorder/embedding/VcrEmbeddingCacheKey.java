package io.github.rifatcakir.springai.testtools.recorder.embedding;

/**
 * The result of hashing an {@code EmbeddingRequest} — the embedding-fixture counterpart
 * of {@code io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKey}, kept as a
 * separate type rather than reused because the two hash families are computed from
 * structurally unrelated requests and must never collide with each other by
 * construction (see {@link VcrEmbeddingCacheKeyGenerator}'s own canonical-form header).
 *
 * @param hash lowercase SHA-256 hex digest, 64 characters
 * @param canonicalRequest the exact string the digest was computed over
 * @author Rifat Cakir
 */
public record VcrEmbeddingCacheKey(String hash, String canonicalRequest) {
}
