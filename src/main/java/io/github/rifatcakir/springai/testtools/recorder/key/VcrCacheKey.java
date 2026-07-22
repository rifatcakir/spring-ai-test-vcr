package io.github.rifatcakir.springai.testtools.recorder.key;

/**
 * The result of hashing a request.
 *
 * <p>The {@code canonicalRequest} is carried alongside the digest on purpose. A bare hash
 * is useless when a fixture unexpectedly misses — you can see that the key changed but not
 * what changed. Keeping the pre-image lets the miss exception print it and lets a recorded
 * fixture store it, so diagnosing a miss is a text diff rather than an investigation.
 *
 * @param hash lowercase SHA-256 hex digest, 64 characters
 * @param canonicalRequest the exact string the digest was computed over
 * @author Rifat Cakir
 */
public record VcrCacheKey(String hash, String canonicalRequest) {
}
