package io.github.rifatcakir.springai.testtools.assertions;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Entry point for the Assertions layer: deterministic, structured checks against a
 * Spring AI response, with no model call made by the assertion itself and no dependency
 * on how the response was obtained (live call or a Recorder replay — see {@code
 * docs/VISION.md} Layer 2 for why that distinction doesn't matter here).
 *
 * <p>Two overloads exist because both are things a real caller already holds, confirmed
 * against {@code ChatClient.CallResponseSpec}: {@code .chatClientResponse()} returns a
 * {@link ChatClientResponse} (adds advisor {@code context()}), {@code .chatResponse()}
 * returns the plain {@link ChatResponse} that most of this project's own tests already
 * build directly.
 *
 * @author Rifat Cakir
 */
public final class VcrAssertions {

	private VcrAssertions() {
	}

	/**
	 * Start a fluent assertion against a {@link ChatClientResponse}, as returned by
	 * {@code ChatClient.CallResponseSpec#chatClientResponse()}.
	 * @param actual the response to assert on, live or replayed
	 * @return a fluent assertion object
	 */
	public static ChatClientResponseAssert assertThat(ChatClientResponse actual) {
		return new ChatClientResponseAssert(actual);
	}

	/**
	 * Start a fluent assertion against a {@link ChatResponse}, as returned by {@code
	 * ChatClient.CallResponseSpec#chatResponse()} or a raw {@code ChatModel#call(Prompt)}.
	 * @param actual the response to assert on, live or replayed
	 * @return a fluent assertion object
	 */
	public static ChatResponseAssert assertThat(ChatResponse actual) {
		return new ChatResponseAssert(actual);
	}

}
