package io.github.rifatcakir.springai.testtools.assertions;

import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;

import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.AbstractStringAssert;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.embedding.EmbeddingModel;

import tools.jackson.databind.node.JsonNodeType;

/**
 * Fluent, deterministic assertions against a {@link ChatClientResponse}. Every method
 * here delegates to a {@link ChatResponseAssert} built around {@code
 * actual.chatResponse()} rather than duplicating {@link ChatResponseAssert}'s logic —
 * the two response types carry the same generation/tool-call/finish-reason data,
 * {@link ChatClientResponse} just adds advisor {@code context()} on top, which none of
 * A1's assertions currently need.
 *
 * <p>The delegate is built once and reused for every method call on a given instance
 * (see {@link #delegate()}) rather than rebuilt fresh each time — required for A2's
 * {@link #usingEmbeddingModel(EmbeddingModel)}, whose whole point is that the model it
 * stores survives to the later {@code isSemanticallySimilarTo(...)} call in the same
 * chain; a fresh delegate per call would silently lose it.
 *
 * @author Rifat Cakir
 * @see ChatResponseAssert
 */
public final class ChatClientResponseAssert extends AbstractObjectAssert<ChatClientResponseAssert, ChatClientResponse> {

	private ChatResponseAssert delegate;

	ChatClientResponseAssert(ChatClientResponse actual) {
		super(actual, ChatClientResponseAssert.class);
	}

	/**
	 * @see ChatResponseAssert#hasToolCall(String)
	 */
	public ChatClientResponseAssert hasToolCall(String name) {
		delegate().hasToolCall(name);
		return this;
	}

	/**
	 * @see ChatResponseAssert#hasToolCall(String, Map)
	 */
	public ChatClientResponseAssert hasToolCall(String name, Map<String, Object> exactArguments) {
		delegate().hasToolCall(name, exactArguments);
		return this;
	}

	/**
	 * @see ChatResponseAssert#hasToolCall(String, Consumer)
	 */
	public ChatClientResponseAssert hasToolCall(String name, Consumer<Map<String, Object>> argumentsRequirements) {
		delegate().hasToolCall(name, argumentsRequirements);
		return this;
	}

	/**
	 * @see ChatResponseAssert#hasNoToolCalls()
	 */
	public ChatClientResponseAssert hasNoToolCalls() {
		delegate().hasNoToolCalls();
		return this;
	}

	/**
	 * @see ChatResponseAssert#hasToolCallCount(int)
	 */
	public ChatClientResponseAssert hasToolCallCount(int expected) {
		delegate().hasToolCallCount(expected);
		return this;
	}

	/**
	 * @see ChatResponseAssert#hasFinishReason(String)
	 */
	public ChatClientResponseAssert hasFinishReason(String expected) {
		delegate().hasFinishReason(expected);
		return this;
	}

	/**
	 * @see ChatResponseAssert#extractingText()
	 */
	public AbstractStringAssert<?> extractingText() {
		return delegate().extractingText();
	}

	/**
	 * @see ChatResponseAssert#hasJsonField(String)
	 */
	public ChatClientResponseAssert hasJsonField(String jsonPointer) {
		delegate().hasJsonField(jsonPointer);
		return this;
	}

	/**
	 * @see ChatResponseAssert#hasJsonField(String, Object)
	 */
	public ChatClientResponseAssert hasJsonField(String jsonPointer, Object expectedValue) {
		delegate().hasJsonField(jsonPointer, expectedValue);
		return this;
	}

	/**
	 * @see ChatResponseAssert#hasJsonFieldOfType(String, JsonNodeType)
	 */
	public ChatClientResponseAssert hasJsonFieldOfType(String jsonPointer, JsonNodeType expectedType) {
		delegate().hasJsonFieldOfType(jsonPointer, expectedType);
		return this;
	}

	/**
	 * @see ChatResponseAssert#usingEmbeddingModel(EmbeddingModel)
	 */
	public ChatClientResponseAssert usingEmbeddingModel(EmbeddingModel embeddingModel) {
		delegate().usingEmbeddingModel(embeddingModel);
		return this;
	}

	/**
	 * @see ChatResponseAssert#isSemanticallySimilarTo(String)
	 */
	public ChatClientResponseAssert isSemanticallySimilarTo(String expected) {
		delegate().isSemanticallySimilarTo(expected);
		return this;
	}

	/**
	 * @see ChatResponseAssert#isSemanticallySimilarTo(String, double)
	 */
	public ChatClientResponseAssert isSemanticallySimilarTo(String expected, double threshold) {
		delegate().isSemanticallySimilarTo(expected, threshold);
		return this;
	}

	/**
	 * @see ChatResponseAssert#isSemanticallySimilarToAnyOf(Collection, double)
	 */
	public ChatClientResponseAssert isSemanticallySimilarToAnyOf(Collection<String> candidates, double threshold) {
		delegate().isSemanticallySimilarToAnyOf(candidates, threshold);
		return this;
	}

	private ChatResponseAssert delegate() {
		isNotNull();
		if (this.delegate == null) {
			ChatResponse chatResponse = this.actual.chatResponse();
			if (chatResponse == null) {
				failWithMessage("Expected a ChatClientResponse with a non-null chatResponse() but it was null");
			}
			this.delegate = new ChatResponseAssert(chatResponse);
		}
		return this.delegate;
	}

}
