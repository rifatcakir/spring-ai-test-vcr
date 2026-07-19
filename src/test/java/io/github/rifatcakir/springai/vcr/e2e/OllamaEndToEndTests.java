package io.github.rifatcakir.springai.vcr.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.vcr.autoconfigure.SpringAiVcrAutoConfiguration;
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
 * The proof every other test in this module takes on faith: that replaying a
 * {@link io.github.rifatcakir.springai.vcr.track.VcrTrack} fixture actually stands in for a
 * real model call, rather than merely returning a plausible-looking string.
 *
 * <p>Every other test here mocks {@code CallAdvisorChain}. This one talks to a real Ollama
 * server running in a real Docker container, with a real model, over a real HTTP client — and
 * counts requests at that HTTP client, not just at the advisor. The second identical prompt is
 * proven to reach zero network calls, not assumed to: a request counter is wired into the
 * {@link RestClient} underneath {@link OllamaApi} itself, so a cache-hit that quietly still hit
 * the network would fail this test even if the returned text happened to match.
 *
 * <p>Skipped, not failed, when Docker is unavailable — see {@link #startOllama()} — and
 * excluded from the default {@code mvn test} run via {@code @Tag("integration")} and the
 * {@code excludedGroups} in the Surefire configuration. Run explicitly with
 * {@code mvn test -Pintegration-test}.
 *
 * @author Rifat Cakira
 */
@Tag("integration")
class OllamaEndToEndTests {

	/**
	 * Smallest model available in this environment's Ollama instance at the time this test
	 * was written (1.3 GB) — chosen for pull and inference speed, not capability.
	 */
	private static final String OLLAMA_MODEL_TAG = "llama3.2:1b";

	private static final DockerImageName OLLAMA_BASE_IMAGE = DockerImageName.parse("ollama/ollama:latest");

	/**
	 * Once the model is pulled inside a fresh container, it is committed under this tag so
	 * later runs reuse it instead of re-pulling ~1.3 GB every time.
	 */
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

	/**
	 * Isolated from {@code isDockerAvailable()} being (rarely) able to throw rather than
	 * return {@code false}, since {@code assumeTrue}'s condition must not itself blow up.
	 */
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

	@Test
	@DisplayName("first call reaches the real model and records a fixture; the identical second call replays it and makes zero HTTP requests")
	void recordThenReplayWithZeroNetworkCallsOnTheHit() throws Exception {
		AtomicInteger httpRequestCount = new AtomicInteger();
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
				ChatClient chatClient = chatClientBuilder.build();

				String prompt = "Reply with exactly the single word: PONG";

				// --- first call: miss, real model, record ---
				String firstResponse = chatClient.prompt().user(prompt).call().content();

				assertThat(firstResponse).isNotBlank();
				assertThat(httpRequestCount.get()).as("the first call must have actually reached Ollama over HTTP")
					.isGreaterThan(0);
				int requestsAfterFirstCall = httpRequestCount.get();

				try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
					assertThat(fixtures).as("exactly one fixture must have been written").hasSize(1);
				}

				// --- second call: identical prompt, must be a hit ---
				String secondResponse = chatClient.prompt().user(prompt).call().content();

				assertThat(secondResponse).isEqualTo(firstResponse);
				assertThat(httpRequestCount.get())
					.as("the replayed call must make zero additional HTTP requests to Ollama")
					.isEqualTo(requestsAfterFirstCall);
			});
	}

}
