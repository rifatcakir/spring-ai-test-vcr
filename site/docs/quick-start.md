# Quick Start

## Requirements

Java 21 · Spring Boot 4.0+ · Spring AI 2.0+ (Jackson 3, `tools.jackson.*`)

## Add the dependency

```xml
<dependency>
    <groupId>io.github.rifatcakir</groupId>
    <artifactId>spring-ai-test-tools</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

## The fastest path — no YAML, no Spring context

```java
@Test
void answersAQuestionAboutTheOrder() {
    ChatModel model = VcrStubs.chatModel().respondingWith("Yes, shipped yesterday.").build();
    ChatClient chatClient = ChatClient.builder(model).build();

    String answer = chatClient.prompt()
        .user("What's the status of order ORD-4471?")
        .call()
        .content();

    assertThat(answer).isEqualTo("Yes, shipped yesterday.");
}
```

That's a complete, deterministic unit test — no Docker, no network, no fixture. See
[Stubbing](stub.md) for tool calls, failures, and file-sourced responses.

## Prefer to capture a real answer once and replay it automatically forever?

`src/test/resources/application-test.yml`:

```yaml
spring:
  ai:
    test:
      vcr:
        enabled: true
        mode: RECORD_OR_REPLAY
```

That is the entire integration. The advisor attaches itself to every `ChatClient.Builder`
in the context via `ChatClientBuilderCustomizer`, so no production code changes and no
test knows the cache exists.

```java
@SpringBootTest
class OrderStatusTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Test
    void answersAQuestionAboutTheOrder() {
        ChatClient chatClient = this.chatClientBuilder.build();

        String answer = chatClient.prompt()
            .user("What's the status of order ORD-4471?")
            .call()
            .content();

        assertThat(answer).contains("shipped");
    }
}
```

The first run needs a real model reachable (Ollama, or whichever provider your
`ChatClient` is built against) and writes
`src/test/resources/llm-cache/{sha256}.json`. Commit that file. Every run after that — on
your machine, on a teammate's, in CI — replays it in milliseconds, with zero network
calls.

## In CI

```yaml
spring.ai.test.vcr.mode: REPLAY_ONLY
```

`REPLAY_ONLY` replays a known fixture and throws `VcrCacheMissException` immediately on
anything unrecorded — never silently reaching a real model in a pipeline that isn't
expecting to. See [Record & Replay](record-replay.md) for the full mode reference and
what actually busts the cache, or [Stubbing](stub.md#choosing-per-test-real-vs-stub-vs-recordreplay)
for the full per-test decision guide.
