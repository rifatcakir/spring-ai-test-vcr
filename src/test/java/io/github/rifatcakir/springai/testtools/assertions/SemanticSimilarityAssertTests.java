package io.github.rifatcakir.springai.testtools.assertions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import static io.github.rifatcakir.springai.testtools.assertions.VcrAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Proves A2's core assertion logic (semantic similarity, {@code
 * docs/A2-SEMANTIC-ASSERTIONS-PRD.md}) against a hand-rolled {@link EmbeddingModel} stub
 * returning fixed, known vectors for fixed input text — no real model, no Docker, and
 * critically, hand-computable expected cosine-similarity values, so these tests check
 * exact numbers rather than "greater than zero." Same discipline as
 * {@link ChatResponseAssertTests}: every assertion type gets a passing case and a
 * failing case whose message is checked for content.
 *
 * @author Rifat Cakir
 */
class SemanticSimilarityAssertTests {

	private static ChatResponse textResponse(String text) {
		return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(text)))).build();
	}

	/**
	 * A stub {@link EmbeddingModel} that returns exactly the vector registered for a
	 * given input text, so a test can hand-compute the expected cosine similarity
	 * instead of trusting a real model's output.
	 */
	private static EmbeddingModel fixedVectors(Map<String, float[]> vectorsByText) {
		return new EmbeddingModel() {
			@Override
			public EmbeddingResponse call(EmbeddingRequest request) {
				List<Embedding> embeddings = new ArrayList<>();
				List<String> inputs = request.getInstructions();
				for (int i = 0; i < inputs.size(); i++) {
					float[] vector = vectorsByText.get(inputs.get(i));
					if (vector == null) {
						throw new IllegalStateException("no fixed vector registered for: " + inputs.get(i));
					}
					embeddings.add(new Embedding(vector, i));
				}
				return new EmbeddingResponse(embeddings);
			}

			@Override
			public float[] embed(Document document) {
				throw new UnsupportedOperationException("not needed by these tests");
			}
		};
	}

	@Nested
	class UsingEmbeddingModel {

		@Test
		@DisplayName("fails with a clear message when isSemanticallySimilarTo is called before usingEmbeddingModel")
		void failsWhenModelNeverConfigured() {
			ChatResponse response = textResponse("The capital of France is Paris.");

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).isSemanticallySimilarTo("Paris is the capital of France"))
				.withMessageContaining("usingEmbeddingModel");
		}

		@Test
		@DisplayName("accepts a plain (non-VcrEmbeddingModel) EmbeddingModel without throwing -- warns, does not refuse")
		void warnsButDoesNotRefuseANonRecorderBackedModel() {
			ChatResponse response = textResponse("hello");
			EmbeddingModel plainModel = fixedVectors(Map.of("hello", new float[] { 1f, 0f }, "hello again",
					new float[] { 1f, 0f }));

			assertThat(response).usingEmbeddingModel(plainModel).isSemanticallySimilarTo("hello again", 0.99);
		}

	}

	@Nested
	class IsSemanticallySimilarTo {

		@Test
		@DisplayName("passes when the cosine similarity is exactly 1.0 (identical vectors)")
		void passesOnIdenticalVectors() {
			ChatResponse response = textResponse("The capital of France is Paris.");
			EmbeddingModel model = fixedVectors(Map.of("The capital of France is Paris.", new float[] { 1f, 0f },
					"Paris is the capital of France", new float[] { 1f, 0f }));

			assertThat(response).usingEmbeddingModel(model).isSemanticallySimilarTo("Paris is the capital of France");
		}

		@Test
		@DisplayName("fails, with the actual similarity in the message, when below the default threshold")
		void failsBelowDefaultThreshold() {
			ChatResponse response = textResponse("apples");
			// Orthogonal vectors: cosine similarity is exactly 0.0, well below the 0.7 default.
			EmbeddingModel model = fixedVectors(
					Map.of("apples", new float[] { 1f, 0f }, "unrelated topic", new float[] { 0f, 1f }));

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).usingEmbeddingModel(model).isSemanticallySimilarTo("unrelated topic"))
				.withMessageContaining("0.7")
				.withMessageContaining("0.0");
		}

		@Test
		@DisplayName("an explicit threshold passes where the default would have failed")
		void explicitThresholdCanBeLowerThanDefault() {
			ChatResponse response = textResponse("a");
			// cos([1,1], [1,0]) = 1/sqrt(2) ~= 0.70710678 -- just under the 0.7 default in
			// spirit but let's push it clearly below with a stricter pair, then pass it
			// explicitly with a low threshold.
			EmbeddingModel model = fixedVectors(Map.of("a", new float[] { 1f, 0f }, "b", new float[] { 0.1f, 1f }));

			assertThat(response).usingEmbeddingModel(model).isSemanticallySimilarTo("b", 0.05);
		}

		@Test
		@DisplayName("an explicit threshold can also make a call fail that the default would have passed")
		void explicitThresholdCanBeStricterThanDefault() {
			ChatResponse response = textResponse("a");
			// cos([1,1], [1,0]) = 1/sqrt(2) ~= 0.7071 -- passes the 0.7 default but not a
			// stricter 0.95 threshold.
			EmbeddingModel model = fixedVectors(Map.of("a", new float[] { 1f, 1f }, "b", new float[] { 1f, 0f }));

			assertThat(response).usingEmbeddingModel(model).isSemanticallySimilarTo("b");

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).usingEmbeddingModel(model).isSemanticallySimilarTo("b", 0.95))
				.withMessageContaining("0.95");
		}

		@Test
		@DisplayName("fails with a clear message when the two texts embed to different dimensions")
		void failsOnMismatchedDimensions() {
			ChatResponse response = textResponse("a");
			EmbeddingModel model = fixedVectors(
					Map.of("a", new float[] { 1f, 0f }, "b", new float[] { 1f, 0f, 0f }));

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).usingEmbeddingModel(model).isSemanticallySimilarTo("b"))
				.withMessageContaining("different dimensions")
				.withMessageContaining("2")
				.withMessageContaining("3");
		}

		@Test
		@DisplayName("fails with a clear message when either embedding is an all-zero vector")
		void failsOnZeroVector() {
			ChatResponse response = textResponse("a");
			EmbeddingModel model = fixedVectors(Map.of("a", new float[] { 0f, 0f }, "b", new float[] { 1f, 0f }));

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).usingEmbeddingModel(model).isSemanticallySimilarTo("b"))
				.withMessageContaining("zero vector");
		}

	}

	@Nested
	class IsSemanticallySimilarToAnyOf {

		@Test
		@DisplayName("passes when at least one candidate meets the threshold")
		void passesWhenAnyCandidateMatches() {
			ChatResponse response = textResponse("a");
			EmbeddingModel model = fixedVectors(Map.of("a", new float[] { 1f, 0f }, "unrelated", new float[] { 0f, 1f },
					"matching", new float[] { 1f, 0f }));

			assertThat(response).usingEmbeddingModel(model)
				.isSemanticallySimilarToAnyOf(List.of("unrelated", "matching"), 0.7);
		}

		@Test
		@DisplayName("fails and lists every candidate's actual similarity when none match")
		void failsAndListsAllSimilaritiesWhenNoneMatch() {
			ChatResponse response = textResponse("a");
			EmbeddingModel model = fixedVectors(
					Map.of("a", new float[] { 1f, 0f }, "first", new float[] { 0f, 1f }, "second", new float[] { -1f, 0f }));

			assertThatExceptionOfType(AssertionError.class)
				.isThrownBy(() -> assertThat(response).usingEmbeddingModel(model)
					.isSemanticallySimilarToAnyOf(List.of("first", "second"), 0.7))
				.withMessageContaining("first")
				.withMessageContaining("second");
		}

	}

}
