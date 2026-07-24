package io.github.rifatcakir.springai.testtools.recorder.advisor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import io.github.rifatcakir.springai.testtools.recorder.VcrFixtureRedactor;
import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.VcrModeOverride;
import io.github.rifatcakir.springai.testtools.recorder.VcrScope;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKey;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrack;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

/**
 * Intercepts blocking {@code ChatClient.call()} cycles and streaming {@code
 * ChatClient.stream()} cycles alike, serving either from a local JSON fixture when one
 * exists.
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
 * {@link VcrScope}. The same numeric order governs this advisor's position in both the
 * call chain and the stream chain — confirmed to be exactly how {@code ToolCallingAdvisor}
 * itself positions its own {@code CallAdvisor}/{@code StreamAdvisor} identity with one
 * shared {@code getOrder()} (see {@code docs/R3-STREAMING-PRD.md} section 2), so no
 * separate streaming order/scope concept was needed.
 *
 * <h2>Streaming (R3)</h2>
 *
 * <p>Implementing {@link StreamAdvisor} in addition to {@link CallAdvisor} — on this same
 * class, mirroring {@code ToolCallingAdvisor}'s own precedent, since mode/redactor handling
 * is genuinely shared logic between the two — is what makes {@code .stream()} deterministic
 * too. {@link #adviseStream} records or replays the exact ordered chunk sequence a live
 * stream would emit, via {@link VcrStreamTrackStore}/{@link VcrStreamTrackMapper} rather
 * than {@link VcrTrackStore}/{@link VcrTrackMapper} — a stream fixture ({@link
 * VcrStreamTrack}) is a structurally different shape (a sequence, not one response), see
 * that type's own Javadoc. Streaming support is optional per advisor instance: an advisor
 * built through one of the constructors that don't accept a {@code VcrStreamTrackStore}/
 * {@code VcrStreamTrackMapper} simply passes {@code .stream()} calls straight through live,
 * exactly as every {@code DeterministicVcrAdvisor} did before this capability existed —
 * fully backward compatible, not a breaking change to existing wiring.
 *
 * <p>Uses the same {@link VcrCacheKeyGenerator} as {@link #adviseCall}, unchanged, via
 * {@code VcrCacheKeyGenerator#generateForStream} rather than {@code #generate} — identical
 * canonicalization in every field except the header line, which is what keeps a call and a
 * stream sharing the exact same prompt from ever colliding on one fixture file. See that
 * method's own Javadoc.
 *
 * <h2>Redaction</h2>
 *
 * <p>Registered {@link VcrFixtureRedactor} instances run once per recording, after the real
 * model has answered and after the cache key has already been computed — never on a replay,
 * and never able to change the hash a fixture is filed under. See {@link VcrFixtureRedactor}
 * for why this is a separate hook from {@link io.github.rifatcakir.springai.testtools.recorder.VcrPromptNormalizer}
 * rather than reusing it. Not yet wired into the streaming path (R3) — the same scope
 * decision R4's {@code VcrEmbeddingModel} already made for its own capability, revisited
 * only if a real need for streaming fixture redaction appears.
 *
 * <h2>Per-test mode override</h2>
 *
 * <p>{@link VcrModeOverride#current()} is consulted at the top of every {@link #adviseCall}/
 * {@link #adviseStream} and wins over the mode this advisor was constructed with, whenever
 * one is set. This is how
 * {@link io.github.rifatcakir.springai.testtools.recorder.junit.Vcr @Vcr} lets one test make a live call
 * while the rest of a sealed {@link VcrMode#REPLAY_ONLY} CI run stays sealed — see that
 * annotation's Javadoc.
 *
 * @author Rifat Cakir
 */
public class DeterministicVcrAdvisor implements CallAdvisor, StreamAdvisor {

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

	/**
	 * {@code null} unless one of the streaming-capable constructors was used — see
	 * {@link #adviseStream}, which passes {@code .stream()} straight through live when
	 * this is {@code null}, exactly as every {@code DeterministicVcrAdvisor} did before
	 * R3 existed.
	 */
	private final VcrStreamTrackStore streamStore;

