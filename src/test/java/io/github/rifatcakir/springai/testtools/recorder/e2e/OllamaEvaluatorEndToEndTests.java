package io.github.rifatcakir.springai.testtools.recorder.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
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
 * <p>Also settles the "recursion" worry {@code docs/BRAINSTORM.md} names for any
 * judge-call mechanism: a recorded verdict must not be frozen forever against a
 * response that has since changed. {@code
 * relevancyEvaluatorChangedResponseForcesAFreshJudgeCall}/{@code
 * factCheckingEvaluatorChangedClaimForcesAFreshJudgeCall} confirm, by counting real HTTP
 * requests and counting real fixture files (not by reading the prompt template's source
 * and assuming), that changing the judged response/claim produces a different canonical
 * request and therefore a fresh judge call — the judge prompt genuinely embeds the
 * output under judgment, so it cannot go stale the way a hash-insensitive design would.
 *
 * <p>Also proves E2's core claim (see {@code docs/E2-EVALUATION-MODES-PRD.md}): the same
 * evaluator construction pattern runs in either of two modes purely by which {@link
 * VcrMode} the underlying advisor was built with — {@code
 * bypassModeAlwaysReachesTheModelRegardlessOfAnExistingFixture} confirms {@code BYPASS}
 * reaches the real model on every call even when a matching fixture already exists on
 * disk, the live drift/quality-check path this project's mode system already provides
 * with no new mechanism.
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
	 * hand to Spring AI's own evaluators. Defaults to {@link VcrMode#RECORD_OR_REPLAY}.
	 */
	private ChatClient.Builder vcrBackedChatClientBuilder(AtomicInteger httpRequestCount) {
		return vcrBackedChatClientBuilder(httpRequestCount, VcrMode.RECORD_OR_REPLAY);
	}

	/**
	 * Like {@link #vcrBackedChatClientBuilder(AtomicInteger)}, with an explicit
	 * {@link VcrMode} — this is E2's whole point: the exact same evaluator construction
	 * pattern, the only difference being which mode the underlying advisor was built
	 * with (deterministic replay for CI, or {@link VcrMode#BYPASS}/{@code RECORD_ALWAYS}
	 * for a live drift/quality check).
	 */
	private ChatClient.Builder vcrBackedChatClientBuilder(AtomicInteger httpRequestCount, VcrMode mode) {
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
					"spring.ai.test.vcr.cache-directory=" + this.cacheDirectory, "spring.ai.test.vcr.mode=" + mode)
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

	/**
	 * The "recursion" worry named in {@code docs/BRAINSTORM.md}: a judge's verdict must
	 * not be frozen forever against a response that has since changed. Because
	 * {@code RelevancyEvaluator}'s judge prompt is rendered with {@code
	 * EvaluationRequest#getResponseContent()} spliced directly into the message text
	 * this library hashes, a genuinely different response being judged produces a
	 * genuinely different canonical request — proven here by asserting on the actual
	 * network traffic, not by trusting the prompt template's source: both calls must
	 * reach Ollama, and two separate fixture files must exist, not one shared between
	 * two different judged outputs.
	 */
	@Test
	@DisplayName("RelevancyEvaluator: changing the judged response busts the cache and forces a fresh judge call — the fixture is not frozen against a stale answer")
	void relevancyEvaluatorChangedResponseForcesAFreshJudgeCall() throws Exception {
		AtomicInteger httpRequestCount = new AtomicInteger();
		Evaluator relevancyEvaluator = RelevancyEvaluator.builder()
			.chatClientBuilder(vcrBackedChatClientBuilder(httpRequestCount))
			.build();

		List<Document> context = List.of(new Document("Paris is the capital and most populous city of France."));
		String query = "What is the capital of France?";

		EvaluationRequest originalRequest = new EvaluationRequest(query, context, "The capital of France is Paris.");
		relevancyEvaluator.evaluate(originalRequest);

		assertThat(httpRequestCount.get()).as("the first evaluate() call must have actually reached Ollama over HTTP")
			.isGreaterThan(0);
		int requestsAfterFirstCall = httpRequestCount.get();

		// A different judged response, same query and context — must not replay the
		// first fixture, since the judge is being asked to evaluate different content.
		EvaluationRequest changedRequest = new EvaluationRequest(query, context,
				"The capital of France is Berlin, a city on the Rhine.");
		relevancyEvaluator.evaluate(changedRequest);

		assertThat(httpRequestCount.get())
			.as("a genuinely different judged response must reach the model again, not replay the first verdict")
			.isGreaterThan(requestsAfterFirstCall);

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures).as("two different judged responses must produce two separate fixtures, never one shared between them")
				.hasSize(2);
		}
	}

	/**
	 * Same "recursion" proof as {@link #relevancyEvaluatorChangedResponseForcesAFreshJudgeCall()},
	 * for {@link FactCheckingEvaluator} — there, {@code EvaluationRequest#getResponseContent()}
	 * is the "claim" being fact-checked, so a changed claim must equally force a fresh
	 * judge call rather than replaying a verdict recorded for a different claim.
	 */
	@Test
	@DisplayName("FactCheckingEvaluator: changing the judged claim busts the cache and forces a fresh judge call — the fixture is not frozen against a stale claim")
	void factCheckingEvaluatorChangedClaimForcesAFreshJudgeCall() throws Exception {
		AtomicInteger httpRequestCount = new AtomicInteger();
		Evaluator factCheckingEvaluator = FactCheckingEvaluator.builder(vcrBackedChatClientBuilder(httpRequestCount))
			.build();

		List<Document> document = List
			.of(new Document("The Eiffel Tower is located in Paris, France, and was completed in 1889."));

		EvaluationRequest originalRequest = new EvaluationRequest(document, "The Eiffel Tower is in Paris.");
		factCheckingEvaluator.evaluate(originalRequest);

		assertThat(httpRequestCount.get()).as("the first evaluate() call must have actually reached Ollama over HTTP")
			.isGreaterThan(0);
		int requestsAfterFirstCall = httpRequestCount.get();

		// A different claim being fact-checked against the same document — must not
		// replay the first fixture, since a materially different claim is under test.
		EvaluationRequest changedRequest = new EvaluationRequest(document, "The Eiffel Tower is in London.");
		factCheckingEvaluator.evaluate(changedRequest);

		assertThat(httpRequestCount.get())
			.as("a genuinely different claim must reach the model again, not replay the first verdict")
			.isGreaterThan(requestsAfterFirstCall);

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures).as("two different claims must produce two separate fixtures, never one shared between them")
				.hasSize(2);
		}
	}

	/**
	 * E2's core claim, proven rather than argued: the exact same evaluator construction
	 * pattern runs in two modes, and which one is active is entirely a property of the
	 * {@link VcrMode} the underlying advisor was built with — {@code
	 * docs/E2-EVALUATION-MODES-PRD.md} section 2. A fixture is recorded first (as any
	 * CI-facing {@code REPLAY_ONLY} run would already have committed), then a second,
	 * independent evaluator is built against the *same* cache directory and the *same*
	 * {@code EvaluationRequest} — same hash, same fixture file sitting right there — but
	 * with {@link VcrMode#BYPASS} instead. Every {@code evaluate()} call still reaches
	 * Ollama: {@code DeterministicVcrAdvisor}'s own {@code BYPASS} branch returns before
	 * ever computing a hash or touching the store, so a live drift/quality check can run
	 * against a real model without needing to delete or ignore any committed fixture
	 * first.
	 */
	@Test
	@DisplayName("BYPASS mode always reaches the real model, even when a matching fixture already exists -- the live drift-check path never replays")
	void bypassModeAlwaysReachesTheModelRegardlessOfAnExistingFixture() throws Exception {
		EvaluationRequest request = new EvaluationRequest("What is the capital of France?",
				List.of(new Document("Paris is the capital and most populous city of France.")),
				"The capital of France is Paris.");

		// --- first, record a fixture the ordinary (REPLAY_ONLY-eligible) way ---
		AtomicInteger recordingHttpRequestCount = new AtomicInteger();
		Evaluator recordingEvaluator = RelevancyEvaluator.builder()
			.chatClientBuilder(vcrBackedChatClientBuilder(recordingHttpRequestCount, VcrMode.RECORD_OR_REPLAY))
			.build();
		recordingEvaluator.evaluate(request);

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures).as("the fixture this BYPASS test is about to ignore must actually exist first")
				.hasSize(1);
		}

		// --- now build a fresh evaluator against the same cache directory, BYPASS mode ---
		AtomicInteger bypassHttpRequestCount = new AtomicInteger();
		Evaluator bypassEvaluator = RelevancyEvaluator.builder()
			.chatClientBuilder(vcrBackedChatClientBuilder(bypassHttpRequestCount, VcrMode.BYPASS))
			.build();

		bypassEvaluator.evaluate(request);
		assertThat(bypassHttpRequestCount.get())
			.as("BYPASS must reach the model on the first call despite a matching fixture already on disk")
			.isGreaterThan(0);
		int requestsAfterFirstBypassCall = bypassHttpRequestCount.get();

		bypassEvaluator.evaluate(request);
		assertThat(bypassHttpRequestCount.get())
			.as("BYPASS must reach the model again on a second, identical call -- it never replays, ever")
			.isGreaterThan(requestsAfterFirstBypassCall);

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures).as("BYPASS must not write a new fixture either -- the directory still has only the one")
				.hasSize(1);
		}
	}

}
