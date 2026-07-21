package io.github.rifatcakir.springai.vcr.autoconfigure;

import io.github.rifatcakir.springai.vcr.VcrMode;
import io.github.rifatcakir.springai.vcr.VcrScope;

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

}
