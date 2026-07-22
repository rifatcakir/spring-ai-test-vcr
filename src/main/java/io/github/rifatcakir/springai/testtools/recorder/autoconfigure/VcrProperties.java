package io.github.rifatcakir.springai.testtools.recorder.autoconfigure;

import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.VcrScope;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the VCR test cache, bound from {@code spring.ai.test.vcr.*}.
 *
 * @author Rifat Cakir
 */
@ConfigurationProperties(prefix = VcrProperties.PREFIX)
public class VcrProperties {

	public static final String PREFIX = "spring.ai.test.vcr";

	/**
	 * Whether to attach the VCR advisor to ChatClient builders. Off unless explicitly
	 * enabled: a library that silently starts caching model responses is a library that
	 * silently makes a production build pass for the wrong reason.
	 */
	private boolean enabled = false;

	/**
	 * Record and replay strategy.
	 */
	private VcrMode mode = VcrMode.RECORD_OR_REPLAY;

	/**
	 * Whether the advisor sits outside or inside Spring AI's tool-calling loop.
	 */
	private VcrScope scope = VcrScope.OUTSIDE_TOOL_LOOP;

	/**
	 * Directory holding the JSON fixtures, relative to the module root. Fixtures belong
	 * in version control; commit them alongside the tests that produced them.
	 */
	private String cacheDirectory = "src/test/resources/llm-cache";

	/**
	 * Explicit advisor order. When null, derived from {@link #scope}. Set this only to
	 * interleave with other custom advisors in a specific way.
	 */
	private Integer order;

	/**
	 * {@code EmbeddingModel} interception (R4) — deliberately a separate group with its
	 * own {@code enabled} flag rather than piggy-backing on the top-level one: enabling
	 * chat caching must never silently start caching embeddings too, and vice versa.
	 */
	private Embedding embedding = new Embedding();

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public VcrMode getMode() {
		return this.mode;
	}

	public void setMode(VcrMode mode) {
		this.mode = mode;
	}

	public VcrScope getScope() {
		return this.scope;
	}

	public void setScope(VcrScope scope) {
		this.scope = scope;
	}

	public String getCacheDirectory() {
		return this.cacheDirectory;
	}

	public void setCacheDirectory(String cacheDirectory) {
		this.cacheDirectory = cacheDirectory;
	}

	public Integer getOrder() {
		return this.order;
	}

	public void setOrder(Integer order) {
		this.order = order;
	}

	public Embedding getEmbedding() {
		return this.embedding;
	}

	public void setEmbedding(Embedding embedding) {
		this.embedding = embedding;
	}

	/**
	 * Configuration for {@code EmbeddingModel} interception, bound from
	 * {@code spring.ai.test.vcr.embedding.*}.
	 */
	public static class Embedding {

		/**
		 * Whether to wrap the context's {@code EmbeddingModel} bean(s) with the VCR
		 * cache. Off unless explicitly enabled, same reasoning as the top-level
		 * {@code enabled} flag, and independent of it.
		 */
		private boolean enabled = false;

		/**
		 * Record and replay strategy for embedding calls.
		 */
		private VcrMode mode = VcrMode.RECORD_OR_REPLAY;

		/**
		 * Directory holding the embedding JSON fixtures, relative to the module root.
		 */
		private String cacheDirectory = "src/test/resources/llm-cache-embedding";

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public VcrMode getMode() {
			return this.mode;
		}

		public void setMode(VcrMode mode) {
			this.mode = mode;
		}

		public String getCacheDirectory() {
			return this.cacheDirectory;
		}

		public void setCacheDirectory(String cacheDirectory) {
			this.cacheDirectory = cacheDirectory;
		}

	}

}
