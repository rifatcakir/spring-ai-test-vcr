package io.github.rifatcakir.springai.testtools.assertions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import tools.jackson.databind.node.JsonNodeType;

import static io.github.rifatcakir.springai.testtools.assertions.VcrAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Proves A1's core assertion logic against hand-built {@link ChatResponse} objects — no
 * model, no Docker, no fixture — the same style already used by
 * {@code DeterministicVcrAdvisorStructuredOutputTests} and
 * {@code VcrTrackStoreRoundTripTests}. Every assertion type gets both a passing case and
 * a failing case whose message is checked for content, not just presence: an assertion
 * library's entire job is failing with a message that tells you what was actually there,
 * so a test that only proves "it throws" would miss the point.
 *
 * @author Rifat Cakir
 */
class ChatResponseAssertTests {

	private static ChatResponse textResponse(String text, String finishReason) {
		return ChatResponse.builder()
			.generations(List.of(
					new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder()
						.finishReason(finishReason)
						.build())))
			.build();
	}

	private static ChatResponse toolCallResponse(String name, String argumentsJson) {
		AssistantMessage message = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", name, argumentsJson)))
			.build();
		return ChatResponse.builder().generations(List.of(new Generation(message))).build();
	}

	private static ChatResponse multiToolCallResponse(AssistantMessage.ToolCall... calls) {
		AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
		return ChatResponse.builder().generations(List.of(new Generation(message))).build();
	}

	private static ChatResponse noToolCallResponse() {
		return textResponse("The weather is sunny.", "STOP");
	}

	@Nested
	class HasToolCallByName {

		@Test
		@DisplayName("passes when a tool call with the expected name exists")
		void passesWhenPresent() {
			ChatResponse response = toolCallResponse("getOrderStatus", "{\"orderId\":\"ORD-4471\"}");
			assertThat(response).hasToolCall("getOrderStatus");
		}

		@Test
		@DisplayName("fails and names the tool that was actually called")
		void failsAndNamesActualCall() {
			ChatResponse response = toolCallResponse("getWeather", "{\"city\":\"Ankara\"}");

			assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(response).hasToolCall("getOrderStatus"))
				.satisfies(ex -> {
					org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("getOrderStatus");
					org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("getWeather");
				});
		}

		@Test
		@DisplayName("fails and shows an empty list when no tool call happened at all")
		void failsAndShowsEmptyListWhenNoToolCalls() {
			ChatResponse response = noToolCallResponse();

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).hasToolCall("getOrderStatus"))
				.withMessageContaining("getOrderStatus")
				.withMessageContaining("[]");
		}

	}

	@Nested
	class HasToolCallWithExactArguments {

		@Test
		@DisplayName("passes when parsed arguments equal the expected map")
		void passesOnExactMatch() {
			ChatResponse response = toolCallResponse("getOrderStatus", "{\"orderId\":\"ORD-4471\"}");
			assertThat(response).hasToolCall("getOrderStatus", Map.of("orderId", "ORD-4471"));
		}

		@Test
		@DisplayName("passes when the same arguments are serialized with different key order and whitespace")
		void passesRegardlessOfSerializationDifferences() {
			// The exact bug the raw-JSON-substring approach (documented in the PRD's
			// "before" example) could not reliably catch: two JSON strings that are not
			// textually identical but parse to the same data must both satisfy an
			// exact-match assertion.
			ChatResponse response = toolCallResponse("getOrderStatus",
					"{\n  \"note\" : \"urgent\",\n  \"orderId\" : \"ORD-4471\"\n}");
			assertThat(response).hasToolCall("getOrderStatus", Map.of("orderId", "ORD-4471", "note", "urgent"));
		}

		@Test
		@DisplayName("fails and shows expected vs. actual arguments when they differ")
		void failsAndShowsBothArgumentMaps() {
			ChatResponse response = toolCallResponse("getOrderStatus", "{\"orderId\":\"ORD-9999\"}");

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).hasToolCall("getOrderStatus", Map.of("orderId", "ORD-4471")))
				.withMessageContaining("ORD-4471")
				.withMessageContaining("ORD-9999");
		}

	}

	@Nested
	class HasToolCallWithArgumentsRequirements {

		@Test
		@DisplayName("passes when the caller's own assertions on the parsed arguments succeed")
		void passesWhenConsumerAssertionsSucceed() {
			ChatResponse response = toolCallResponse("getOrderStatus",
					"{\"orderId\":\"ORD-4471\",\"priority\":\"high\"}");
			assertThat(response).hasToolCall("getOrderStatus",
					args -> org.assertj.core.api.Assertions.assertThat(args).containsEntry("orderId", "ORD-4471"));
		}

		@Test
		@DisplayName("propagates the caller's own assertion failure, unmodified")
		void propagatesConsumerAssertionFailure() {
			ChatResponse response = toolCallResponse("getOrderStatus", "{\"orderId\":\"ORD-9999\"}");

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).hasToolCall("getOrderStatus",
						args -> org.assertj.core.api.Assertions.assertThat(args).containsEntry("orderId", "ORD-4471")))
				.withMessageContaining("ORD-4471");
		}

	}

	@Nested
	class HasNoToolCalls {

		@Test
		@DisplayName("passes when the response made no tool call")
		void passesWhenNoneCalled() {
			assertThat(noToolCallResponse()).hasNoToolCalls();
		}

		@Test
		@DisplayName("fails and lists the tool calls that were found")
		void failsAndListsCalls() {
			ChatResponse response = toolCallResponse("getOrderStatus", "{}");

			assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(response).hasNoToolCalls())
				.withMessageContaining("getOrderStatus");
		}

	}

	@Nested
	class HasToolCallCount {

		@Test
		@DisplayName("passes when the count matches")
		void passesOnMatchingCount() {
			ChatResponse response = multiToolCallResponse(
					new AssistantMessage.ToolCall("call_1", "function", "getWeather", "{}"),
					new AssistantMessage.ToolCall("call_2", "function", "getOrderStatus", "{}"));
			assertThat(response).hasToolCallCount(2);
		}

		@Test
		@DisplayName("fails and shows expected vs. actual count")
		void failsAndShowsBothCounts() {
			ChatResponse response = toolCallResponse("getOrderStatus", "{}");

			assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(response).hasToolCallCount(2))
				.withMessageContaining("2")
				.withMessageContaining("1");
		}

	}

	@Nested
	class HasFinishReason {

		@Test
		@DisplayName("passes when the finish reason matches")
		void passesOnMatch() {
			assertThat(textResponse("It is sunny.", "STOP")).hasFinishReason("STOP");
		}

		@Test
		@DisplayName("fails and shows expected vs. actual finish reason")
		void failsAndShowsBoth() {
			ChatResponse response = textResponse("It is sunny.", "STOP");

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).hasFinishReason("TOOL_CALLS"))
				.withMessageContaining("TOOL_CALLS")
				.withMessageContaining("STOP");
		}

	}

	@Nested
	class ExtractingText {

		@Test
		@DisplayName("chains into an ordinary AssertJ string assertion that passes")
		void chainsAndPasses() {
			assertThat(textResponse("The status is shipped.", "STOP")).extractingText()
				.contains("shipped");
		}

		@Test
		@DisplayName("chains into an ordinary AssertJ string assertion that fails with AssertJ's own message")
		void chainsAndFails() {
			ChatResponse response = textResponse("The status is shipped.", "STOP");

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).extractingText().contains("delivered"))
				.withMessageContaining("delivered");
		}

	}

	@Nested
	class HasJsonField {

		private static final String SHIPPING_JSON = """
				{
				  "carrier": "Turkish Airlines",
				  "estimatedDays": 9,
				  "cancelled": false,
				  "note": null,
				  "tags": ["fast", "cheap"],
				  "shipping": {
				    "originCity": "Istanbul"
				  }
				}
				""";

		private ChatResponse shippingResponse() {
			return textResponse(SHIPPING_JSON, "STOP");
		}

		@Test
		@DisplayName("passes when a top-level field exists")
		void passesOnTopLevelField() {
			assertThat(shippingResponse()).hasJsonField("/carrier");
		}

		@Test
		@DisplayName("passes when a nested field exists, addressed by JSON Pointer")
		void passesOnNestedField() {
			assertThat(shippingResponse()).hasJsonField("/shipping/originCity");
		}

		@Test
		@DisplayName("fails and shows the pointer and the response text when the field is missing")
		void failsWhenMissing() {
			ChatResponse response = shippingResponse();

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).hasJsonField("/trackingNumber"))
				.withMessageContaining("/trackingNumber")
				.withMessageContaining("Turkish Airlines");
		}

		@Test
		@DisplayName("fails with a clear message when the response text is not valid JSON")
		void failsWhenNotJson() {
			ChatResponse response = textResponse("The status is shipped.", "STOP");

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).hasJsonField("/carrier"))
				.withMessageContaining("valid JSON")
				.withMessageContaining("The status is shipped.");
		}

		@Nested
		class WithExpectedValue {

			@Test
			@DisplayName("passes on an exact string match")
			void passesOnString() {
				assertThat(shippingResponse()).hasJsonField("/carrier", "Turkish Airlines");
			}

			@Test
			@DisplayName("passes on a numeric match even when the Java literal's type differs from the JSON number's")
			void passesOnNumberRegardlessOfBoxedType() {
				// estimatedDays is a JSON integer; comparing against a Java int literal
				// must not fail just because Jackson might box it as a Long internally.
				assertThat(shippingResponse()).hasJsonField("/estimatedDays", 9);
				assertThat(shippingResponse()).hasJsonField("/estimatedDays", 9.0);
			}

			@Test
			@DisplayName("passes on a boolean match")
			void passesOnBoolean() {
				assertThat(shippingResponse()).hasJsonField("/cancelled", false);
			}

			@Test
			@DisplayName("passes on a null match")
			void passesOnNull() {
				assertThat(shippingResponse()).hasJsonField("/note", null);
			}

			@Test
			@DisplayName("fails and shows expected vs. actual value on a mismatch")
			void failsAndShowsBothValues() {
				ChatResponse response = shippingResponse();

				assertThatExceptionOfType(AssertionError.class)
					.isThrownBy(() -> assertThat(response).hasJsonField("/carrier", "Lufthansa"))
					.withMessageContaining("Lufthansa")
					.withMessageContaining("Turkish Airlines");
			}

		}

		@Nested
		class OfType {

			@Test
			@DisplayName("passes for each JSON node type actually present")
			void passesForEachType() {
				ChatResponse response = shippingResponse();
				assertThat(response).hasJsonFieldOfType("/carrier", JsonNodeType.STRING);
				assertThat(response).hasJsonFieldOfType("/estimatedDays", JsonNodeType.NUMBER);
				assertThat(response).hasJsonFieldOfType("/cancelled", JsonNodeType.BOOLEAN);
				assertThat(response).hasJsonFieldOfType("/note", JsonNodeType.NULL);
				assertThat(response).hasJsonFieldOfType("/tags", JsonNodeType.ARRAY);
				assertThat(response).hasJsonFieldOfType("/shipping", JsonNodeType.OBJECT);
			}

			@Test
			@DisplayName("fails and shows expected vs. actual type on a mismatch")
			void failsAndShowsBothTypes() {
				ChatResponse response = shippingResponse();

				assertThatExceptionOfType(AssertionError.class)
					.isThrownBy(() -> assertThat(response).hasJsonFieldOfType("/carrier", JsonNodeType.NUMBER))
					.withMessageContaining("NUMBER")
					.withMessageContaining("STRING");
			}

		}

	}

	@Nested
	class EdgeCases {

		@Test
		@DisplayName("fails with a clear message when the response has no generations at all")
		void failsWhenNoGenerations() {
			ChatResponse response = ChatResponse.builder().generations(List.of()).build();

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).hasToolCall("getOrderStatus"))
				.withMessageContaining("at least one generation");
		}

	}

}
