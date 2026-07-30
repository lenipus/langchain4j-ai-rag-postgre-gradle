package com.example.chatbot.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatModelGateway#resolveNumCtx(String)}가 설정값(상한)과 모델별 실제 최대 컨텍스트
 * 중 더 작은 쪽을 고르는지 검증한다.
 *
 * <p>모델마다 실제 지원하는 컨텍스트 크기가 다른데(예: gemma2:2b=8192, qwen3:4b=40960),
 * 예전엔 어떤 모델을 고르든 설정값 하나를 그대로 Ollama에 보내서, 작은 모델엔 낭비/한계
 * 초과 위험이 있었고 큰 모델은 설정값 이상을 못 썼다. 상한(설정값)과 모델 한계 중 작은
 * 쪽을 쓰도록 바꿔서 이 문제를 해결했다.</p>
 */
class ChatModelGatewayResolveNumCtxTest {

    private ChatModelGateway newGateway(Integer configuredNumCtx, Function<String, Optional<Integer>> contextLengthLookup) {
        ChatModelGateway gateway = new ChatModelGateway() {
            @Override
            public Optional<Integer> getContextLength(String modelName) {
                return contextLengthLookup.apply(modelName);
            }
        };
        ReflectionTestUtils.setField(gateway, "chatModelNumCtx", configuredNumCtx);
        return gateway;
    }

    @Test
    @DisplayName("설정값(상한)이 모델 한계보다 크면 모델 한계로 깎는다 (예: gemma2:2b)")
    void capsAtModelMaxWhenConfiguredCeilingIsLarger() {
        ChatModelGateway gateway = newGateway(40960, name -> Optional.of(8192));

        assertThat(gateway.resolveNumCtx("gemma2:2b")).isEqualTo(8192);
    }

    @Test
    @DisplayName("모델 한계가 설정값(상한)보다 크거나 같으면 설정값을 그대로 쓴다 (예: qwen3:4b)")
    void usesConfiguredCeilingWhenModelSupportsMoreOrEqual() {
        ChatModelGateway gateway = newGateway(40960, name -> Optional.of(40960));

        assertThat(gateway.resolveNumCtx("qwen3:4b-q4_K_M")).isEqualTo(40960);
    }

    @Test
    @DisplayName("모델이 상한보다 훨씬 큰 컨텍스트를 지원해도 설정한 상한으로 깎는다 (예: hyperclova)")
    void capsAtConfiguredCeilingWhenModelSupportsMuchMore() {
        ChatModelGateway gateway = newGateway(40960, name -> Optional.of(131072));

        assertThat(gateway.resolveNumCtx("hyperclova-fixed:Q4_K_M")).isEqualTo(40960);
    }

    @Test
    @DisplayName("모델 정보를 못 가져오면 설정값을 그대로 쓴다")
    void fallsBackToConfiguredValueWhenModelInfoUnavailable() {
        ChatModelGateway gateway = newGateway(16384, name -> Optional.empty());

        assertThat(gateway.resolveNumCtx("unknown-model")).isEqualTo(16384);
    }

    @Test
    @DisplayName("설정값이 없으면(0) 모델 한계를 그대로 쓴다")
    void usesModelMaxWhenNoCeilingConfigured() {
        ChatModelGateway gateway = newGateway(0, name -> Optional.of(40960));

        assertThat(gateway.resolveNumCtx("qwen3:4b-q4_K_M")).isEqualTo(40960);
    }

    @Test
    @DisplayName("설정값도 없고 모델 정보도 못 가져오면 0을 반환해 Ollama 기본값에 맡긴다")
    void returnsZeroWhenBothUnavailable() {
        ChatModelGateway gateway = newGateway(0, name -> Optional.empty());

        assertThat(gateway.resolveNumCtx("unknown-model")).isEqualTo(0);
    }
}
