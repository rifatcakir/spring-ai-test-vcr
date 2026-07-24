package io.github.rifatcakir.springai.testtools.recorder.e2e;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.rifatcakir.springai.testtools.assertions.VcrAssertions;
import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingModel;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import io.micrometer.observation.ObservationRegistry;

import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A2's actual proof, not just the design in {@code docs/A2-SEMANTIC-ASSERTIONS-PRD.md}:
 * against a real embedding model, a genuinely similar sentence pair passes {@code
 * isSemanticallySimilarTo} and a genuinely unrelated pair does not, and both the
 * response-text embedding and the expected-text embedding are Recorder-backed — an
 * identical second assertion makes zero additional HTTP requests, the same discipline
 * {@link OllamaEmbeddingEndToEndTests} already applies to raw {@code EmbeddingModel}
 * calls, applied here one layer up to the assertion that consumes them.
 *
 * <p>Talks to a real Ollama server using {@code llama3.2:1b} — the only model already
 * available in this environment, no new model pulled. A request counter wired into the
 * {@link RestClient} underneath {@link OllamaApi} means a replay that quietly still
 * reached the network would fail this test even if the similarity verdict happened to be
 * right.
 *
 * <p>Skipped, not failed, when Docker is unavailable; excluded from the default
 * {@code mvn test} run via {@code @Tag("integration")}. Run explicitly with
 * {@code mvn test -Pintegration-test}.
 *
 * @author Rifat Cakir
 */
@Tag("integration")
class SemanticSimilarityEndToEndTests {

	private static final String OLLAMA_MODEL_TAG = "llama3.2:1b";

	private static final DockerImageName OLLAMA_BASE_IMAGE = DockerImageName.parse("ollama/ollama:latest");

	private static final String OLLAMA_BAKED_IMAGE = "tc-ollama-llama3-2-1b-vcr-test";

	private static OllamaContainer ollama;

	@BeforeAll
	static void startOllama() throws IOException, InterruptedException {
		assumeTrue(dockerIsAvailable(), "Docker is not available — skipping the end-to-end test");

		boolean bakedImageExists = !DockerClientFactory.instance()
			.client()
			.listImagesCmd()
			.withImageNameFilter(OLLAMA_BAKED_IMAGE)
			.exec()
			.isEmpty();

		ollama = bakedImageExists
				? new OllamaContainer(DockerImageName.parse(OLLAMA_BAKED_IMAGE).asCompatibleSubstituteFor("ollama/ollama"))
				: new OllamaContainer(OLLAMA_BASE_IMAGE);
		ollama.withStartupTimeout(Duration.ofMinutes(2));
		ollama.start();

		if (!bakedImageExists) {
			ExecResult pull = ollama.execInContainer("ollama", "pull", OLLAMA_MODEL_TAG);
			if (pull.getExitCode() != 0) {
				throw new IllegalStateException(
						"Failed to pull " + OLLAMA_MODEL_TAG + " inside the Ollama container: " + pull.getStderr());
			}
			ollama.commitToImage(OLLAMA_BAKED_IMAGE);
		}
	}

