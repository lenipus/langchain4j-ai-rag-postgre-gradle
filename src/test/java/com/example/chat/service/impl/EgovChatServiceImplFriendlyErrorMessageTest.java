package com.example.chat.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link EgovChatServiceImpl#friendlyErrorMessage(Throwable)}가 Ollama의 컨텍스트 크기
 * 초과 오류를 실제 토큰 수까지 포함한 구체적인 한국어 메시지로 바꿔주는지 검증한다.
 *
 * <p>이전에는 이 예외가 그대로 프론트(EventSource)에 전달돼 "네트워크 연결을 확인해
 * 주세요"처럼 원인을 전혀 알 수 없는 메시지만 보여줬다 - 채팅 기록이 길어져 모델의
 * 컨텍스트 창을 넘긴 것뿐인데 사용자는 원인을 알 방법이 없었다.</p>
 */
class EgovChatServiceImplFriendlyErrorMessageTest {

    private final EgovChatServiceImpl service =
            new EgovChatServiceImpl(mock(com.example.chat.service.ChatbotFactory.class),
                    mock(com.example.sqlgen.service.SqlGenService.class));

    @Test
    @DisplayName("컨텍스트 크기 초과 오류는 실제 토큰 수(n_prompt_tokens/n_ctx)를 포함한 메시지로 바뀐다")
    void translatesContextSizeExceededErrorWithActualTokenCounts() {
        String ollamaErrorBody = "{\"error\":\"{\\\"error\\\":{\\\"code\\\":400,"
                + "\\\"message\\\":\\\"request (13018 tokens) exceeds the available context size (4096 tokens), "
                + "try increasing it\\\",\\\"type\\\":\\\"exceed_context_size_error\\\","
                + "\\\"n_prompt_tokens\\\":13018,\\\"n_ctx\\\":4096}}\"}";
        Exception exception = new RuntimeException(ollamaErrorBody);

        String message = service.friendlyErrorMessage(exception);

        assertThat(message).contains("13018").contains("4096");
        assertThat(message).contains("너무 길어");
        assertThat(message).doesNotContain("네트워크 연결");
    }

    @Test
    @DisplayName("원인 체인(getCause) 안에 컨텍스트 초과 오류가 있어도 찾아낸다")
    void findsContextSizeExceededErrorInCauseChain() {
        Exception cause = new RuntimeException("{\"type\":\"exceed_context_size_error\","
                + "\"n_prompt_tokens\":20000,\"n_ctx\":8192}");
        Exception wrapper = new RuntimeException("wrapped", cause);

        String message = service.friendlyErrorMessage(wrapper);

        assertThat(message).contains("20000").contains("8192");
    }

    @Test
    @DisplayName("타임아웃/연결 오류는 기존처럼 재시도 안내 메시지로 처리된다")
    void keepsExistingTimeoutMessageForUnrelatedErrors() {
        Exception exception = new RuntimeException("connection timed out");

        String message = service.friendlyErrorMessage(exception);

        assertThat(message).contains("응답 시간이 초과");
    }

    @Test
    @DisplayName("그 외 오류는 원본 메시지를 포함한 범용 메시지로 폴백한다")
    void fallsBackToGenericMessageForOtherErrors() {
        Exception exception = new RuntimeException("boom");

        String message = service.friendlyErrorMessage(exception);

        assertThat(message).contains("boom");
    }
}
