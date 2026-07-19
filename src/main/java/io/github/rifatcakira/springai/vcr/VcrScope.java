package io.github.rifatcakira.springai.vcr;

/**
 * Controls where {@code DeterministicVcrAdvisor} sits relative to Spring AI's
 * auto-registered {@code ToolCallingAdvisor}.
 *
 * <p>This has no equivalent in VCR.py, which patches the HTTP socket layer and therefore
 * always records one cassette episode per wire request. Spring AI 2.0 lifted the
 * tool-calling loop out of the chat models and into the advisor chain, so a Java VCR has
 * to make an explicit choice about which side of that loop it observes.
 *
 * @author Rifat Cakira
 */
public enum VcrScope {

	/**
	 * Place the advisor <em>before</em> {@code ToolCallingAdvisor} in the chain, so it
	 * wraps the entire tool-calling loop.
	 *
	 * <p>One fixture per logical interaction, holding the model's final answer. Fastest
	 * replay and the closest analogue to "one cassette per test".
	 *
	 * <p><strong>Trade-off:</strong> on a cache hit the loop never runs, so your
	 * {@code @Tool} methods are never invoked. A test that asserts a side effect produced
	 * by a tool (a row written, a counter incremented) will fail on replay. Use
	 * {@link #INSIDE_TOOL_LOOP} for those.
	 */
	OUTSIDE_TOOL_LOOP,

	/**
	 * Place the advisor <em>after</em> {@code ToolCallingAdvisor} in the chain, so it runs
	 * on every iteration of the loop.
	 *
	 * <p>One fixture per model turn. Tool-call requests are replayed from disk, but the
	 * real {@code @Tool} methods still execute on each iteration, so side-effect
	 * assertions keep working. Costs more files and a little more replay time.
	 */
	INSIDE_TOOL_LOOP

}
