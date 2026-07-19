package io.github.rifatcakir.springai.vcr.autoconfigure;

import java.nio.file.Path;
import java.util.List;

import io.github.rifatcakir.springai.vcr.VcrFixtureRedactor;
import io.github.rifatcakir.springai.vcr.VcrMode;
import io.github.rifatcakir.springai.vcr.VcrPromptNormalizer;
import io.github.rifatcakir.springai.vcr.advisor.DeterministicVcrAdvisor;
import io.github.rifatcakir.springai.vcr.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.vcr.track.VcrTrackMapper;
import io.github.rifatcakir.springai.vcr.track.VcrTrackStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the VCR advisor onto every {@code ChatClient.Builder} in the context.
 *
 * <p>Activated by {@code spring.ai.test.vcr.enabled=true}. Nothing is registered
 * otherwise, so the artifact is inert if it accidentally reaches a compile-scope
 * classpath.
 *
 * <p>Attachment happens through {@link ChatClientBuilderCustomizer}, not
 * {@code ChatClientCustomizer} — the latter is {@code @Deprecated(forRemoval = true)} as
 * of Spring AI 2.0.0. This is the Java equivalent of VCR.py's socket-layer monkeypatching:
 * production code calls {@code ChatClient} exactly as it always does, and no test-only
 * conditional appears anywhere in it.
 *
 * @author Rifat Cakira
 */
@AutoConfiguration
@ConditionalOnClass(ChatClientBuilderCustomizer.class)
@ConditionalOnProperty(prefix = VcrProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(VcrProperties.class)
public class SpringAiVcrAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(SpringAiVcrAutoConfiguration.class);

	@Bean
	@ConditionalOnMissingBean
	public VcrCacheKeyGenerator vcrCacheKeyGenerator(List<VcrPromptNormalizer> normalizers) {
		if (!normalizers.isEmpty()) {
			logger.info("VCR using {} prompt normalizer(s): {}", normalizers.size(), normalizers);
		}
		return new VcrCacheKeyGenerator(normalizers);
	}

	@Bean
	@ConditionalOnMissingBean
	public VcrTrackMapper vcrTrackMapper(List<VcrPromptNormalizer> normalizers) {
		return new VcrTrackMapper(normalizers);
	}

	@Bean
	@ConditionalOnMissingBean
	public VcrTrackStore vcrTrackStore(VcrProperties properties) {
		Path directory = Path.of(properties.getCacheDirectory());
		logger.info("VCR cache directory: {}", directory.toAbsolutePath().normalize());
		return new VcrTrackStore(directory);
	}

	@Bean
	@ConditionalOnMissingBean
	public DeterministicVcrAdvisor deterministicVcrAdvisor(VcrCacheKeyGenerator keyGenerator, VcrTrackStore store,
			VcrTrackMapper mapper, VcrProperties properties, List<VcrFixtureRedactor> redactors) {

		VcrMode mode = properties.getMode();
		if (mode == VcrMode.RECORD_ALWAYS) {
			logger.warn("VCR mode is RECORD_ALWAYS: every call reaches the real model and "
					+ "existing fixtures will be overwritten. This mode is not intended for CI.");
		}
		if (!redactors.isEmpty()) {
			logger.info("VCR using {} fixture redactor(s): {}", redactors.size(), redactors);
		}

		DeterministicVcrAdvisor advisor = (properties.getOrder() != null)
				? new DeterministicVcrAdvisor(keyGenerator, store, mapper, mode, properties.getOrder(), redactors)
				: new DeterministicVcrAdvisor(keyGenerator, store, mapper, mode, properties.getScope(), redactors);

		logger.info("VCR enabled — mode={} scope={} order={}", mode, properties.getScope(), advisor.getOrder());
		return advisor;
	}

	@Bean
	public ChatClientBuilderCustomizer vcrChatClientBuilderCustomizer(DeterministicVcrAdvisor advisor) {
		return builder -> builder.defaultAdvisors(advisor);
	}

}