	/** @see #streamStore */
	private final VcrStreamTrackMapper streamMapper;

	/**
	 * Construct an advisor with no redactors, letting {@code scope} decide its order.
	 * Streaming (R3) is inert on an advisor built this way — {@code .stream()} passes
	 * through live; use the {@code VcrStreamTrackStore}/{@code VcrStreamTrackMapper}
	 * overloads for streaming support.
	 *
	 * <p>The usual choice for manual (non-auto-configuration) wiring: pick this one unless
	 * you need either an explicit numeric order (see the {@code int order} overloads) or
	 * {@link VcrFixtureRedactor} beans (see the {@code List<VcrFixtureRedactor>} overloads).
	 * @param keyGenerator computes the cache key for each request
	 * @param store reads and writes fixtures on disk
	 * @param mapper translates between Spring AI's response domain and {@code VcrTrack}
	 * @param mode the record/replay strategy
	 * @param scope where this advisor sits relative to {@code ToolCallingAdvisor}; see
	 * {@link VcrScope}
	 */
	public DeterministicVcrAdvisor(VcrCacheKeyGenerator keyGenerator, VcrTrackStore store, VcrTrackMapper mapper,
			VcrMode mode, VcrScope scope) {
		this(keyGenerator, store, mapper, mode, scope, List.of());
	}

	/**
	 * Construct an advisor whose order is derived from {@code scope}, with redactors applied
	 * to every fixture before it is written. Streaming (R3) is inert on an advisor built
	 * this way. See
	 * {@link #DeterministicVcrAdvisor(VcrCacheKeyGenerator, VcrTrackStore, VcrTrackMapper, VcrMode, VcrScope)}
	 * for the other parameters; this overload adds only {@code redactors}.
	 * @param redactors applied in list order to every fixture on a cache miss, before it is
	 * written; never affects the computed hash or what a replay returns — see
	 * {@link VcrFixtureRedactor}
	 */
	public DeterministicVcrAdvisor(VcrCacheKeyGenerator keyGenerator, VcrTrackStore store, VcrTrackMapper mapper,
			VcrMode mode, VcrScope scope, List<VcrFixtureRedactor> redactors) {
		this(keyGenerator, store, mapper, mode, orderFor(scope), redactors);
	}

	/**
	 * Construct an advisor at an explicit {@code Ordered} position instead of one derived
	 * from a {@link VcrScope}. Streaming (R3) is inert on an advisor built this way. Use
	 * this only to interleave with other custom advisors at a specific position; otherwise
	 * prefer the {@code VcrScope} overloads, which stay correct relative to
	 * {@code ToolCallingAdvisor} even if its own order ever changes.
	 * @param order the exact {@link org.springframework.core.Ordered} value for this advisor
	 */
	public DeterministicVcrAdvisor(VcrCacheKeyGenerator keyGenerator, VcrTrackStore store, VcrTrackMapper mapper,
			VcrMode mode, int order) {
		this(keyGenerator, store, mapper, mode, order, List.of());
	}

	/**
	 * Construct an advisor at an explicit order, with redactors applied to every fixture
	 * before it is written. Streaming (R3) is inert on an advisor built this way — use one
	 * of the {@code VcrStreamTrackStore}/{@code VcrStreamTrackMapper} overloads for that.
	 * @param redactors applied in list order to every fixture on a cache miss, before it is
	 * written; never affects the computed hash or what a replay returns — see
	 * {@link VcrFixtureRedactor}
	 */
	public DeterministicVcrAdvisor(VcrCacheKeyGenerator keyGenerator, VcrTrackStore store, VcrTrackMapper mapper,
			VcrMode mode, int order, List<VcrFixtureRedactor> redactors) {
		this(keyGenerator, store, mapper, mode, order, redactors, null, null);
	}

