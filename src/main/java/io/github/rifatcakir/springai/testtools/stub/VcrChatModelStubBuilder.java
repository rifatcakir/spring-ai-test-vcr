package io.github.rifatcakir.springai.testtools.stub;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.Assert;

/**
 * Builds a canned {@code ChatModel} — see {@link VcrStubs} for why this exists and how it
 * differs from record/replay.
 *
 * <p>Every field is optional; {@link #build()} with nothing configured still produces a
 * valid, empty-text, {@code "STOP"}-finished response rather than {@code null} or an
 * exception, since a not-yet-configured stub is a legitimate starting point, not a misuse.
 * The built model always answers the same way, for any prompt — this is deliberate, not a
 * missing feature; see {@code docs/STUB-PRD.md}'s "Request matching" section for why a
 * per-prompt routing table is explicitly out of scope. A test that needs two different
 * answers builds two stub instances, one per scenario.
 *
 * <p>Not thread-hostile, but not built for concurrent mutation either — configure it,
 * call {@link #build()} once, then treat the builder as spent, the same convention every
 * other builder in this project follows.
 *
 * @author Rifat Cakir
 */
public final class VcrChatModelStubBuilder {

	private String text = "";

	private String finishReason;

	private String modelName = "stub";

	private int promptTokens = 0;

	private int completionTokens = 0;

	private final List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();

	private int nextToolCallId = 1;

	private RuntimeException failure;

	VcrChatModelStubBuilder() {
	}

	/**
	 * The assistant's response text. Default: {@code ""}.
	 * @param text the text a call to the built model returns
	 * @return this builder
	 */
	public VcrChatModelStubBuilder respondingWith(String text) {
		Assert.notNull(text, "text must not be null");
		this.text = text;
		return this;
	}

	/**
	 * The assistant's response text, read verbatim from a test classpath resource
	 * (e.g. {@code src/test/resources/responses/greeting.txt}, addressed here as
	 * {@code "responses/greeting.txt"}) rather than an inline Java string literal.
	 *
	 * <p>Produces exactly what {@link #respondingWith(String)} would with the file's
	 * content passed directly — this is an alternate <em>source</em> for the same
	 * {@code text} field every other builder method already composes with, never a new
	 * response shape, envelope, or schema. The file is read <strong>exactly as
	 * written</strong>: no trimming, no whitespace normalization. A trailing newline an
	 * editor added on save is part of the file's content and will be part of the
	 * returned response's text, exactly as it would be had you typed it into
	 * {@link #respondingWith(String)} yourself.
	 *
	 * <p>Resolves a classpath resource, not a filesystem path — the same resolution
	 * {@code ClassLoader.getResourceAsStream(String)} already uses for any other test
	 * resource, and deliberately the only resolution mode this method supports (see
	 * {@code docs/STUB-FILE-SOURCE-PRD.md} for why classpath-only, not a second,
	 * ambiguous filesystem-path mode). A missing or unreadable resource fails
	 * immediately, at this call site, naming the exact resource path that couldn't be
	 * found — not deferred to {@link #build()} or to the first call on the built model.
	 * @param classpathResource a classpath-relative resource path, e.g.
	 * {@code "responses/greeting.txt"}
	 * @return this builder
	 */
	public VcrChatModelStubBuilder respondingWithContentOf(String classpathResource) {
		Assert.hasText(classpathResource, "classpathResource must not be empty");
		return respondingWith(readClasspathResource(classpathResource));
	}

