package io.github.rifatcakira.springai.vcr.advisor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.rifatcakira.springai.vcr.VcrCacheMissException;
import io.github.rifatcakira.springai.vcr.VcrFixtureRedactor;
import io.github.rifatcakira.springai.vcr.VcrMode;
import io.github.rifatcakira.springai.vcr.VcrScope;
import io.github.rifatcakira.springai.vcr.key.VcrCacheKey;
import io.github.rifatcakira.springai.vcr.key.VcrCacheKeyGenerator;
import io.github.rifatcakira.springai.vcr.track.VcrTrack;
import io.github.rifatcakira.springai.vcr.track.VcrTrackMapper;
import io.github.rifatcakira.springai.vcr.track.VcrTrackStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

/**
 * Intercepts blocking {@code ChatClient.call()} cycles and serves them from a local JSON
 * fixture when one exists.
 *
 * <h2>Why {@link CallAdvisor} and not {@code BaseAdvisor}</h2>
 *
 * <p>{@code BaseAdvisor} reduces boilerplate by splitting an advisor into {@code before()}
 * and {@code after()} hooks, but that shape always calls the downstream chain in between.
 * A VCR advisor's entire purpose is to <em>not</em> call it. Short-circuiting requires
 * implementing {@link CallAdvisor#adviseCall} directly and simply returning without
 * touching {@link CallAdvisorChain#nextCall}.
 *
 * <p>(For the record: {@code CallAroundAdvisor} and {@code StreamAroundAdvisor} were
 * removed in Spring AI 1.0.0-RC1, and no type named {@code AbstractAdvisor} exists in the
 * advisor API.)
 *
 * <h2>Ordering</h2>
 *
 * <p>Spring AI 2.0 lifted the tool-calling loop into the advisor chain as
 * {@code ToolCallingAdvisor}, auto-registered at {@code HIGHEST_PRECEDENCE + 300}. Sitting
 * before it caches the final answer for a whole tool round-trip; sitting after it caches
 * each individual model turn and lets real {@code @Tool} methods keep executing. See
 * {@link VcrScope}.
 *
 * <h2>Streaming</h2>
 *
 * <p>This advisor implements {@link CallAdvisor} only, so {@code .stream()} calls pass
 * through untouched and always reach the real model. Replaying a token stream
 * deterministically — chunk boundaries, timing, partial tool-call fragments — is a
 * separate design problem, and pretending to solve it with a single-chunk {@code Flux}
 * would produce fixtures that mask real streaming bugs.
 *
 * <h2>Redaction</h2>
 *
 * <p>Registered {@link VcrFixtureRedactor} instances run once per recording, after the real
 * model has answered and after the cache key has already been computed — never on a replay,
 * and never able to change the hash a fixture is filed under. See {@link VcrFixtureRedactor}
 * for why this is a separate hook from {@link io.github.rifatcakira.springai.vcr.VcrPromptNormalizer}
 * rather than reusing it.
 *
 * @author Rifat Cakira
 */
public class DeterministicVcrAdvisor implements CallAdvisor {

	/**
	 * {@code ToolCallingAdvisor}'s auto-registered order in Spring AI 2.0. Mirrored as a
	 * literal because the constant is not part of the published advisor API contract.
	 */
	public static final int TOOL_CALLING_ADVISOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 300;

	/** Outside the tool loop: runs once, wraps the entire round-trip. */
	public static final int ORDER_OUTSIDE_TOOL_LOOP = TOOL_CALLING_ADVISOR_ORDER - 50;

	/** Inside the tool loop: runs on every iteration. */
	public static final int ORDER_INSIDE_TOOL_LOOP = TOOL_CALLING_ADVISOR_ORDER + 100;

	private static final Logger logger = LoggerFactory.getLogger(DeterministicVcrAdvisor.class);

	private final VcrCacheKeyGenerator keyGenerator;

	private final VcrTrackStore store;

	private final VcrTrackMapper mapper;

	private final VcrMode mode;

	private final int order;

	private final List<VcrFixtureRedactor> redactors;

	public DeterministicVcrAdvisor(VcrCacheKeyGenerator keyGenerator, VcrTrackStore store, VcrTrackMapper mapper,
			VcrMode mode, VcrScope scope) {
		this(keyGenerator, store, mapper, mode, scope, List.of());
	}

	public DeterministicVcrAdvisor(VcrCacheKeyGenerator keyGenerator, VcrTrackStore store, VcrTrackMapper mapper,
			VcrMode mode, VcrScope scope, List<VcrFixtureRedactor> redactors) {
		this(keyGenerator, store, mapper, mode, orderFor(scope), redactors);
	}

	public DeterministicVcrAdvisor(VcrCacheKeyGenerator keyGenerator, VcrTrackStore store, VcrTrackMapper mapper,
			VcrMode mode, int order) {
		this(keyGenerator, store, mapper, mode, order, List.of());
	}