	/**
	 * Like {@link #DeterministicVcrAdvisor(VcrCacheKeyGenerator, VcrTrackStore, VcrTrackMapper, VcrMode, VcrScope, List)},
	 * with streaming (R3) also enabled on this advisor.
	 * @param streamStore reads and writes stream fixtures on disk
	 * @param streamMapper translates between a collected chunk sequence and {@code
	 * VcrStreamTrack}
	 */
	public DeterministicVcrAdvisor(VcrCacheKeyGenerator keyGenerator, VcrTrackStore store, VcrTrackMapper mapper,
			VcrMode mode, VcrScope scope, List<VcrFixtureRedactor> redactors, VcrStreamTrackStore streamStore,
			VcrStreamTrackMapper streamMapper) {
		this(keyGenerator, store, mapper, mode, orderFor(scope), redactors, streamStore, streamMapper);
	}

	/**
	 * Like {@link #DeterministicVcrAdvisor(VcrCacheKeyGenerator, VcrTrackStore, VcrTrackMapper, VcrMode, int, List)},
	 * with streaming (R3) also enabled on this advisor. The most general constructor;
	 * every other overload delegates to this one.
	 * @param streamStore reads and writes stream fixtures on disk
	 * @param streamMapper translates between a collected chunk sequence and {@code
	 * VcrStreamTrack}
	 */
	public DeterministicVcrAdvisor(VcrCacheKeyGenerator keyGenerator, VcrTrackStore store, VcrTrackMapper mapper,
			VcrMode mode, int order, List<VcrFixtureRedactor> redactors, VcrStreamTrackStore streamStore,
			VcrStreamTrackMapper streamMapper) {
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
		this.streamStore = streamStore;
		this.streamMapper = streamMapper;
	}

	private static int orderFor(VcrScope scope) {
		Assert.notNull(scope, "scope must not be null");
		return (scope == VcrScope.INSIDE_TOOL_LOOP) ? ORDER_INSIDE_TOOL_LOOP : ORDER_OUTSIDE_TOOL_LOOP;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		Assert.notNull(chatClientRequest, "chatClientRequest must not be null");
		Assert.notNull(callAdvisorChain, "callAdvisorChain must not be null");

		VcrMode effectiveMode = VcrModeOverride.current().orElse(this.mode);
		if (effectiveMode != this.mode) {
			logger.info("VCR mode overridden to {} for this call (configured mode is {}) — see @Vcr", effectiveMode,
					this.mode);
		}

		if (effectiveMode == VcrMode.BYPASS) {
			logger.debug("VCR BYPASS — delegating to the real model");
			return callAdvisorChain.nextCall(chatClientRequest);
		}

		VcrCacheKey key = this.keyGenerator.generate(chatClientRequest.prompt(), chatClientRequest.context());
		String shortHash = VcrTrackStore.shortHash(key.hash());

		if (effectiveMode == VcrMode.RECORD_ALWAYS) {
			logger.info("VCR RE-RECORD [{}] — mode is RECORD_ALWAYS, ignoring any existing fixture", shortHash);
			return recordAndReturn(chatClientRequest, callAdvisorChain, key);
		}

		Optional<VcrTrack> existing = this.store.read(key.hash());

		if (existing.isPresent()) {
			logger.info("VCR CACHE HIT  [{}] replaying {}", shortHash, this.store.pathFor(key.hash()).getFileName());
			return replay(chatClientRequest, existing.get());
		}

		if (effectiveMode == VcrMode.REPLAY_ONLY) {
			logger.error("VCR CACHE MISS [{}] in REPLAY_ONLY — refusing to call the real model", shortHash);
			throw new VcrCacheMissException(key.hash(), this.store.pathFor(key.hash()), key.canonicalRequest());
		}

		logger.info("VCR CACHE MISS [{}] invoking the real model and recording", shortHash);
		return recordAndReturn(chatClientRequest, callAdvisorChain, key);
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
			StreamAdvisorChain streamAdvisorChain) {
		Assert.notNull(chatClientRequest, "chatClientRequest must not be null");
		Assert.notNull(streamAdvisorChain, "streamAdvisorChain must not be null");

		if (this.streamStore == null || this.streamMapper == null) {
			logger.debug("VCR STREAM not configured on this advisor — delegating to the real model");
			return streamAdvisorChain.nextStream(chatClientRequest);
		}

		VcrMode effectiveMode = VcrModeOverride.current().orElse(this.mode);
		if (effectiveMode != this.mode) {
			logger.info("VCR mode overridden to {} for this stream (configured mode is {}) — see @Vcr", effectiveMode,
					this.mode);
		}

		if (effectiveMode == VcrMode.BYPASS) {
			logger.debug("VCR STREAM BYPASS — delegating to the real model");
			return streamAdvisorChain.nextStream(chatClientRequest);
		}

		VcrCacheKey key = this.keyGenerator.generateForStream(chatClientRequest.prompt(), chatClientRequest.context());
		String shortHash = VcrStreamTrackStore.shortHash(key.hash());

		if (effectiveMode == VcrMode.RECORD_ALWAYS) {
			logger.info("VCR STREAM RE-RECORD [{}] — mode is RECORD_ALWAYS, ignoring any existing fixture", shortHash);
			return recordAndReturnStream(chatClientRequest, streamAdvisorChain, key);
		}

		Optional<VcrStreamTrack> existing = this.streamStore.read(key.hash());

		if (existing.isPresent()) {
			logger.info("VCR STREAM CACHE HIT  [{}] replaying {}", shortHash,
					this.streamStore.pathFor(key.hash()).getFileName());
			return replayStream(chatClientRequest, existing.get());
		}

		if (effectiveMode == VcrMode.REPLAY_ONLY) {
			logger.error("VCR STREAM CACHE MISS [{}] in REPLAY_ONLY — refusing to call the real model", shortHash);
			throw new VcrCacheMissException(key.hash(), this.streamStore.pathFor(key.hash()), key.canonicalRequest());
		}

		logger.info("VCR STREAM CACHE MISS [{}] invoking the real model and recording", shortHash);
		return recordAndReturnStream(chatClientRequest, streamAdvisorChain, key);
	}