	private static String readClasspathResource(String classpathResource) {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		if (classLoader == null) {
			classLoader = VcrChatModelStubBuilder.class.getClassLoader();
		}
		try (InputStream in = classLoader.getResourceAsStream(classpathResource)) {
			if (in == null) {
				throw new IllegalArgumentException(
						"VcrStubs: no classpath resource found at \"" + classpathResource + "\" -- expected under "
								+ "a test resources root, e.g. src/test/resources/" + classpathResource);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("VcrStubs: failed to read classpath resource \"" + classpathResource + "\"",
					ex);
		}
	}

	/**
	 * Adds a tool call with an auto-generated id ({@code "call_1"}, {@code "call_2"}, ...
	 * per builder instance). Use {@link #withToolCall(String, String, String)} when a
	 * test needs to pin an exact id.
	 *
	 * <p>Adding at least one tool call switches the built response's default finish
	 * reason to {@code "TOOL_CALLS"} automatically, unless {@link #withFinishReason}
	 * was also called — the same behavior a real model exhibits, so a test doesn't have
	 * to spell out both facts by hand every time.
	 * @param name the tool's name
	 * @param argumentsJson the tool call's raw JSON arguments string
	 * @return this builder
	 */
	public VcrChatModelStubBuilder withToolCall(String name, String argumentsJson) {
		return withToolCall("call_" + this.nextToolCallId++, name, argumentsJson);
	}

	/**
	 * Adds a tool call with an explicit id. See {@link #withToolCall(String, String)}
	 * for the auto-id overload and the finish-reason auto-default it shares.
	 * @param id the tool call's id, exactly as it should appear in the response
	 * @param name the tool's name
	 * @param argumentsJson the tool call's raw JSON arguments string
	 * @return this builder
	 */
	public VcrChatModelStubBuilder withToolCall(String id, String name, String argumentsJson) {
		Assert.hasText(id, "id must not be empty");
		Assert.hasText(name, "name must not be empty");
		Assert.notNull(argumentsJson, "argumentsJson must not be null");
		this.toolCalls.add(new AssistantMessage.ToolCall(id, "function", name, argumentsJson));
		return this;
	}

	/**
	 * Overrides the response's finish reason. Default: {@code "STOP"}, or
	 * {@code "TOOL_CALLS"} automatically once a tool call has been added — see
	 * {@link #withToolCall(String, String)}.
	 * @param finishReason the exact finish-reason string the response should carry
	 * @return this builder
	 */
	public VcrChatModelStubBuilder withFinishReason(String finishReason) {
		Assert.hasText(finishReason, "finishReason must not be empty");
		this.finishReason = finishReason;
		return this;
	}

	/**
	 * Overrides the response metadata's model name. Default: {@code "stub"}. Cosmetic
	 * only — never participates in anything this response is used for.
	 * @param modelName the model name the response's metadata should report
	 * @return this builder
	 */
	public VcrChatModelStubBuilder withModel(String modelName) {
		Assert.hasText(modelName, "modelName must not be empty");
		this.modelName = modelName;
		return this;
	}

	/**
	 * Overrides the response's token usage. Default: {@code 0}/{@code 0} (total is the
	 * sum of the two).
	 * @param promptTokens the prompt token count to report
	 * @param completionTokens the completion token count to report
	 * @return this builder
	 */
	public VcrChatModelStubBuilder withUsage(int promptTokens, int completionTokens) {
		this.promptTokens = promptTokens;
		this.completionTokens = completionTokens;
		return this;
	}

	/**
	 * Makes the built model's {@code call(Prompt)} throw this exact exception instead of
	 * returning a response — for the timeout/refusal/malformed-response scenarios a real
	 * provider will not reliably reproduce on demand, the whole reason this class exists
	 * alongside record/replay. Wins over every other configured field: if set, the built
	 * model always throws, regardless of any response-shaping calls also present on this
	 * builder.
	 * @param exception the exact exception instance to throw on every call
	 * @return this builder
	 */
	public VcrChatModelStubBuilder failingWith(RuntimeException exception) {
		Assert.notNull(exception, "exception must not be null");
		this.failure = exception;
		return this;
	}

	/**
	 * @return a {@code ChatModel} that always answers as configured
	 */
	public ChatModel build() {
		String effectiveFinishReason = (this.finishReason != null) ? this.finishReason
				: (this.toolCalls.isEmpty() ? "STOP" : "TOOL_CALLS");
		ChatResponse response = buildResponse(effectiveFinishReason);
		return new StubChatModel(response, this.failure);
	}

	private ChatResponse buildResponse(String finishReason) {
		AssistantMessage message = this.toolCalls.isEmpty() ? new AssistantMessage(this.text)
				: AssistantMessage.builder()
					.content(this.text)
					.properties(Map.of())
					.toolCalls(List.copyOf(this.toolCalls))
					.media(List.of())
					.build();

		ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
			.finishReason(finishReason)
			.build();
		Generation generation = new Generation(message, generationMetadata);

		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
			.model(this.modelName)
			.usage(new DefaultUsage(this.promptTokens, this.completionTokens, this.promptTokens + this.completionTokens))
			.build();

		return ChatResponse.builder().generations(List.of(generation)).metadata(metadata).build();
	}

	/**
	 * The stub itself — deliberately package-private, since {@link VcrStubs} and this
	 * builder are the only sanctioned way to obtain one.
	 */
	private static final class StubChatModel implements ChatModel {

		private final ChatResponse response;

		private final RuntimeException failure;

		StubChatModel(ChatResponse response, RuntimeException failure) {
			this.response = response;
			this.failure = failure;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			Assert.notNull(prompt, "prompt must not be null");
			if (this.failure != null) {
				throw this.failure;
			}
			return this.response;
		}

	}

}