	public DeterministicVcrAdvisor(VcrCacheKeyGenerator keyGenerator, VcrTrackStore store, VcrTrackMapper mapper,
			VcrMode mode, int order, List<VcrFixtureRedactor> redactors) {
		Assert.notNull(keyGenerator, "keyGenerator must not be null");
		Assert.notNull(store, "store must not be null");
		Assert.notNull(mapper, "mapper must not be null");
		Assert.notNull(mode, "mode must not be null");
		Assert.notNull(redactors, "redactors must not be null");
		this.keyGenerator = keyGenerator;
		this.store = store;
		this.mapper = mapper;
		this.mode = mode;
		this.order = order;
		this.redactors = List.copyOf(redactors);
	}

	private static int orderFor(VcrScope scope) {
		Assert.notNull(scope, "scope must not be null");
		return (scope == VcrScope.INSIDE_TOOL_LOOP) ? ORDER_INSIDE_TOOL_LOOP : ORDER_OUTSIDE_TOOL_LOOP;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		Assert.notNull(chatClientRequest, "chatClientRequest must not be null");
		Assert.notNull(callAdvisorChain, "callAdvisorChain must not be null");

		if (this.mode == VcrMode.BYPASS) {
			logger.debug("VCR BYPASS — delegating to the real model");
			return callAdvisorChain.nextCall(chatClientRequest);
		}

		VcrCacheKey key = this.keyGenerator.generate(chatClientRequest.prompt());
		String shortHash = VcrTrackStore.shortHash(key.hash());

		if (this.mode == VcrMode.RECORD_ALWAYS) {
			logger.info("VCR RE-RECORD [{}] — mode is RECORD_ALWAYS, ignoring any existing fixture", shortHash);
			return recordAndReturn(chatClientRequest, callAdvisorChain, key);
		}

		Optional<VcrTrack> existing = this.store.read(key.hash());

		if (existing.isPresent()) {
			logger.info("VCR CACHE HIT  [{}] replaying {}", shortHash, this.store.pathFor(key.hash()).getFileName());
			return replay(chatClientRequest, existing.get());
		}

		if (this.mode == VcrMode.REPLAY_ONLY) {
			logger.error("VCR CACHE MISS [{}] in REPLAY_ONLY — refusing to call the real model", shortHash);
			throw new VcrCacheMissException(key.hash(), this.store.pathFor(key.hash()), key.canonicalRequest());
		}

		logger.info("VCR CACHE MISS [{}] invoking the real model and recording", shortHash);
		return recordAndReturn(chatClientRequest, callAdvisorChain, key);
	}

	private ChatClientResponse replay(ChatClientRequest request, VcrTrack track) {
		ChatResponse chatResponse = this.mapper.toChatResponse(track);
		// The advise-context is copied forward so downstream advisors that stashed state
		// on the way in still observe it on the way out, exactly as on a live call.
		Map<String, Object> context = new HashMap<>(request.context());
		return new ChatClientResponse(chatResponse, context);
	}

	private ChatClientResponse recordAndReturn(ChatClientRequest request, CallAdvisorChain chain, VcrCacheKey key) {
		ChatClientResponse response = chain.nextCall(request);

		ChatResponse chatResponse = response.chatResponse();
		if (chatResponse == null) {
			logger.warn("VCR not recording [{}] — the model returned a null ChatResponse",
					VcrTrackStore.shortHash(key.hash()));
			return response;
		}

		this.store.write(applyRedactors(this.mapper.toTrack(key, request.prompt(), chatResponse)));
		return response;
	}

	/**
	 * Applies every configured {@link VcrFixtureRedactor}, in registration order, to what is
	 * about to be written — and only to what is about to be written.
	 *
	 * <p>{@code track.hash()} and {@code track.schemaVersion()} are re-applied from the
	 * original, un-redacted track after every step, regardless of what a redactor returns for
	 * those two fields. This is deliberate belt-and-suspenders: rule #1 (exact SHA-256 match)
	 * cannot depend on every {@code VcrFixtureRedactor} implementation being trusted to leave
	 * the cache key alone, so a redactor is structurally unable to change it, not merely asked
	 * not to.
	 */
	private VcrTrack applyRedactors(VcrTrack track) {
		VcrTrack redacted = track;
		for (VcrFixtureRedactor redactor : this.redactors) {
			VcrTrack candidate = redactor.redact(redacted);
			Assert.notNull(candidate, "VcrFixtureRedactor must not return null");
			redacted = new VcrTrack(track.schemaVersion(), track.hash(), candidate.recordedAt(),
					candidate.canonicalRequest(), candidate.request(), candidate.response());
		}
		return redacted;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public String getName() {
		return "DeterministicVcrAdvisor";
	}

}
