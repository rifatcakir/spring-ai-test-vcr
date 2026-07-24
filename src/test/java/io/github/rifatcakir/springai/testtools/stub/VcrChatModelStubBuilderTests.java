package io.github.rifatcakir.springai.testtools.stub;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic, model-free, Spring-context-free tests for {@link VcrChatModelStubBuilder}
 * — every configured field verified field-by-field on the produced {@link ChatResponse},
 * not just "a response came back".
 *
 * @author Rifat Cakir
 */
class VcrChatModelStubBuilderTests {

	private static Prompt anyPrompt() {
		return new Prompt(List.of(new UserMessage("anything")));
	}

	@Test
	@DisplayName("an unconfigured stub is still a valid, empty-text, STOP-finished response, never null")
	void unconfiguredStubHasSensibleDefaults() {
		ChatModel model = VcrStubs.chatModel().build();

		ChatResponse response = model.call(anyPrompt());

		assertThat(response.getResult().getOutput().getText()).isEmpty();
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(response.getMetadata().getModel()).isEqualTo("stub");
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isZero();
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isZero();
		assertThat(response.getResult().getOutput().hasToolCalls()).isFalse();
	}

	@Test
	@DisplayName("respondingWith sets the exact response text, every call")
	void respondingWithSetsText() {
		ChatModel model = VcrStubs.chatModel().respondingWith("Yes.").build();

		assertThat(model.call(anyPrompt()).getResult().getOutput().getText()).isEqualTo("Yes.");
		assertThat(model.call(anyPrompt()).getResult().getOutput().getText())
			.as("the same stub answers identically on every call, any prompt")
			.isEqualTo("Yes.");
	}

	@Test
	@DisplayName("withToolCall (2-arg) auto-generates sequential ids and auto-defaults the finish reason to TOOL_CALLS")
	void withToolCallAutoIdAndFinishReason() {
		ChatModel model = VcrStubs.chatModel()
			.withToolCall("getWeather", "{\"city\":\"Ankara\"}")
			.withToolCall("getOrderStatus", "{\"orderId\":\"ORD-1\"}")
			.build();

		ChatResponse response = model.call(anyPrompt());
		List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();

		assertThat(toolCalls).hasSize(2);
		assertThat(toolCalls.get(0).id()).isEqualTo("call_1");
		assertThat(toolCalls.get(0).name()).isEqualTo("getWeather");
		assertThat(toolCalls.get(0).arguments()).isEqualTo("{\"city\":\"Ankara\"}");
		assertThat(toolCalls.get(1).id()).isEqualTo("call_2");
		assertThat(toolCalls.get(1).name()).isEqualTo("getOrderStatus");
		assertThat(response.getResult().getMetadata().getFinishReason())
			.as("a tool call present with no explicit withFinishReason must auto-default to TOOL_CALLS")
			.isEqualTo("TOOL_CALLS");
	}

	@Test
	@DisplayName("withToolCall (3-arg) lets a test pin an exact id")
	void withToolCallExplicitId() {
		ChatModel model = VcrStubs.chatModel().withToolCall("call_pinned", "getWeather", "{}").build();

		AssistantMessage.ToolCall toolCall = model.call(anyPrompt()).getResult().getOutput().getToolCalls().get(0);

		assertThat(toolCall.id()).isEqualTo("call_pinned");
	}

	@Test
	@DisplayName("withFinishReason overrides the TOOL_CALLS auto-default when a tool call is also present")
	void explicitFinishReasonWinsOverToolCallAutoDefault() {
		ChatModel model = VcrStubs.chatModel()
			.withToolCall("getWeather", "{}")
			.withFinishReason("length")
			.build();

		assertThat(model.call(anyPrompt()).getResult().getMetadata().getFinishReason()).isEqualTo("length");
	}

	@Test
	@DisplayName("withFinishReason alone (no tool call) sets the finish reason directly, e.g. a truncation scenario")
	void explicitFinishReasonWithoutToolCall() {
		ChatModel model = VcrStubs.chatModel().respondingWith("The answer is ").withFinishReason("length").build();

		ChatResponse response = model.call(anyPrompt());

		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("length");
		assertThat(response.getResult().getOutput().getText()).isEqualTo("The answer is ");
	}

	@Test
	@DisplayName("withModel overrides the reported model name")
	void withModelOverridesModelName() {
		ChatModel model = VcrStubs.chatModel().withModel("gpt-4o-mini").build();

		assertThat(model.call(anyPrompt()).getMetadata().getModel()).isEqualTo("gpt-4o-mini");
	}

