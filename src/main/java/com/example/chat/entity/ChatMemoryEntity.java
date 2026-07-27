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

    /**
     * 첨부 이미지(있는 경우만). 새로고침해도 화면에 다시 보이도록 그대로 저장하지만,
     * 채팅 메모리를 다시 불러와 LLM에 넘길 때는(getMessages) 과거 턴 컨텍스트가 계속
     * 불어나는 걸 막기 위해 이 값을 다시 실어보내지 않는다 - 화면 표시 전용이다.
     */
    @Column(name = "image_base64", columnDefinition = "TEXT")
    private String imageBase64;

    @Column(name = "image_mime_type", length = 100)
    private String imageMimeType;

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
