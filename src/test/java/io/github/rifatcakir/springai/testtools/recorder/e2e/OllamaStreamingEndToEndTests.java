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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaModel;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R3's proof against a real model: that replaying a {@link
 * io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrack} fixture reproduces
 * the exact chunk sequence a live {@code ChatClient...stream()} call would have produced,
 * not merely a matching final answer.
 *
 * <p>Mirrors {@link OllamaEndToEndTests}'s discipline exactly (real Docker, real Ollama,
 * an HTTP request counter wired under {@link OllamaApi} itself), with the property unique
 * to streaming: every assertion compares the full, ordered list of chunks — {@code
 * containsExactly}, not just the concatenated text — because an aggregate-only match
 * would not catch a chunk boundary silently reshuffled or merged on replay.
 *
 * <p>Tagged {@code integration} and excluded from the default {@code mvn test} run — see
 * {@link OllamaEndToEndTests} for why and how to run it explicitly.
 *
 * @author Rifat Cakir
 */
@Tag("integration")
class OllamaStreamingEndToEndTests {

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

	private OllamaChatModel chatModel(AtomicInteger httpRequestCount) {
		ClientHttpRequestInterceptor countingInterceptor = (request, body, execution) -> {
			httpRequestCount.incrementAndGet();
			return execution.execute(request, body);
		};

		// Streaming responses (NDJSON) do not go through the blocking RestClient at all --
		// OllamaApi uses a separate reactive WebClient for `.stream()` calls, confirmed via
		// OllamaApi.Builder having both restClientBuilder(...) and webClientBuilder(...).
		// Counting only the RestClient here would silently under-count and make a
		// cache-hit-that-still-hit-the-network bug invisible to this test.
		ExchangeFilterFunction countingExchangeFilter = ExchangeFilterFunction.ofRequestProcessor(request -> {
			httpRequestCount.incrementAndGet();
			return Mono.just(request);
		});

		OllamaApi ollamaApi = OllamaApi.builder()
			.baseUrl(ollama.getEndpoint())
			.restClientBuilder(RestClient.builder().requestInterceptor(countingInterceptor))
			.webClientBuilder(WebClient.builder().filter(countingExchangeFilter))
			.build();

		OllamaChatOptions options = OllamaChatOptions.builder().model(OllamaModel.LLAMA3_2_1B).temperature(0.0).build();

		return OllamaChatModel.builder()
			.ollamaApi(ollamaApi)
			.options(options)
			.toolCallingManager(ToolCallingManager.builder().build())
			.modelManagementOptions(ModelManagementOptions.defaults())
			.observationRegistry(ObservationRegistry.NOOP)
			.build();
	}

	@Test
	@DisplayName("first stream reaches the real model and records every chunk; the identical second stream replays "
			+ "the exact chunk sequence and makes zero HTTP requests")
	void recordThenReplayWithZeroNetworkCallsOnTheHit() throws Exception {
		AtomicInteger httpRequestCount = new AtomicInteger();
		OllamaChatModel chatModel = chatModel(httpRequestCount);

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

				// --- first stream: miss, real model, record ---
				List<String> firstChunks = chatClient.prompt().user(prompt).stream().content().collectList().block();

				assertThat(firstChunks).as("a live stream from a real model must produce at least one chunk")
					.isNotEmpty();
				assertThat(httpRequestCount.get()).as("the first stream must have actually reached Ollama over HTTP")
					.isGreaterThan(0);
				int requestsAfterFirstStream = httpRequestCount.get();

				try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
					assertThat(fixtures).as("exactly one stream fixture must have been written").hasSize(1);
				}

				// --- second stream: identical prompt, must be a hit, chunk-for-chunk exact ---
				List<String> secondChunks = chatClient.prompt().user(prompt).stream().content().collectList().block();

				assertThat(secondChunks).as("replay must reproduce the exact chunk sequence, in the same order — "
						+ "not just a matching concatenated answer").containsExactlyElementsOf(firstChunks);
				assertThat(httpRequestCount.get())
					.as("the replayed stream must make zero additional HTTP requests to Ollama")
					.isEqualTo(requestsAfterFirstStream);
			});
	}

	/**
	 * A real {@code @Tool} method, not a mock — the same proof {@code
	 * OllamaToolCallingEndToEndTests} makes for the blocking path, extended to streaming:
	 * {@code INSIDE_TOOL_LOOP} keeps executing the real tool on every replayed iteration.
	 */
	static class WeatherTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(description = "Get the current weather for a named city")
		String getWeather(String city) {
			this.invocations.incrementAndGet();
			return "sunny, 22 degrees Celsius";
		}

	}

	@Test
	@DisplayName("a real streamed tool-calling round trip records and replays chunk-for-chunk, while the real "
			+ "@Tool method still runs on every replay")
	void streamedToolCallingRoundTripRecordsAndReplays() throws Exception {
		AtomicInteger httpRequestCount = new AtomicInteger();
		OllamaChatModel chatModel = chatModel(httpRequestCount);
		WeatherTool weatherTool = new WeatherTool();

		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SpringAiVcrAutoConfiguration.class))
			.withPropertyValues("spring.ai.test.vcr.enabled=true",
					"spring.ai.test.vcr.cache-directory=" + this.cacheDirectory,
					"spring.ai.test.vcr.mode=RECORD_OR_REPLAY", "spring.ai.test.vcr.scope=INSIDE_TOOL_LOOP")
			.run(context -> {
				ChatClient.Builder chatClientBuilder = ChatClient.builder(chatModel);
				List<ChatClientBuilderCustomizer> customizers = context.getBeanProvider(ChatClientBuilderCustomizer.class)
					.orderedStream()
					.toList();
				customizers.forEach(customizer -> customizer.customize(chatClientBuilder));
				ChatClient chatClient = chatClientBuilder.build();

				String prompt = "What is the weather in Ankara? Use the tool to find out, do not guess.";

				// --- first stream: at least one real model turn, one real tool execution ---
				List<ChatResponse> firstChunks = chatClient.prompt()
					.user(prompt)
					.tools(weatherTool)
					.stream()
					.chatResponse()
					.collectList()
					.block();

				assertThat(firstChunks).isNotEmpty();
				assertThat(weatherTool.invocations).as("the real tool must have actually run once on the live stream")
					.hasValue(1);
				int requestsAfterFirstStream = httpRequestCount.get();
				assertThat(requestsAfterFirstStream).isGreaterThan(0);

				try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
					assertThat(fixtures).as("INSIDE_TOOL_LOOP caches one stream fixture per model turn").isNotEmpty();
				}

				List<String> firstTexts = firstChunks.stream().map(r -> r.getResult().getOutput().getText()).toList();

				// --- second stream: identical prompt, every turn must replay chunk-for-chunk ---
				List<ChatResponse> secondChunks = chatClient.prompt()
					.user(prompt)
					.tools(weatherTool)
					.stream()
					.chatResponse()
					.collectList()
					.block();
				List<String> secondTexts = secondChunks.stream().map(r -> r.getResult().getOutput().getText()).toList();

				assertThat(secondTexts).as("every replayed turn's chunk sequence must match the recording exactly")
					.containsExactlyElementsOf(firstTexts);
				assertThat(httpRequestCount.get())
					.as("both replayed turns together must make zero additional HTTP requests to Ollama")
					.isEqualTo(requestsAfterFirstStream);
				assertThat(weatherTool.invocations)
					.as("INSIDE_TOOL_LOOP's documented promise, proven for streaming: the real @Tool method runs "
							+ "again on replay even though the model turns around it did not reach the network")
					.hasValue(2);
			});
	}

}
