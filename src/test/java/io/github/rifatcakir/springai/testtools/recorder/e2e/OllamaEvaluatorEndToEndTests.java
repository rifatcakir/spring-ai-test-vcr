package io.github.rifatcakir.springai.testtools.recorder.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.testtools.recorder.autoconfigure.SpringAiVcrAutoConfiguration;
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

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaModel;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E1's actual proof, not just the bytecode argument for it: Spring AI's own
 * {@link RelevancyEvaluator} and {@link FactCheckingEvaluator} — built from the same
 * {@link ChatClient.Builder} this library's {@code ChatClientBuilderCustomizer} already
 * attaches {@code DeterministicVcrAdvisor} to — are Recorder-backed with zero new
 * mechanism. Confirmed here the same way every other capability in this library was
 * confirmed: a real Ollama server, a real request counter wired into the
 * {@link RestClient} underneath {@link OllamaApi}, and an exact-value comparison between
 * the recorded and replayed {@link EvaluationResponse}, not just "it returned something."
 *
 * <p>No new production code exists for this — {@link Evaluator} is Spring AI's own
 * interface, and wiring it through an already-customized {@code ChatClient.Builder} is a
 * usage pattern, not a library feature this project had to build. This test is what
 * turns "should already work by construction" into "confirmed to actually work."
 *
 * <p>Skipped, not failed, when Docker is unavailable; excluded from the default
 * {@code mvn test} run via {@code @Tag("integration")}. Run explicitly with
 * {@code mvn test -Pintegration-test}.
 *
 * @author Rifat Cakir
 */
@Tag("integration")
class OllamaEvaluatorEndToEndTests {

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
	Path cacheDirectory;

	/**
	 * Builds a real {@code OllamaChatModel} (with an HTTP request counter wired into the
	 * {@link RestClient} underneath {@link OllamaApi}) and a {@link ChatClient.Builder}
	 * customized by every {@link ChatClientBuilderCustomizer} this library's
	 * auto-configuration registers — the same builder a real consuming application would
	 * hand to Spring AI's own evaluators.
	 */
	private ChatClient.Builder vcrBackedChatClientBuilder(AtomicInteger httpRequestCount) {
		ClientHttpRequestInterceptor countingInterceptor = (request, body, execution) -> {
			httpRequestCount.incrementAndGet();
			return execution.execute(request, body);
		};

		OllamaApi ollamaApi = OllamaApi.builder()
			.baseUrl(ollama.getEndpoint())
			.restClientBuilder(RestClient.builder().requestInterceptor(countingInterceptor))
			.build();

		OllamaChatOptions options = OllamaChatOptions.builder().model(OllamaModel.LLAMA3_2_1B).temperature(0.0).build();

		OllamaChatModel chatModel = OllamaChatModel.builder()
			.ollamaApi(ollamaApi)
			.options(options)
			.toolCallingManager(ToolCallingManager.builder().build())
			.modelManagementOptions(ModelManagementOptions.defaults())
			.observationRegistry(ObservationRegistry.NOOP)
			.build();

		ChatClient.Builder[] result = new ChatClient.Builder[1];
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SpringAiVcrAutoConfiguration.class))
			.withPropertyValues("spring.ai.test.vcr.enabled=true",
					"spring.ai.test.vcr.cache-directory=" + this.cacheDirectory,
					"spring.ai.test.vcr.mode=RECORD_OR_REPLAY")
			.run(context -> {
				ChatClient.Builder chatClientBuilder = ChatClient.builder(chatModel);
				List<ChatClientBuilderCustomizer> customizers = context.getBeanProvider(ChatClientBuilderCustomizer.class)
					.orderedStream()
					.toList();
				assertThat(customizers).as("the VCR auto-configuration must have registered a customizer").isNotEmpty();
				customizers.forEach(customizer -> customizer.customize(chatClientBuilder));
				result[0] = chatClientBuilder;
			});
		return result[0];
	}

	@Test
	@DisplayName("RelevancyEvaluator's internal judge call is Recorder-backed for free: first call records, identical second call replays with zero additional HTTP requests")
	void relevancyEvaluatorIsRecorderBackedWithNoNewCode() throws Exception {
		AtomicInteger httpRequestCount = new AtomicInteger();
		Evaluator relevancyEvaluator = RelevancyEvaluator.builder()
			.chatClientBuilder(vcrBackedChatClientBuilder(httpRequestCount))
			.build();

		EvaluationRequest request = new EvaluationRequest("What is the capital of France?",
				List.of(new Document("Paris is the capital and most populous city of France.")),
				"The capital of France is Paris.");

		// --- first call: miss, real judge call, record ---
		EvaluationResponse firstResponse = relevancyEvaluator.evaluate(request);

		assertThat(httpRequestCount.get()).as("the first evaluate() call must have actually reached Ollama over HTTP")
			.isGreaterThan(0);
		int requestsAfterFirstCall = httpRequestCount.get();

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures).as("exactly one fixture must have been written for this one judge call").hasSize(1);
		}

		// --- second call: identical EvaluationRequest, must be a hit ---
		EvaluationResponse secondResponse = relevancyEvaluator.evaluate(request);

		assertThat(httpRequestCount.get())
			.as("the replayed judge call must make zero additional HTTP requests to Ollama")
			.isEqualTo(requestsAfterFirstCall);
		assertThat(secondResponse.isPass()).as("a replay must return the exact recorded verdict, not a fresh one")
			.isEqualTo(firstResponse.isPass());
		assertThat(secondResponse.getScore()).isEqualTo(firstResponse.getScore());
	}

	@Test
	@DisplayName("FactCheckingEvaluator's internal judge call is Recorder-backed for free: first call records, identical second call replays with zero additional HTTP requests")
	void factCheckingEvaluatorIsRecorderBackedWithNoNewCode() throws Exception {
		AtomicInteger httpRequestCount = new AtomicInteger();
		Evaluator factCheckingEvaluator = FactCheckingEvaluator.builder(vcrBackedChatClientBuilder(httpRequestCount))
			.build();

		EvaluationRequest request = new EvaluationRequest(
				List.of(new Document("The Eiffel Tower is located in Paris, France, and was completed in 1889.")),
				"The Eiffel Tower is in Paris.");

		// --- first call: miss, real judge call, record ---
		EvaluationResponse firstResponse = factCheckingEvaluator.evaluate(request);

		assertThat(httpRequestCount.get()).as("the first evaluate() call must have actually reached Ollama over HTTP")
			.isGreaterThan(0);
		int requestsAfterFirstCall = httpRequestCount.get();

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures).as("exactly one fixture must have been written for this one judge call").hasSize(1);
		}

		// --- second call: identical EvaluationRequest, must be a hit ---
		EvaluationResponse secondResponse = factCheckingEvaluator.evaluate(request);

		assertThat(httpRequestCount.get())
			.as("the replayed judge call must make zero additional HTTP requests to Ollama")
			.isEqualTo(requestsAfterFirstCall);
		assertThat(secondResponse.isPass()).as("a replay must return the exact recorded verdict, not a fresh one")
			.isEqualTo(firstResponse.isPass());
	}

}
