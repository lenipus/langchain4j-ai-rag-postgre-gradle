package com.example.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatModelGateway#getInstalledModels()}와 {@link ChatModelGateway#isAvailable()}가
 * 첫 조회 결과가 실패(빈 목록/false)일 때 최대 3번까지(점점 늘어나는 대기 시간을 두고)
 * 재시도하는지 검증한다.
 *
 * <p>{@code ollama --version}(가용성 체크)은 데몬 없이도 성공하지만 {@code ollama list}는
 * 데몬이 응답해야 하므로, 데몬이 막 기동된 직후에는 가용은 true이면서 목록만 비어 오는
 * 경우가 있었다(새로고침하면 정상 조회됨). 원격(openai 호환) 모드는 가정용 회선/동적 DNS
 * 뒤의 서버라 응답이 더 자주 늦거나 끊겨서, 재시도 횟수를 1번에서 3번으로 늘렸다.</p>
 *
 * <p>{@code isAvailable()}은 원래 재시도가 없는 단발성 체크였는데, 이 체크가 일시적으로
 * false를 반환하면 컨트롤러가 {@code getInstalledModels()}까지 가보지도 못하고 바로
 * "사용 불가"로 응답을 끝내버려서, 새로고침해도 모델 목록이 계속 안 나오는 문제가 있었다
 * (getInstalledModels 쪽 재시도만으로는 해결되지 않는 경로). 그래서 같은 재시도를
 * isAvailable()에도 적용했다.</p>
 */
class ChatModelGatewayRetryTest {

    @Test
    @DisplayName("두 번째 재시도에서 성공하면 그 결과를 반환하고 멈춘다(총 3번 호출)")
    void retriesUntilSuccessWithinMaxAttempts() {
        AtomicInteger callCount = new AtomicInteger(0);
        ChatModelGateway gateway = new ChatModelGateway() {
            @Override
            protected List<String> fetchInstalledModels() {
                return callCount.incrementAndGet() >= 3 ? List.of("qllama/bge-m3:q8_0") : List.of();
            }
        };

        List<String> models = gateway.getInstalledModels();

        assertThat(models).containsExactly("qllama/bge-m3:q8_0");
        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("첫 조회가 이미 결과가 있으면 재시도하지 않는다")
    void doesNotRetryWhenFirstResultIsNotEmpty() {
        AtomicInteger callCount = new AtomicInteger(0);
        ChatModelGateway gateway = new ChatModelGateway() {
            @Override
            protected List<String> fetchInstalledModels() {
                callCount.incrementAndGet();
                return List.of("embeddinggemma:300m");
            }
        };

        List<String> models = gateway.getInstalledModels();

        assertThat(models).containsExactly("embeddinggemma:300m");
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도까지 계속 비어있으면 최대 횟수(1회 최초 + 3회 재시도 = 4번)만 시도하고 빈 목록을 반환한다")
    void returnsEmptyAfterExhaustingMaxRetries() {
        AtomicInteger callCount = new AtomicInteger(0);
        ChatModelGateway gateway = new ChatModelGateway() {
            @Override
            protected List<String> fetchInstalledModels() {
                callCount.incrementAndGet();
                return List.of();
            }
        };

        List<String> models = gateway.getInstalledModels();

        assertThat(models).isEmpty();
        assertThat(callCount.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("가용성 체크가 두 번째 재시도에서 성공하면 true를 반환하고 멈춘다(총 3번 호출)")
    void isAvailableRetriesUntilSuccessWithinMaxAttempts() {
        AtomicInteger callCount = new AtomicInteger(0);
        ChatModelGateway gateway = new ChatModelGateway() {
            @Override
            protected boolean checkAvailability() {
                return callCount.incrementAndGet() >= 3;
            }
        };

        assertThat(gateway.isAvailable()).isTrue();
        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("가용성 체크가 첫 시도에 이미 true면 재시도하지 않는다")
    void isAvailableDoesNotRetryWhenFirstCheckSucceeds() {
        AtomicInteger callCount = new AtomicInteger(0);
        ChatModelGateway gateway = new ChatModelGateway() {
            @Override
            protected boolean checkAvailability() {
                callCount.incrementAndGet();
                return true;
            }
        };

        assertThat(gateway.isAvailable()).isTrue();
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("가용성 체크가 재시도까지 계속 실패하면 최대 횟수(1회 최초 + 3회 재시도 = 4번)만 시도하고 false를 반환한다")
    void isAvailableReturnsFalseAfterExhaustingMaxRetries() {
        AtomicInteger callCount = new AtomicInteger(0);
        ChatModelGateway gateway = new ChatModelGateway() {
            @Override
            protected boolean checkAvailability() {
                callCount.incrementAndGet();
                return false;
            }
        };

        assertThat(gateway.isAvailable()).isFalse();
        assertThat(callCount.get()).isEqualTo(4);
    }
}
