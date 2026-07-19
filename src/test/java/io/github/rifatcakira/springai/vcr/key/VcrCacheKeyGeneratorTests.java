package io.github.rifatcakira.springai.vcr.key;

import java.util.List;

import io.github.rifatcakira.springai.vcr.RegexPromptNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key generator is the load-bearing component: if it is unstable the cache never hits,
 * and if it is insensitive the cache serves answers for prompts nobody reviewed. These
 * tests pin both directions.
 *
 * @author Rifat Cakira
 */
class VcrCacheKeyGeneratorTests {

	private final VcrCacheKeyGenerator generator = new VcrCacheKeyGenerator();

	private static Prompt prompt(String userText, ChatOptions options) {
		return new Prompt(List.of(new SystemMessage("You are terse."), new UserMessage(userText)), options);
	}

	private static ChatOptions options(String model, Double temperature) {
		return ChatOptions.builder().model(model).temperature(temperature).build();
	}

	@Test
	@DisplayName("produces a 64-character lowercase hex digest")
	void producesWellFormedDigest() {
		VcrCacheKey key = this.generator.generate(prompt("hello", options("llama3", 0.0)));

		assertThat(key.hash()).hasSize(64).matches("[0-9a-f]{64}");
		assertThat(key.canonicalRequest()).contains("model=llama3").contains("temperature=0.0");
	}

	@Test
	@DisplayName("is stable across repeated invocations")
	void isStable() {
		Prompt prompt = prompt("hello", options("llama3", 0.0));

		assertThat(this.generator.generate(prompt).hash()).isEqualTo(this.generator.generate(prompt).hash());
	}

	@Test
	@DisplayName("a single character of prompt drift busts the cache")
	void singleCharacterBustsTheCache() {
		String a = this.generator.generate(prompt("Summarise the backlog.", options("llama3", 0.0))).hash();
		String b = this.generator.generate(prompt("Summarise the backlog!", options("llama3", 0.0))).hash();

		assertThat(a).isNotEqualTo(b);
	}

	@Test
	@DisplayName("model and temperature participate in the hash")
	void optionsParticipate() {
		String base = this.generator.generate(prompt("hello", options("llama3", 0.0))).hash();

		assertThat(this.generator.generate(prompt("hello", options("gemma2", 0.0))).hash()).isNotEqualTo(base);
		assertThat(this.generator.generate(prompt("hello", options("llama3", 0.7))).hash()).isNotEqualTo(base);
	}

	@Test
	@DisplayName("message role participates in the hash")
	void roleParticipates() {
		ChatOptions options = options("llama3", 0.0);
		String asUser = this.generator.generate(new Prompt(List.of(new UserMessage("ping")), options)).hash();
		String asSystem = this.generator.generate(new Prompt(List.of(new SystemMessage("ping")), options)).hash();

		assertThat(asUser).isNotEqualTo(asSystem);
	}

	@Test
	@DisplayName("message order participates in the hash")
	void orderParticipates() {
		ChatOptions options = options("llama3", 0.0);
		String forward = this.generator
			.generate(new Prompt(List.of(new UserMessage("a"), new UserMessage("b")), options))
			.hash();
		String reversed = this.generator
			.generate(new Prompt(List.of(new UserMessage("b"), new UserMessage("a")), options))
			.hash();

		assertThat(forward).isNotEqualTo(reversed);
	}

	@Test
	@DisplayName("an embedded newline cannot forge a canonical field")
	void messageTextCannotForgeFields() {
		ChatOptions options = options("llama3", 0.0);
		String injected = this.generator
			.generate(new Prompt(List.of(new UserMessage("x\nmodel=evil")), options))
			.hash();
		String literal = this.generator.generate(new Prompt(List.of(new UserMessage("x\\nmodel=evil")), options))
			.hash();

		// Both escape to distinct canonical forms; neither introduces a second
		// "model=" line into the pre-image.
		assertThat(injected).isNotEqualTo(literal);
		assertThat(this.generator.generate(new Prompt(List.of(new UserMessage("x\nmodel=evil")), options))
			.canonicalRequest()).doesNotContain("\nmodel=evil");
	}

	@Test
	@DisplayName("a normalizer collapses volatile values so the hash stays stable")
	void normalizerStabilisesVolatileValues() {
		VcrCacheKeyGenerator normalizing = new VcrCacheKeyGenerator(List.of(RegexPromptNormalizer.ISO_DATE));
		ChatOptions options = options("llama3", 0.0);

		String monday = normalizing.generate(prompt("Today is 2026-07-19. Plan the week.", options)).hash();
		String tuesday = normalizing.generate(prompt("Today is 2026-07-20. Plan the week.", options)).hash();

		assertThat(monday).isEqualTo(tuesday);

		// Without the normalizer, the same two prompts would miss forever.
		assertThat(this.generator.generate(prompt("Today is 2026-07-19. Plan the week.", options)).hash())
			.isNotEqualTo(this.generator.generate(prompt("Today is 2026-07-20. Plan the week.", options)).hash());
	}

	@Test
	@DisplayName("normalizing dates does not make genuinely different prompts collide")
	void normalizerRemainsSensitiveElsewhere() {
		VcrCacheKeyGenerator normalizing = new VcrCacheKeyGenerator(List.of(RegexPromptNormalizer.ISO_DATE));
		ChatOptions options = options("llama3", 0.0);

		assertThat(normalizing.generate(prompt("Today is 2026-07-19. Plan the week.", options)).hash())
			.isNotEqualTo(normalizing.generate(prompt("Today is 2026-07-19. Plan the month.", options)).hash());
	}

	@Test
	@DisplayName("absent options do not blow up")
	void toleratesNullOptions() {
		VcrCacheKey key = this.generator.generate(new Prompt(List.of(new UserMessage("hello")), null));

		assertThat(key.hash()).hasSize(64);
		assertThat(key.canonicalRequest()).contains("model= null");
	}

}
