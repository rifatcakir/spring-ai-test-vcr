package io.github.rifatcakir.springai.vcr;

import java.util.Optional;

/**
 * A thread-scoped override for the {@link VcrMode} an advisor would otherwise use.
 *
 * <p>Exists so that one test can legitimately make a live call — a smoke test against a
 * real provider, or an assertion on something {@link io.github.rifatcakir.springai.vcr.track.VcrTrack}
 * deliberately drops — while the rest of a {@link VcrMode#REPLAY_ONLY} CI run stays sealed.
 * Most users should reach for
 * {@link io.github.rifatcakir.springai.vcr.junit.Vcr @Vcr} rather than this class directly;
 * it exists as a public type only because {@code Vcr}'s extension and
 * {@code DeterministicVcrAdvisor} live in different packages and both need to reach it.
 *
 * <p>Deliberately a plain {@link ThreadLocal}, not a {@code Map} keyed by test identity or
 * anything more elaborate: the advisor is only ever invoked synchronously, on whatever
 * thread calls {@code ChatClient.call()}, so the override only needs to follow that one
 * thread for the duration of one test. Code that switches threads (an async executor, a
 * reactive chain) before reaching the advisor will not see an override set this way — a
 * pre-existing constraint of this advisor being blocking-only, not something this class
 * introduces.
 *
 * @author Rifat Cakir
 */
public final class VcrModeOverride {

	private static final ThreadLocal<VcrMode> OVERRIDE = new ThreadLocal<>();

	private VcrModeOverride() {
	}

	/**
	 * Set the mode override for the calling thread. Cleared with {@link #clear()}; a JUnit
	 * extension should always pair a {@code set} in {@code beforeEach} with a {@code clear}
	 * in {@code afterEach}, regardless of test outcome.
	 * @param mode the mode to use instead of whatever an advisor was configured with
	 */
	public static void set(VcrMode mode) {
		OVERRIDE.set(mode);
	}

	/**
	 * Remove any override for the calling thread. Safe to call even when no override is
	 * active.
	 */
	public static void clear() {
		OVERRIDE.remove();
	}

	/**
	 * The override active for the calling thread, if any.
	 * @return the overridden mode, or empty if the calling thread has none set
	 */
	public static Optional<VcrMode> current() {
		return Optional.ofNullable(OVERRIDE.get());
	}

}
