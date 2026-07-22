package io.github.rifatcakir.springai.testtools.recorder.autoconfigure;

import java.nio.file.Path;

import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingModelBeanPostProcessor;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wraps every {@code EmbeddingModel} bean in the context with the VCR cache (R4) — the
 * embedding counterpart of {@link SpringAiVcrAutoConfiguration}.
 *
 * <p>Activated independently by {@code spring.ai.test.vcr.embedding.enabled=true},
 * deliberately not gated behind the top-level {@code spring.ai.test.vcr.enabled} flag:
 * a project may want chat caching without embedding caching, or vice versa (e.g. before
 * R4 existed, or before an {@code EmbeddingModel} bean is even configured).
 *
 * <p>Unlike {@link SpringAiVcrAutoConfiguration}, which customizes a
 * {@code ChatClient.Builder}, this wraps the {@code EmbeddingModel} bean itself via a
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} — see
 * {@code VcrEmbeddingModel}'s Javadoc for why {@code EmbeddingModel} has no
 * builder-customizer equivalent to attach to instead.
 *
 * <p>Expected, benign log noise: Spring logs a {@code BeanPostProcessorChecker} warning
 * for each of this configuration's own bean dependencies ({@code
 * VcrEmbeddingCacheKeyGenerator}, {@code VcrEmbeddingTrackStore}, {@code
 * VcrEmbeddingTrackMapper}, {@code VcrProperties}), because a {@code BeanPostProcessor}
 * with constructor dependencies forces Spring to instantiate them before every
 * {@code BeanPostProcessor} is registered, making them ineligible for later AOP
 * proxying. None of these four bean types need AOP proxying, so this is inherent to
 * using a {@code BeanPostProcessor} at all (the only correct interception point here —
 * see this class's own Javadoc above), not a configuration mistake.
 *
 * @author Rifat Cakir
 */
@AutoConfiguration
@ConditionalOnClass(EmbeddingModel.class)
@ConditionalOnProperty(prefix = VcrProperties.PREFIX, name = "embedding.enabled", havingValue = "true")
@EnableConfigurationProperties(VcrProperties.class)
public class SpringAiVcrEmbeddingAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(SpringAiVcrEmbeddingAutoConfiguration.class);

	@Bean
	@ConditionalOnMissingBean
	public VcrEmbeddingCacheKeyGenerator vcrEmbeddingCacheKeyGenerator() {
		return new VcrEmbeddingCacheKeyGenerator();
	}

	@Bean
	@ConditionalOnMissingBean
	public VcrEmbeddingTrackMapper vcrEmbeddingTrackMapper() {
		return new VcrEmbeddingTrackMapper();
	}

	@Bean
	@ConditionalOnMissingBean
	public VcrEmbeddingTrackStore vcrEmbeddingTrackStore(VcrProperties properties) {
		Path directory = Path.of(properties.getEmbedding().getCacheDirectory());
		logger.info("VCR EMBEDDING cache directory: {}", directory.toAbsolutePath().normalize());
		return new VcrEmbeddingTrackStore(directory);
	}

	/**
	 * {@code static}, deliberately: a {@code BeanPostProcessor} {@code @Bean} method must
	 * be static so Spring can register it without eagerly instantiating this
	 * configuration class itself (and everything it would otherwise pull in early) —
	 * standard Spring Boot guidance for exactly this bean shape.
	 */
	@Bean
	public static VcrEmbeddingModelBeanPostProcessor vcrEmbeddingModelBeanPostProcessor(
			VcrEmbeddingCacheKeyGenerator keyGenerator, VcrEmbeddingTrackStore store, VcrEmbeddingTrackMapper mapper,
			VcrProperties properties) {
		VcrMode mode = properties.getEmbedding().getMode();
		if (mode == VcrMode.RECORD_ALWAYS) {
			logger.warn("VCR EMBEDDING mode is RECORD_ALWAYS: every call reaches the real model and "
					+ "existing fixtures will be overwritten. This mode is not intended for CI.");
		}
		logger.info("VCR EMBEDDING enabled — mode={}", mode);
		return new VcrEmbeddingModelBeanPostProcessor(keyGenerator, store, mapper, mode);
	}

}
