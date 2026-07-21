package com.example.chat.service.impl;

import com.example.chat.dto.ChatMessageDto;
import com.example.chat.entity.ChatMemoryEntity;
import com.example.chat.repository.ChatMemoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EgovChatSessionServiceImpl#getSessionMessages(String)}가 화면 표시용으로
 * RAG 컨텍스트 삽입 텍스트("Answer using the following information:...")를 잘라내는지 검증한다.
 *
 * <p>이 스트립은 여기(조회/DTO 변환 경로)에서만 이뤄져야 한다. PersistentChatMemoryStore는
 * AiServices가 현재 턴 프롬프트를 재구성할 때도 재사용하는 소스라서 거기서 잘라내면
 * 검색된 문서 내용이 LLM에 전달되지 않는 회귀가 생긴다.
 */
class EgovChatSessionServiceImplRagStripTest {

    private final ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);
    private final EgovChatSessionServiceImpl service =
            new EgovChatSessionServiceImpl(null, chatMemoryRepository, null);

    @Test
    @DisplayName("USER 메시지의 RAG 삽입 텍스트는 화면 조회 시 잘려나간다")
    void stripsRagInjectionFromUserMessage() {
        String augmented = "본부장 연봉 상한액이 얼마야??\n\nAnswer using the following information:\n부 칙 제1조...";
        ChatMemoryEntity entity = new ChatMemoryEntity("session-1", "USER", augmented);
        entity.setCreatedAt(LocalDateTime.now());
        when(chatMemoryRepository.findBySessionIdOrderByCreatedAtAsc(eq("session-1")))
                .thenReturn(List.of(entity));

        List<ChatMessageDto> result = service.getSessionMessages("session-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("본부장 연봉 상한액이 얼마야??");
    }

    @Test
    @DisplayName("삽입 텍스트가 없는 일반 USER 메시지는 그대로 반환된다")
    void leavesPlainUserMessageUnchanged() {
        ChatMemoryEntity entity = new ChatMemoryEntity("session-2", "USER", "겸직허가 규정 좀 알려줘");
        entity.setCreatedAt(LocalDateTime.now());
        when(chatMemoryRepository.findBySessionIdOrderByCreatedAtAsc(eq("session-2")))
                .thenReturn(List.of(entity));

        List<ChatMessageDto> result = service.getSessionMessages("session-2");

        assertThat(result.get(0).getContent()).isEqualTo("겸직허가 규정 좀 알려줘");
    }

    @Test
    @DisplayName("ASSISTANT 메시지는 마커가 있어도 그대로 반환된다(스트립 대상 아님)")
    void leavesAssistantMessageUnchangedRegardlessOfMarker() {
        String content = "답변입니다.\n\nAnswer using the following information:\n(모델이 그대로 인용)";
        ChatMemoryEntity entity = new ChatMemoryEntity("session-3", "ASSISTANT", content);
        entity.setCreatedAt(LocalDateTime.now());
        when(chatMemoryRepository.findBySessionIdOrderByCreatedAtAsc(eq("session-3")))
                .thenReturn(List.of(entity));

        List<ChatMessageDto> result = service.getSessionMessages("session-3");

        assertThat(result.get(0).getContent()).isEqualTo(content);
    }

    @Test
    @DisplayName("SYSTEM 메시지는 필터링되어 반환되지 않는다")
    void filtersOutSystemMessage() {
        ChatMemoryEntity entity = new ChatMemoryEntity("session-4", "SYSTEM", "시스템 프롬프트");
        entity.setCreatedAt(LocalDateTime.now());
        when(chatMemoryRepository.findBySessionIdOrderByCreatedAtAsc(eq("session-4")))
                .thenReturn(List.of(entity));

        List<ChatMessageDto> result = service.getSessionMessages("session-4");

        assertThat(result).isEmpty();
    }
}
