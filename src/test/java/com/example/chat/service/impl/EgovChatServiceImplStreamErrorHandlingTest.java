package com.example.chat.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link EgovChatServiceImpl#applyRetryAndErrorHandling(Flux, String, String)}가 스트리밍 중
 * 오류를 자동 재시도 없이 친화적 메시지로만 변환하는지 검증한다.
 *
 * <p>한때 여기서 503 오류를 {@code retryWhen}으로 자동 재시도했었는데, 재시도 때마다
 * {@code ChatbotFactory.createXxxChatbot()}을 다시 호출하고, 그게
 * {@link com.example.chat.repository.PersistentChatMemoryStore}를 통해 세션 히스토리를
 * 다시 불러온 뒤 같은 사용자 질문을 또 추가해버려서 채팅 기록에 질문이 중복 저장되는
 * 부작용이 있었다(사용자가 3번 보냈는데 매번 1번씩 내부 재시도가 걸려 총 6개로 늘어남).
 * 그래서 자동 재시도를 없애고 오류를 그대로 친화적 메시지로만 변환하도록 되돌렸다 - 이
 * 테스트는 그 자동 재시도가 실수로 다시 추가되지 않도록 지키는 회귀 테스트다.</p>
 */
class EgovChatServiceImplStreamErrorHandlingTest {

    private final EgovChatServiceImpl service =
            new EgovChatServiceImpl(mock(com.example.chat.service.ChatbotFactory.class),
                    java.util.Optional.of(mock(com.example.sqlgen.service.SqlGenService.class)));

    @Test
    @DisplayName("503 오류도 재시도 없이 단 1번만 구독하고 바로 친화적 메시지로 변환한다")
    void doesNotRetryOn503AndConvertsToFriendlyMessage() {
        AtomicInteger subscriptions = new AtomicInteger(0);
        Flux<String> stream = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.error(new RuntimeException("503 Service Temporarily Unavailable"));
        });

        Flux<String> result = service.applyRetryAndErrorHandling(stream, "RAG", "session-1");

        assertThat(result.collectList().block().get(0)).contains("503 Service Temporarily Unavailable");
        assertThat(subscriptions.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("단발성(unicast) 싱크로 만든 스트림도 재구독 시도 없이 안전하게 오류로 끝난다")
    void singleSubscriptionNeverViolatesUnicastSink() {
        // langchain4j-reactor가 streamChat()에서 실제로 쓰는 것과 같은 단발성 싱크.
        // 재시도(재구독)가 없어야 이 싱크가 "single Subscriber" 위반 없이 안전하다.
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        sink.tryEmitError(new RuntimeException("503 Service Temporarily Unavailable"));

        Flux<String> result = service.applyRetryAndErrorHandling(sink.asFlux(), "RAG", "session-1");

        String message = result.collectList().block().get(0);
        assertThat(message).contains("503 Service Temporarily Unavailable");
        assertThat(message).doesNotContain("single Subscriber");
    }

    @Test
    @DisplayName("503과 무관한 오류도 동일하게 재시도 없이 친화적 메시지로 변환된다")
    void nonRetriableErrorAlsoConvertsWithoutRetry() {
        AtomicInteger subscriptions = new AtomicInteger(0);
        Flux<String> stream = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.error(new RuntimeException("boom"));
        });

        Flux<String> result = service.applyRetryAndErrorHandling(stream, "RAG", "session-1");

        assertThat(result.collectList().block().get(0)).contains("boom");
        assertThat(subscriptions.get()).isEqualTo(1);
    }
}