	private Flux<ChatClientResponse> replayStream(ChatClientRequest request, VcrStreamTrack track) {
		List<ChatResponse> chunks = this.streamMapper.toChatResponseChunks(track);
		// The advise-context is copied forward once per chunk, same reasoning as
		// #replay: downstream advisors that stashed state on the way in still observe
		// it on the way out, exactly as on a live stream.
		return Flux.fromIterable(chunks).map(chunk -> new ChatClientResponse(chunk, new HashMap<>(request.context())));
	}

	/**
	 * Collects every chunk the live stream emits via {@code doOnNext} and writes the
	 * fixture once the stream completes via {@code doOnComplete} — never on error and
	 * never partially, so a cancelled or failed live stream can never produce a
	 * half-recorded fixture. No artificial delay is introduced on replay ({@link
	 * #replayStream} uses a plain {@code Flux.fromIterable}) — deterministic tests
	 * should not depend on wall-clock timing, see {@code docs/R3-STREAMING-PRD.md}
	 * section 4.
	 */
	private Flux<ChatClientResponse> recordAndReturnStream(ChatClientRequest request, StreamAdvisorChain chain,
			VcrCacheKey key) {
		List<ChatResponse> collected = Collections.synchronizedList(new ArrayList<>());
		return chain.nextStream(request).doOnNext(response -> {
			ChatResponse chatResponse = response.chatResponse();
			if (chatResponse != null) {
				collected.add(chatResponse);
			}
		}).doOnComplete(() -> {
			if (collected.isEmpty()) {
				logger.warn("VCR STREAM not recording [{}] — the live stream produced no chunks with a ChatResponse",
						VcrStreamTrackStore.shortHash(key.hash()));
				return;
			}
			this.streamStore
				.write(this.streamMapper.toTrack(key, request.prompt(), request.context(), List.copyOf(collected)));
		});
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

		this.store.write(applyRedactors(this.mapper.toTrack(key, request.prompt(), request.context(), chatResponse)));
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