	@Test
	@DisplayName("withUsage overrides prompt/completion tokens and total is their sum")
	void withUsageOverridesTokenCounts() {
		ChatModel model = VcrStubs.chatModel().withUsage(42, 7).build();

		ChatResponse response = model.call(anyPrompt());

		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(42);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(7);
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(49);
	}

	@Test
	@DisplayName("failingWith makes every call throw the exact exception instance, never a response")
	void failingWithThrowsExactException() {
		RuntimeException timeout = new RuntimeException("timeout");
		ChatModel model = VcrStubs.chatModel().failingWith(timeout).build();

		assertThatThrownBy(() -> model.call(anyPrompt())).isSameAs(timeout);
		assertThatThrownBy(() -> model.call(anyPrompt())).as("failure persists across repeated calls")
			.isSameAs(timeout);
	}

	@Test
	@DisplayName("failingWith wins over any response-shaping calls also configured on the same builder")
	void failingWithWinsOverResponseShaping() {
		RuntimeException failure = new IllegalStateException("refused");
		ChatModel model = VcrStubs.chatModel()
			.respondingWith("this text is never returned")
			.withToolCall("getWeather", "{}")
			.failingWith(failure)
			.build();

		assertThatThrownBy(() -> model.call(anyPrompt())).isSameAs(failure);
	}

	@Test
	@DisplayName("call(null) rejects with a clear message rather than a NullPointerException deep in Spring AI")
	void callRejectsNullPrompt() {
		ChatModel model = VcrStubs.chatModel().build();

		assertThatThrownBy(() -> model.call((Prompt) null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("prompt");
	}

	@Test
	@DisplayName("respondingWithContentOf reads a classpath resource's exact content as the response text")
	void respondingWithContentOfReadsClasspathResource() {
		ChatModel model = VcrStubs.chatModel().respondingWithContentOf("stub-responses/greeting.txt").build();

		assertThat(model.call(anyPrompt()).getResult().getOutput().getText())
			.isEqualTo("Yes, I can help with that. What's the order number?");
	}

	@Test
	@DisplayName("respondingWithContentOf is verbatim -- a trailing newline in the file is not trimmed")
	void respondingWithContentOfDoesNotTrimTrailingNewline() {
		ChatModel model = VcrStubs.chatModel()
			.respondingWithContentOf("stub-responses/with-trailing-newline.txt")
			.build();

		assertThat(model.call(anyPrompt()).getResult().getOutput().getText()).isEqualTo("Yes.\n");
	}

	@Test
	@DisplayName("respondingWithContentOf composes with withToolCall/withFinishReason exactly like respondingWith does")
	void respondingWithContentOfComposesWithOtherBuilderMethods() {
		ChatModel model = VcrStubs.chatModel()
			.respondingWithContentOf("stub-responses/refund-approved.json")
			.withToolCall("processRefund", "{\"refundId\":\"REF-9981\"}")
			.build();

		ChatResponse response = model.call(anyPrompt());

		assertThat(response.getResult().getOutput().getText())
			.isEqualTo("{\"status\":\"approved\",\"refundId\":\"REF-9981\",\"amount\":42.50}");
		assertThat(response.getResult().getOutput().getToolCalls()).singleElement()
			.satisfies(call -> assertThat(call.name()).isEqualTo("processRefund"));
		assertThat(response.getResult().getMetadata().getFinishReason())
			.as("the tool-call auto-default applies the same way regardless of where the text came from")
			.isEqualTo("TOOL_CALLS");
	}

	@Test
	@DisplayName("respondingWithContentOf fails immediately, at the call site, when the classpath resource is missing")
	void respondingWithContentOfFailsFastOnMissingResource() {
		VcrChatModelStubBuilder builder = VcrStubs.chatModel();

		assertThatThrownBy(() -> builder.respondingWithContentOf("stub-responses/does-not-exist.txt"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("stub-responses/does-not-exist.txt");
	}

	@Test
	@DisplayName("respondingWithContentOf rejects a blank resource path")
	void respondingWithContentOfRejectsBlankPath() {
		VcrChatModelStubBuilder builder = VcrStubs.chatModel();

		assertThatThrownBy(() -> builder.respondingWithContentOf("  ")).isInstanceOf(IllegalArgumentException.class);
	}

}
