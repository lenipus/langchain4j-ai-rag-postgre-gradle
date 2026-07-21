package com.example.chat.service.impl;

import com.example.chat.repository.ChatMemoryRepository;
import com.example.chat.repository.ChatSessionRepository;
import com.example.chat.repository.RagRetrievalLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

/**
 * {@link EgovChatSessionServiceImpl#deleteSession(String)}이 {@code rag.retrieval-log.delete-with-session}
 * 설정에 따라 {@code rag_retrieval_logs}도 같이 지우는지 검증한다.
 *
 * <p>chat_memory와 rag_retrieval_logs는 session_id 값만 공유할 뿐 DB 외래키로 묶여있지
 * 않으므로, 세션 삭제 시 자동으로 같이 지워지지 않는다 - 이 설정으로 명시적으로 제어한다.</p>
 */
class EgovChatSessionServiceImplDeleteSessionTest {

    private final ChatSessionRepository chatSessionRepository = mock(ChatSessionRepository.class);
    private final ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);
    private final RagRetrievalLogRepository ragRetrievalLogRepository = mock(RagRetrievalLogRepository.class);
    private final EgovChatSessionServiceImpl service = new EgovChatSessionServiceImpl(
            chatSessionRepository, chatMemoryRepository, ragRetrievalLogRepository);

    @Test
    @DisplayName("delete-with-session=true(기본값)면 세션 삭제 시 RAG 감사 로그도 같이 지운다")
    void deletesRagLogWhenEnabled() {
        ReflectionTestUtils.setField(service, "deleteRagLogWithSession", true);

        service.deleteSession("session-1");

        verify(chatMemoryRepository).deleteBySessionId("session-1");
        verify(ragRetrievalLogRepository).deleteBySessionId("session-1");
        verify(chatSessionRepository).deleteById("session-1");
    }

    @Test
    @DisplayName("delete-with-session=false면 세션을 지워도 RAG 감사 로그는 남긴다")
    void keepsRagLogWhenDisabled() {
        ReflectionTestUtils.setField(service, "deleteRagLogWithSession", false);

        service.deleteSession("session-2");

        verify(chatMemoryRepository).deleteBySessionId("session-2");
        verify(ragRetrievalLogRepository, never()).deleteBySessionId(anyString());
        verify(chatSessionRepository).deleteById("session-2");
    }
}
