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

import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves what {@code docs/STATUS.md} used to call "designed but unproven": that
 * {@link io.github.rifatcakir.springai.testtools.recorder.VcrScope#INSIDE_TOOL_LOOP} actually caches a
 * real, multi-turn tool-calling round trip correctly, with a real model that decides to
 * call a real {@code @Tool} method.
 *
 * <p>The scenario has two model turns — the model asks to call the tool, then, after the
 * tool's real result is fed back, produces a final answer — so {@code INSIDE_TOOL_LOOP}
 * must write <em>two</em> fixtures, not one. It also exercises the exact bug this test
 * class exists to close out: before {@code VcrTrack} schema version {@code "2"}, both
 * turns of this conversation canonicalized as if they carried no information at all,
 * because {@code AssistantMessage.getText()} is empty for a tool-calls-only turn and
 * {@code ToolResponseMessage.getText()} is always empty. A different tool call, or a
 * different tool result, would have collided on the same hash.
 *
 * <p>Tagged {@code integration} and excluded from the default {@code mvn test} run — see
 * {@link OllamaEndToEndTests} for why and how to run it explicitly.
 *
 * @author Rifat Cakir
 */
@Tag("integration")
class OllamaToolCallingEndToEndTests {

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
	 * A real {@code @Tool} method, not a mock — {@code INSIDE_TOOL_LOOP}'s whole
	 * documented promise is that this keeps executing on every iteration, replayed model
	 * turns included. The counter is how this test proves that promise still holds
	 * against a real model rather than assuming it from the design.
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
	@DisplayName("a real tool-calling round trip records two turns and replays both with zero network calls, "
			+ "while the real @Tool method still runs on every replay")
	void toolCallingRoundTripRecordsAndReplaysBothTurns() {
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
				assertThat(customizers).as("the VCR auto-configuration must have registered a customizer").isNotEmpty();
				customizers.forEach(customizer -> customizer.customize(chatClientBuilder));
				ChatClient chatClient = chatClientBuilder.build();

				String prompt = "What is the weather in Ankara? Use the tool to find out, do not guess.";

				// --- first call: two real model turns, one real tool execution, two
				// fixtures recorded ---
				String firstResponse = chatClient.prompt().user(prompt).tools(weatherTool).call().content();

				assertThat(firstResponse).isNotBlank();
				assertThat(weatherTool.invocations).as("the real tool must have actually run once on the live call")
					.hasValue(1);
				assertThat(httpRequestCount.get()).as("recording this scenario takes at least two real HTTP calls to "
						+ "Ollama — one per model turn").isGreaterThanOrEqualTo(2);
				int requestsAfterFirstCall = httpRequestCount.get();

				try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
					assertThat(fixtures).as("INSIDE_TOOL_LOOP caches one fixture per model turn — two turns here")
						.hasSize(2);
				}

				// --- second call: identical prompt, both turns must replay ---
				String secondResponse = chatClient.prompt().user(prompt).tools(weatherTool).call().content();

				assertThat(secondResponse).as("a replay must return exactly what was recorded")
					.isEqualTo(firstResponse);
				assertThat(httpRequestCount.get())
					.as("both replayed turns together must make zero additional HTTP requests to Ollama")
					.isEqualTo(requestsAfterFirstCall);
				assertThat(weatherTool.invocations)
					.as("INSIDE_TOOL_LOOP's documented promise: the real @Tool method runs again on replay, "
							+ "even though the model turns around it did not reach the network")
					.hasValue(2);
			});
	}

}
