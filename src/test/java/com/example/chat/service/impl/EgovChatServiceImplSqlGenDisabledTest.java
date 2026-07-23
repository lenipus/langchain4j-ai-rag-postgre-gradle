package com.example.chat.service.impl;

import com.example.chat.context.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code sqlgen.enabled=false}면 {@link com.example.sqlgen.service.impl.SqlGenServiceImpl} 빈
 * 자체가 안 만들어져(암호화 키 미설정으로 인한 구동 실패 방지) {@link EgovChatServiceImpl}은
 * {@code Optional.empty()}인 {@code SqlGenService}를 받는다. 이때 SQL 생성 스트리밍 요청이
 * NPE로 죽지 않고 안내 메시지를 그대로 돌려주는지 검증한다.
 */
class EgovChatServiceImplSqlGenDisabledTest {

    private final EgovChatServiceImpl service =
            new EgovChatServiceImpl(mock(com.example.chat.service.ChatbotFactory.class), Optional.empty());

    @AfterEach
    void clearSessionContext() {
        SessionContext.clear();
    }

    @Test
    @DisplayName("SqlGenService가 없으면(sqlgen.enabled=false) 비활성화 안내 메시지를 반환한다")
    void returnsDisabledMessageWhenSqlGenServiceAbsent() {
        SessionContext.setCurrentSessionId("session-1");

        Flux<String> result = service.streamSqlGenResponse("SELECT 1", null, 1L, java.util.List.of("table1"));

        String message = String.join("", result.collectList().block());
        assertThat(message).contains("비활성화");
    }
}
