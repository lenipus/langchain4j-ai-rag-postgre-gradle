package com.example.chat.service.impl;

import com.example.chat.dto.ChatMessageDto;
import com.example.chat.dto.ChatSession;
import com.example.chat.entity.ChatMemoryEntity;
import com.example.chat.entity.ChatSessionEntity;
import com.example.chat.repository.ChatMemoryRepository;
import com.example.chat.repository.ChatSessionRepository;
import com.example.chat.repository.RagRetrievalLogRepository;
import com.example.chat.service.EgovChatSessionService;
import com.example.chat.service.SqlGenChatbot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EgovChatSessionServiceImpl extends EgovAbstractServiceImpl implements EgovChatSessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMemoryRepository chatMemoryRepository;
    private final RagRetrievalLogRepository ragRetrievalLogRepository;

    /** 세션 삭제 시 그 세션의 RAG 검색 감사 로그(rag_retrieval_logs)도 같이 지울지 여부. */
    @Value("${rag.retrieval-log.delete-with-session:true}")
    private boolean deleteRagLogWithSession;

    @Override
    @Transactional
    public ChatSession createNewSession() {
        String sessionId = UUID.randomUUID().toString();

        ChatSessionEntity entity = new ChatSessionEntity();
        entity.setSessionId(sessionId);
        entity.setTitle("새 채팅");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        chatSessionRepository.save(entity);

        log.debug("새 채팅 세션 생성: {}", sessionId);
        return new ChatSession(sessionId, "새 채팅", LocalDateTime.now());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> getAllSessions() {
        List<ChatSessionEntity> entities = chatSessionRepository.findAllByOrderByUpdatedAtDesc();

        return entities.stream()
                .map(entity -> new ChatSession(
                        entity.getSessionId(),
                        entity.getTitle(),
                        entity.getCreatedAt(),
                        entity.getUpdatedAt()))
                .collect(Collectors.toList());
    }

    /**
     * LangChain4j DefaultContentInjector가 사용자 메시지에 덧붙이는 RAG 컨텍스트 구분자.
     * ChatMemoryStore(PersistentChatMemoryStore)는 AiServices가 현재 턴의 프롬프트를
     * 재구성할 때도 그대로 재사용하는 소스이므로 저장 시점에 잘라내면 안 되고,
     * 화면 표시용으로 조회할 때만 잘라낸다.
     */
    private static final String RAG_INJECTION_MARKER = "\n\nAnswer using the following information:\n";

    /** SQL 생성 모드가 스키마 컨텍스트를 붙일 때 쓰는 구분자. 역할은 위 RAG 마커와 동일. */
    private static final String SQLGEN_CONTEXT_MARKER = SqlGenChatbot.SCHEMA_CONTEXT_MARKER;

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageDto> getSessionMessages(String sessionId) {
        List<ChatMemoryEntity> entities = chatMemoryRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // USER와 ASSISTANT 메시지만 반환 (SYSTEM 메시지 제외)
        return entities.stream()
                .filter(entity -> "USER".equals(entity.getMessageType()) ||
                        "ASSISTANT".equals(entity.getMessageType()))
                .map(entity -> new ChatMessageDto(
                        entity.getMessageType(),
                        "USER".equals(entity.getMessageType())
                                ? stripInjectedContext(entity.getContent())
                                : entity.getContent(),
                        entity.getCreatedAt()))
                .collect(Collectors.toList());
    }

    /** RAG 삽입 마커와 SQL 생성 스키마 컨텍스트 마커 중 먼저 나오는 위치에서 잘라낸다. */
    private String stripInjectedContext(String content) {
        if (content == null) {
            return null;
        }
        int ragIndex = content.indexOf(RAG_INJECTION_MARKER);
        int sqlGenIndex = content.indexOf(SQLGEN_CONTEXT_MARKER);
        int cutIndex = -1;
        if (ragIndex >= 0) {
            cutIndex = ragIndex;
        }
        if (sqlGenIndex >= 0 && (cutIndex < 0 || sqlGenIndex < cutIndex)) {
            cutIndex = sqlGenIndex;
        }
        return cutIndex >= 0 ? content.substring(0, cutIndex) : content;
    }

    @Override
    @Transactional
    public void updateSessionTitle(String sessionId, String title) {
        chatSessionRepository.findById(sessionId).ifPresent(entity -> {
            entity.setTitle(title);
            entity.setUpdatedAt(LocalDateTime.now());
            chatSessionRepository.save(entity);
            log.debug("세션 제목 업데이트: {} -> {}", sessionId, title);
        });
    }

    @Override
    @Transactional
    public void updateLastMessageTime(String sessionId) {
        chatSessionRepository.findById(sessionId).ifPresent(entity -> {
            entity.setUpdatedAt(LocalDateTime.now());
            chatSessionRepository.save(entity);
        });
    }

    @Override
    public String generateSessionTitle(String firstMessage) {
        if (firstMessage == null || firstMessage.trim().isEmpty()) {
            return "새 채팅";
        }

        // 첫 메시지에서 제목 생성 (최대 30자)
        String title = firstMessage.trim();
        if (title.length() > 30) {
            title = title.substring(0, 27) + "...";
        }

        return title;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean sessionExists(String sessionId) {
        return chatSessionRepository.existsById(sessionId);
    }

    @Override
    @Transactional
    public void deleteSession(String sessionId) {
        // 채팅 메모리 삭제 (cascade)
        chatMemoryRepository.deleteBySessionId(sessionId);

        // RAG 검색 감사 로그(rag_retrieval_logs)도 같이 정리할지는 설정으로 결정한다.
        // chat_memory와는 별개 테이블이라 DB상 외래키로 묶여있지 않으므로 명시적으로 지운다.
        if (deleteRagLogWithSession) {
            ragRetrievalLogRepository.deleteBySessionId(sessionId);
        }

        // 세션 삭제
        chatSessionRepository.deleteById(sessionId);

        log.debug("세션 삭제: {} (RAG 로그 삭제: {})", sessionId, deleteRagLogWithSession);
    }
}
