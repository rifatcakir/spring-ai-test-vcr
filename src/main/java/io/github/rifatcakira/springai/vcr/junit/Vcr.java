package io.github.rifatcakira.springai.vcr.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.rifatcakira.springai.vcr.VcrCacheMissException;
import io.github.rifatcakira.springai.vcr.VcrMode;
import io.github.rifatcakira.springai.vcr.VcrModeOverride;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Overrides the effective {@link VcrMode} for one test method, or every test in one class,
 * regardless of what an advisor bean was otherwise configured with — restored automatically
 * once each test completes.
 *
 * <p>The escape hatch this exists for: CI runs the whole suite in
 * {@link VcrMode#REPLAY_ONLY}, so a miss throws {@link VcrCacheMissException} rather than
 * reaching a real model. That is exactly the guarantee CI needs — right up until one test
 * legitimately needs a live call anyway (a smoke test against a real provider, or an
 * assertion on something {@code VcrTrack} deliberately drops, such as a provider-native usage
 * object). {@code @Vcr} lets that one test opt out without weakening the seal for every other
 * test in the same run:
 *
 * <pre>{@code
 * @Test
 * @Vcr(mode = VcrMode.BYPASS)
 * void assertsOnProviderNativeUsage() {
 *     // reaches the real model even though the rest of this CI run is REPLAY_ONLY
 * }
 * }</pre>
 *
 * <p>Applies to whichever thread runs the annotated test method, via
 * {@link VcrModeOverride}. Blocking {@code ChatClient.call()} usage — everything this
 * library's advisor currently supports — satisfies this; code that switches threads before
 * reaching the advisor will not see the override.
 *
 * <p>A method-level {@code @Vcr} overrides a class-level one. Neither is required: a test
 * with no {@code @Vcr} anywhere runs under whatever mode the advisor was actually configured
 * with, exactly as before this annotation existed.
 *
 * @author Rifat Cakira
 * @see VcrModeExtension
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(VcrModeExtension.class)
public @interface Vcr {

	/**
	 * The mode to use for the annotated test, or every test in the annotated class, instead
	 * of whatever the advisor was otherwise configured with.
	 */
	VcrMode mode();

}
