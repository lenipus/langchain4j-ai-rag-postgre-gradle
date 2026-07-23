package com.example.chat.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link EgovChatServiceImpl#withMemoryConflictRetry(java.util.function.Supplier, String)}가
 * "중지" 직후 바로 재질문했을 때 발생하는 채팅 메모리 낙관적 잠금 충돌
 * (ObjectOptimisticLockingFailureException)을 짧게 재시도해 넘기는지 검증한다.
 *
 * <p>이 충돌은 이전 스트림이 서버 쪽에서 아직 마무리 중이던 채팅 메모리 저장과 새 질문의
 * 메모리 저장이 같은 세션 행을 동시에 건드려 발생하며, 대부분 수백ms 안에 저절로 풀리는
 * 일시적 경합이다 - 이전에는 이게 바로 사용자에게 오류로 보여졌다.</p>
 */
class EgovChatServiceImplMemoryConflictRetryTest {

    private final EgovChatServiceImpl service =
            new EgovChatServiceImpl(mock(com.example.chat.service.ChatbotFactory.class),
                    java.util.Optional.of(mock(com.example.sqlgen.service.SqlGenService.class)));

    @Test
    @DisplayName("최대 재시도 횟수 이내에 성공하면 예외 없이 스트림을 반환한다")
    void succeedsAfterTransientConflictWithinMaxRetries() {
        AtomicInteger attempts = new AtomicInteger(0);

        Flux<String> result = service.withMemoryConflictRetry(() -> {
            if (attempts.getAndIncrement() < 2) {
                throw new ObjectOptimisticLockingFailureException("ChatMemoryEntity", 4240L);
            }
            return Flux.just("ok");
        }, "session-1");

        assertThat(result.collectList().block()).containsExactly("ok");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("재시도 횟수를 넘기면 원래 예외를 그대로 던진다")
    void rethrowsAfterExhaustingRetries() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() -> service.withMemoryConflictRetry(() -> {
            attempts.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException("ChatMemoryEntity", 4240L);
        }, "session-1")).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // 최초 시도 + 최대 재시도(2회) = 총 3번 시도 후 포기한다.
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("충돌과 무관한 예외는 재시도 없이 바로 전파된다")
    void doesNotRetryUnrelatedExceptions() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() -> service.withMemoryConflictRetry(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("boom");
        }, "session-1")).isInstanceOf(RuntimeException.class).hasMessage("boom");

        assertThat(attempts.get()).isEqualTo(1);
    }
}
