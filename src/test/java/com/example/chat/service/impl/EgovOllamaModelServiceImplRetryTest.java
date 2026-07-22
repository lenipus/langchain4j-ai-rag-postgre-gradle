package com.example.chat.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EgovOllamaModelServiceImpl#getInstalledModels()}가 첫 조회 결과가 빈 목록일 때
 * 한 번만 재시도하는지 검증한다.
 *
 * <p>{@code ollama --version}(가용성 체크)은 데몬 없이도 성공하지만 {@code ollama list}는
 * 데몬이 응답해야 하므로, 데몬이 막 기동된 직후에는 가용은 true이면서 목록만 비어 오는
 * 경우가 있었다(새로고침하면 정상 조회됨). 이를 서버 쪽에서 한 번 재시도해 흡수한다.</p>
 */
class EgovOllamaModelServiceImplRetryTest {

    @Test
    @DisplayName("첫 조회가 빈 목록이면 한 번 재시도해서 두 번째 결과를 반환한다")
    void retriesOnceWhenFirstResultIsEmpty() {
        AtomicInteger callCount = new AtomicInteger(0);
        EgovOllamaModelServiceImpl service = new EgovOllamaModelServiceImpl() {
            @Override
            protected List<String> fetchInstalledModels() {
                return callCount.incrementAndGet() == 1 ? List.of() : List.of("qllama/bge-m3:q8_0");
            }
        };

        List<String> models = service.getInstalledModels();

        assertThat(models).containsExactly("qllama/bge-m3:q8_0");
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("첫 조회가 이미 결과가 있으면 재시도하지 않는다")
    void doesNotRetryWhenFirstResultIsNotEmpty() {
        AtomicInteger callCount = new AtomicInteger(0);
        EgovOllamaModelServiceImpl service = new EgovOllamaModelServiceImpl() {
            @Override
            protected List<String> fetchInstalledModels() {
                callCount.incrementAndGet();
                return List.of("embeddinggemma:300m");
            }
        };

        List<String> models = service.getInstalledModels();

        assertThat(models).containsExactly("embeddinggemma:300m");
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도까지 계속 비어있으면 빈 목록을 반환한다")
    void returnsEmptyWhenRetryAlsoEmpty() {
        AtomicInteger callCount = new AtomicInteger(0);
        EgovOllamaModelServiceImpl service = new EgovOllamaModelServiceImpl() {
            @Override
            protected List<String> fetchInstalledModels() {
                callCount.incrementAndGet();
                return List.of();
            }
        };

        List<String> models = service.getInstalledModels();

        assertThat(models).isEmpty();
        assertThat(callCount.get()).isEqualTo(2);
    }
}
