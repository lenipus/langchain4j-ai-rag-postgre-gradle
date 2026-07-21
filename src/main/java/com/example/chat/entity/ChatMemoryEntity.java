package com.example.chat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_memory", indexes = {
    @Index(name = "idx_chat_memory_session_id", columnList = "session_id"),
    @Index(name = "idx_chat_memory_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "message_type", nullable = false, length = 20)
    private String messageType; // USER, ASSISTANT, SYSTEM

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 이 메시지가 속한 질의(턴)의 고유 키. RAG 턴에서만 채워지며(단순 채팅은 null),
     * 같은 턴에 속한 사용자 메시지·AI 응답이 동일한 값을 갖는다. rag_retrieval_logs.turn_id와
     * 같은 값이라 두 테이블을 조인해 "이 메시지를 만들 때 실제로 RAG가 뭘 검색해줬는지"를
     * 정확히 추적할 수 있다.
     */
    @Column(name = "turn_id", length = 36)
    private String turnId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public ChatMemoryEntity(String sessionId, String messageType, String content) {
        this.sessionId = sessionId;
        this.messageType = messageType;
        this.content = content;
    }
}
