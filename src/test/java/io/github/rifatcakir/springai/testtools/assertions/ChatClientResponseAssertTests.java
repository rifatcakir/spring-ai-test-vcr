package io.github.rifatcakir.springai.testtools.assertions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import tools.jackson.databind.node.JsonNodeType;

import static io.github.rifatcakir.springai.testtools.assertions.VcrAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Proves {@link ChatClientResponseAssert} correctly delegates to
 * {@link ChatResponseAssert} against {@code actual.chatResponse()} — every assertion
 * type's own pass/fail logic is already proven in depth by
 * {@link ChatResponseAssertTests}, so these tests only need to prove the delegation
 * itself works for each method, not re-prove every edge case a second time.
 *
 * @author Rifat Cakir
 */
class ChatClientResponseAssertTests {

	private static ChatClientResponse toolCallResponse(String name, String argumentsJson) {
		AssistantMessage message = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", name, argumentsJson)))
			.build();
		ChatResponse chatResponse = ChatResponse.builder().generations(List.of(new Generation(message))).build();
		return new ChatClientResponse(chatResponse, Map.of());
	}

	private static ChatClientResponse textResponse(String text, String finishReason) {
		ChatResponse chatResponse = ChatResponse.builder()
			.generations(List.of(
					new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder()
						.finishReason(finishReason)
						.build())))
			.build();
		return new ChatClientResponse(chatResponse, Map.of());
	}

	@Test
	@DisplayName("hasToolCall(name) delegates and passes")
	void hasToolCallDelegatesAndPasses() {
		assertThat(toolCallResponse("getOrderStatus", "{\"orderId\":\"ORD-4471\"}")).hasToolCall("getOrderStatus");
	}

	@Test
	@DisplayName("hasToolCall(name) delegates and fails with the same message ChatResponseAssert would produce")
	void hasToolCallDelegatesAndFails() {
		ChatClientResponse response = toolCallResponse("getWeather", "{}");

		assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(response).hasToolCall("getOrderStatus"))
			.withMessageContaining("getOrderStatus")
			.withMessageContaining("getWeather");
	}

	@Test
	@DisplayName("hasToolCall(name, exactArguments) delegates and passes")
	void hasToolCallWithExactArgumentsDelegates() {
		assertThat(toolCallResponse("getOrderStatus", "{\"orderId\":\"ORD-4471\"}")).hasToolCall("getOrderStatus",
				Map.of("orderId", "ORD-4471"));
	}

	@Test
	@DisplayName("hasToolCall(name, argumentsRequirements) delegates and passes")
	void hasToolCallWithConsumerDelegates() {
		assertThat(toolCallResponse("getOrderStatus", "{\"orderId\":\"ORD-4471\"}")).hasToolCall("getOrderStatus",
				args -> org.assertj.core.api.Assertions.assertThat(args).containsEntry("orderId", "ORD-4471"));
	}

	@Test
	@DisplayName("hasNoToolCalls delegates and passes")
	void hasNoToolCallsDelegates() {
		assertThat(textResponse("It is sunny.", "STOP")).hasNoToolCalls();
	}

	@Test
	@DisplayName("hasToolCallCount delegates and fails with expected vs. actual")
	void hasToolCallCountDelegatesAndFails() {
		ChatClientResponse response = toolCallResponse("getOrderStatus", "{}");

		assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(response).hasToolCallCount(2))
			.withMessageContaining("2")
			.withMessageContaining("1");
	}

	@Test
	@DisplayName("hasFinishReason delegates and passes")
	void hasFinishReasonDelegates() {
		assertThat(textResponse("It is sunny.", "STOP")).hasFinishReason("STOP");
	}

	@Test
	@DisplayName("extractingText delegates into an ordinary AssertJ string assertion")
	void extractingTextDelegates() {
		assertThat(textResponse("The status is shipped.", "STOP")).extractingText().contains("shipped");
	}

	@Test
	@DisplayName("hasJsonField delegates and passes")
	void hasJsonFieldDelegates() {
		assertThat(textResponse("{\"carrier\":\"Turkish Airlines\"}", "STOP")).hasJsonField("/carrier",
				"Turkish Airlines");
	}

	@Test
	@DisplayName("hasJsonFieldOfType delegates and fails with expected vs. actual type")
	void hasJsonFieldOfTypeDelegatesAndFails() {
		ChatClientResponse response = textResponse("{\"carrier\":\"Turkish Airlines\"}", "STOP");

		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> assertThat(response).hasJsonFieldOfType("/carrier", JsonNodeType.NUMBER))
			.withMessageContaining("NUMBER")
			.withMessageContaining("STRING");
	}

	@Test
	@DisplayName("fails with a clear message when chatResponse() itself is null")
	void failsWhenChatResponseIsNull() {
		ChatClientResponse response = new ChatClientResponse(null, Map.of());

		assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(response).hasToolCall("x"))
			.withMessageContaining("chatResponse()");
	}

}