	private static boolean dockerIsAvailable() {
		try {
			return DockerClientFactory.instance().isDockerAvailable();
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	@AfterAll
	static void stopOllama() {
		if (ollama != null) {
			ollama.stop();
		}
	}

	@TempDir
	java.nio.file.Path cacheDirectory;

	private static ChatResponse textResponse(String text) {
		return ChatResponse.builder().generations(of(new Generation(new AssistantMessage(text)))).build();
	}

	private EmbeddingModel vcrBackedEmbeddingModel(AtomicInteger httpRequestCount) {
		ClientHttpRequestInterceptor countingInterceptor = (request, body, execution) -> {
			httpRequestCount.incrementAndGet();
			return execution.execute(request, body);
		};

		OllamaApi ollamaApi = OllamaApi.builder()
			.baseUrl(ollama.getEndpoint())
			.restClientBuilder(RestClient.builder().requestInterceptor(countingInterceptor))
			.build();

		OllamaEmbeddingModel realEmbeddingModel = OllamaEmbeddingModel.builder()
			.ollamaApi(ollamaApi)
			.options(OllamaEmbeddingOptions.builder().model(OLLAMA_MODEL_TAG).build())
			.modelManagementOptions(ModelManagementOptions.defaults())
			.observationRegistry(ObservationRegistry.NOOP)
			.build();

		return new VcrEmbeddingModel(realEmbeddingModel, new VcrEmbeddingCacheKeyGenerator(),
				new VcrEmbeddingTrackStore(this.cacheDirectory), new VcrEmbeddingTrackMapper(),
				VcrMode.RECORD_OR_REPLAY);
	}

	/**
	 * The threshold here is explicit ({@code 0.85}), not {@code ChatResponseAssert
	 * #DEFAULT_SIMILARITY_THRESHOLD} — an empirical finding from writing this test, not a
	 * stylistic choice, documented in full in {@code docs/A2-SEMANTIC-ASSERTIONS-PRD.md}
	 * section 5: direct {@code /api/embed} calls against several sentence pairs showed a
	 * genuine paraphrase at 0.93-0.95 cosine similarity, but "unrelated" sentences at
	 * 0.66-0.72 — high enough that the 0.7 default does not reliably separate them for
	 * this model (one unrelated pair scored 0.7171, just above 0.7, and would have
	 * falsely passed). This is a known property of embeddings extracted from an LLM's
	 * hidden states rather than a model purpose-trained for embedding separation
	 * (anisotropy: such vectors cluster in a narrow cone) — {@code llama3.2:1b} is not a
	 * dedicated embedding model, it is the only model already available in this
	 * environment. 0.85 was chosen empirically, after seeing these real numbers, to
	 * cleanly separate the two groups for this specific model.
	 */
	@Test
	@DisplayName("a genuine paraphrase passes isSemanticallySimilarTo against real embeddings, and an unrelated sentence does not, at a threshold calibrated for this model")
	void realEmbeddingsDistinguishAParaphraseFromAnUnrelatedSentence() {
		AtomicInteger httpRequestCount = new AtomicInteger();
		EmbeddingModel embeddingModel = vcrBackedEmbeddingModel(httpRequestCount);
		double threshold = 0.85;

		ChatResponse response = textResponse("The capital of France is Paris.");

		VcrAssertions.assertThat(response)
			.usingEmbeddingModel(embeddingModel)
			.isSemanticallySimilarTo("Paris is the capital city of France.", threshold);

		assertThatExceptionOfType(AssertionError.class)
			.as("a genuinely unrelated sentence must not pass at this threshold")
			.isThrownBy(() -> VcrAssertions.assertThat(response)
				.usingEmbeddingModel(embeddingModel)
				.isSemanticallySimilarTo("Bananas are a good source of potassium.", threshold));

		assertThat(httpRequestCount.get()).as("both assertions above must have actually reached Ollama over HTTP")
			.isGreaterThan(0);
	}

	@Test
	@DisplayName("replaying the same assertion makes zero additional HTTP requests -- both the response text's and the expected text's embeddings are Recorder-backed")
	void secondIdenticalAssertionReplaysWithZeroNetworkCalls() {
		AtomicInteger httpRequestCount = new AtomicInteger();
		EmbeddingModel embeddingModel = vcrBackedEmbeddingModel(httpRequestCount);

		ChatResponse response = textResponse("The capital of France is Paris.");
		String expected = "Paris is the capital city of France.";

		// --- first assertion: two misses (response text + expected text), both recorded ---
		VcrAssertions.assertThat(response).usingEmbeddingModel(embeddingModel).isSemanticallySimilarTo(expected);

		assertThat(httpRequestCount.get()).as("the first assertion must have actually reached Ollama over HTTP")
			.isGreaterThan(0);
		int requestsAfterFirstAssertion = httpRequestCount.get();

		// --- second, identical assertion: both embeddings must replay ---
		VcrAssertions.assertThat(response).usingEmbeddingModel(embeddingModel).isSemanticallySimilarTo(expected);

		assertThat(httpRequestCount.get())
			.as("an identical second assertion must make zero additional HTTP requests to Ollama")
			.isEqualTo(requestsAfterFirstAssertion);
	}

}
