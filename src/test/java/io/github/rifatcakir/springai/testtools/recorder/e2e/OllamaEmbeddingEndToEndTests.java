package io.github.rifatcakir.springai.testtools.recorder.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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

import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The R4 proof every unit test on {@link VcrEmbeddingModel} takes on faith: that
 * replaying a {@code VcrEmbeddingTrack} fixture actually stands in for a real embedding
 * call — same discipline as {@link OllamaEndToEndTests}, applied to
 * {@code EmbeddingModel} instead of {@code ChatClient}.
 *
 * <p>Talks to a real Ollama server in a real Docker container, over a real HTTP client,
 * using {@code llama3.2:1b} — the only model already available in this environment
 * (confirmed to answer Ollama's {@code /api/embed} endpoint before this test was
 * written, not assumed; no dedicated embedding model needed or pulled). A request
 * counter is wired into the {@link RestClient} underneath {@link OllamaApi} itself, so a
 * cache hit that quietly still reached the network would fail this test even if the
 * returned vector happened to match. The vector comparison itself is exact
 * ({@code float[]} equality, not "same length" or "not null") — the one assertion this
 * test exists specifically to make that {@link OllamaEndToEndTests} has no equivalent
 * of.
 *
 * <p>Skipped, not failed, when Docker is unavailable; excluded from the default
 * {@code mvn test} run via {@code @Tag("integration")}. Run explicitly with
 * {@code mvn test -Pintegration-test}.
 *
 * @author Rifat Cakir
 */
@Tag("integration")
class OllamaEmbeddingEndToEndTests {

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

	@Test
	@DisplayName("first call reaches the real model and records a fixture; the identical second call replays the exact vector and makes zero HTTP requests")
	void recordThenReplayWithZeroNetworkCallsAndAnExactVectorOnTheHit() throws Exception {
		AtomicInteger httpRequestCount = new AtomicInteger();
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

		VcrEmbeddingModel vcrEmbeddingModel = new VcrEmbeddingModel(realEmbeddingModel,
				new VcrEmbeddingCacheKeyGenerator(), new VcrEmbeddingTrackStore(this.cacheDirectory),
				new VcrEmbeddingTrackMapper(), VcrMode.RECORD_OR_REPLAY);

		EmbeddingRequest request = new EmbeddingRequest(List.of("hello world"),
				OllamaEmbeddingOptions.builder().model(OLLAMA_MODEL_TAG).build());

		// --- first call: miss, real model, record ---
		EmbeddingResponse firstResponse = vcrEmbeddingModel.call(request);

		float[] recordedVector = firstResponse.getResult().getOutput();
		assertThat(recordedVector).as("a real embedding call must return a non-trivial vector")
			.hasSizeGreaterThan(100);
		assertThat(httpRequestCount.get()).as("the first call must have actually reached Ollama over HTTP")
			.isGreaterThan(0);
		int requestsAfterFirstCall = httpRequestCount.get();

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures).as("exactly one fixture must have been written").hasSize(1);
		}

		// --- second call: identical request, must be a hit ---
		EmbeddingResponse secondResponse = vcrEmbeddingModel.call(request);

		assertThat(httpRequestCount.get())
			.as("the replayed call must make zero additional HTTP requests to Ollama")
			.isEqualTo(requestsAfterFirstCall);
		assertThat(secondResponse.getResult().getOutput())
			.as("the replayed vector must be exactly the recorded one, not merely the same length")
			.isEqualTo(recordedVector);
	}

}
