package io.github.rifatcakir.springai.vcr.junit;

import java.util.Optional;

import io.github.rifatcakir.springai.vcr.VcrModeOverride;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Sets and clears {@link VcrModeOverride} around each test that carries a {@link Vcr}
 * annotation, directly or via its enclosing class.
 *
 * <p>Never referenced directly — {@link Vcr} is meta-annotated with
 * {@code @ExtendWith(VcrModeExtension.class)}, so JUnit registers this automatically
 * wherever {@code @Vcr} is used.
 *
 * <p>{@link #afterEach} always clears the override, even for a test that had no {@code @Vcr}
 * of its own, so a leftover override can never survive from one test into the next.
 *
 * @author Rifat Cakira
 */
public class VcrModeExtension implements BeforeEachCallback, AfterEachCallback {

	@Override
	public void beforeEach(ExtensionContext context) {
		findVcr(context).ifPresent(vcr -> VcrModeOverride.set(vcr.mode()));
	}

	@Override
	public void afterEach(ExtensionContext context) {
		VcrModeOverride.clear();
	}

	/**
	 * A method-level {@link Vcr} takes precedence over a class-level one.
	 */
	private Optional<Vcr> findVcr(ExtensionContext context) {
		return AnnotationSupport.findAnnotation(context.getTestMethod(), Vcr.class)
			.or(() -> AnnotationSupport.findAnnotation(context.getTestClass(), Vcr.class));
	}

}
