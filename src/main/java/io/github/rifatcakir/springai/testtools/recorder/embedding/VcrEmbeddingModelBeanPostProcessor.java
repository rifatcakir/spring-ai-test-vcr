package io.github.rifatcakir.springai.testtools.recorder.embedding;

import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.Assert;

/**
 * Wraps every {@link EmbeddingModel} bean in the context with {@link VcrEmbeddingModel},
 * transparently, the same "application code never has a test-only branch" outcome
 * {@code ChatClientBuilderCustomizer} achieves for chat — just via a
 * {@link BeanPostProcessor} instead, because Spring AI gives {@code EmbeddingModel} no
 * builder to customize (see {@link VcrEmbeddingModel}'s Javadoc for the full reasoning).
 *
 * @author Rifat Cakir
 */
public class VcrEmbeddingModelBeanPostProcessor implements BeanPostProcessor {

	private static final Logger logger = LoggerFactory.getLogger(VcrEmbeddingModelBeanPostProcessor.class);

	private final VcrEmbeddingCacheKeyGenerator keyGenerator;

	private final VcrEmbeddingTrackStore store;

	private final VcrEmbeddingTrackMapper mapper;

	private final VcrMode mode;

	public VcrEmbeddingModelBeanPostProcessor(VcrEmbeddingCacheKeyGenerator keyGenerator, VcrEmbeddingTrackStore store,
			VcrEmbeddingTrackMapper mapper, VcrMode mode) {
		Assert.notNull(keyGenerator, "keyGenerator must not be null");
		Assert.notNull(store, "store must not be null");
		Assert.notNull(mapper, "mapper must not be null");
		Assert.notNull(mode, "mode must not be null");
		this.keyGenerator = keyGenerator;
		this.store = store;
		this.mapper = mapper;
		this.mode = mode;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (!(bean instanceof EmbeddingModel embeddingModel) || bean instanceof VcrEmbeddingModel) {
			return bean;
		}
		logger.info("VCR EMBEDDING wrapping bean '{}' ({})", beanName, embeddingModel.getClass().getSimpleName());
		return new VcrEmbeddingModel(embeddingModel, this.keyGenerator, this.store, this.mapper, this.mode);
	}

}
