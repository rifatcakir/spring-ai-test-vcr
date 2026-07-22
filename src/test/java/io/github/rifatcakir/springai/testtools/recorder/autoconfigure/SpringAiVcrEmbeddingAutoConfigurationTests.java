package io.github.rifatcakir.springai.testtools.recorder.autoconfigure;

import java.nio.file.Path;

import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingModel;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingModelBeanPostProcessor;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice tests for {@link SpringAiVcrEmbeddingAutoConfiguration} — the embedding
 * counterpart of {@code SpringAiVcrAutoConfigurationTests}.
 *
 * <p>The one behaviour these tests exist specifically to prove that the chat
 * auto-configuration's own tests can't: that a real {@code EmbeddingModel} bean placed
 * in the context comes back out of it wrapped in {@link VcrEmbeddingModel} — proving the
 * {@link VcrEmbeddingModelBeanPostProcessor} actually runs through Spring's bean
 * lifecycle, not just that it behaves correctly when invoked directly (already proven in
 * {@code VcrEmbeddingModelTests}) — and that this activates fully independently of the
 * chat {@code spring.ai.test.vcr.enabled} flag, in both directions.
 *
 * @author Rifat Cakir
 */
class SpringAiVcrEmbeddingAutoConfigurationTests {

	@TempDir
	Path cacheDirectory;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(SpringAiVcrEmbeddingAutoConfiguration.class));

	private String cacheDirectoryProperty() {
		return "spring.ai.test.vcr.embedding.cache-directory=" + this.cacheDirectory;
	}

	@Test
	@DisplayName("nothing is registered when the embedding.enabled property is absent")
	void absentWhenPropertyMissing() {
		this.contextRunner.run(context -> {
			assertThat(context).doesNotHaveBean(VcrEmbeddingCacheKeyGenerator.class);
			assertThat(context).doesNotHaveBean(VcrEmbeddingTrackStore.class);
			assertThat(context).doesNotHaveBean(VcrEmbeddingTrackMapper.class);
			assertThat(context).doesNotHaveBean(VcrEmbeddingModelBeanPostProcessor.class);
		});
	}

	@Test
	@DisplayName("nothing is registered when the embedding.enabled property is explicitly false")
	void absentWhenPropertyFalse() {
		this.contextRunner.withPropertyValues("spring.ai.test.vcr.embedding.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(VcrEmbeddingModelBeanPostProcessor.class));
	}

	@Test
	@DisplayName("every collaborator is registered when embedding.enabled=true")
	void allBeansPresentWhenEnabled() {
		this.contextRunner
			.withPropertyValues("spring.ai.test.vcr.embedding.enabled=true", cacheDirectoryProperty())
			.run(context -> {
				assertThat(context).hasSingleBean(VcrEmbeddingCacheKeyGenerator.class);
				assertThat(context).hasSingleBean(VcrEmbeddingTrackMapper.class);
				assertThat(context).hasSingleBean(VcrEmbeddingTrackStore.class);
				assertThat(context).hasSingleBean(VcrEmbeddingModelBeanPostProcessor.class);
			});
	}

	@Test
	@DisplayName("a real EmbeddingModel bean in the context is wrapped by the auto-configured post-processor")
	void embeddingModelBeanIsWrapped() {
		this.contextRunner.withUserConfiguration(FakeEmbeddingModelConfiguration.class)
			.withPropertyValues("spring.ai.test.vcr.embedding.enabled=true", cacheDirectoryProperty())
			.run(context -> assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(VcrEmbeddingModel.class));
	}

	@Test
	@DisplayName("a user-supplied bean of each type wins over the auto-configured default")
	void userSuppliedBeansOverrideDefaults() {
		this.contextRunner.withUserConfiguration(CustomBeansConfiguration.class)
			.withPropertyValues("spring.ai.test.vcr.embedding.enabled=true", cacheDirectoryProperty())
			.run(context -> {
				assertThat(context.getBean(VcrEmbeddingCacheKeyGenerator.class))
					.isSameAs(CustomBeansConfiguration.KEY_GENERATOR);
				assertThat(context.getBean(VcrEmbeddingTrackMapper.class)).isSameAs(CustomBeansConfiguration.MAPPER);
				assertThat(context.getBean(VcrEmbeddingTrackStore.class)).isSameAs(CustomBeansConfiguration.STORE);
			});
	}

	@Test
	@DisplayName("enabling chat caching does not enable embedding caching")
	void chatEnabledDoesNotEnableEmbedding() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SpringAiVcrAutoConfiguration.class,
					SpringAiVcrEmbeddingAutoConfiguration.class))
			.withPropertyValues("spring.ai.test.vcr.enabled=true", "spring.ai.test.vcr.cache-directory=" + this.cacheDirectory)
			.run(context -> assertThat(context).doesNotHaveBean(VcrEmbeddingModelBeanPostProcessor.class));
	}

	@Test
	@DisplayName("enabling embedding caching does not enable chat caching")
	void embeddingEnabledDoesNotEnableChat() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SpringAiVcrAutoConfiguration.class,
					SpringAiVcrEmbeddingAutoConfiguration.class))
			.withPropertyValues("spring.ai.test.vcr.embedding.enabled=true", cacheDirectoryProperty())
			.run(context -> assertThat(context)
				.doesNotHaveBean(io.github.rifatcakir.springai.testtools.recorder.advisor.DeterministicVcrAdvisor.class));
	}

	@Configuration(proxyBeanMethods = false)
	static class FakeEmbeddingModelConfiguration {

		@Bean
		EmbeddingModel embeddingModel() {
			return new EmbeddingModel() {
				@Override
				public EmbeddingResponse call(EmbeddingRequest request) {
					throw new UnsupportedOperationException("never invoked in this test");
				}

				@Override
				public float[] embed(org.springframework.ai.document.Document document) {
					throw new UnsupportedOperationException("never invoked in this test");
				}
			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomBeansConfiguration {

		static final VcrEmbeddingCacheKeyGenerator KEY_GENERATOR = new VcrEmbeddingCacheKeyGenerator();

		static final VcrEmbeddingTrackMapper MAPPER = new VcrEmbeddingTrackMapper();

		static final VcrEmbeddingTrackStore STORE = new VcrEmbeddingTrackStore(
				Path.of(System.getProperty("java.io.tmpdir"), "vcr-embedding-custom-bean-test"));

		@Bean
		VcrEmbeddingCacheKeyGenerator vcrEmbeddingCacheKeyGenerator() {
			return KEY_GENERATOR;
		}

		@Bean
		VcrEmbeddingTrackMapper vcrEmbeddingTrackMapper() {
			return MAPPER;
		}

		@Bean
		VcrEmbeddingTrackStore vcrEmbeddingTrackStore() {
			return STORE;
		}

	}

}
