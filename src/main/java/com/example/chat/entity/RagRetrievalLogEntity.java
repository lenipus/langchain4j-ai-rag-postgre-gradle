package com.example.chat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG 질의마다 실제로 검색되어 LLM에 전달된 청크를 감사·디버깅 목적으로 남기는 로그.
 *
 * <p>{@code chat_memory}와는 완전히 별개다. chat_memory는 LLM에 매 턴 재전송되는 대화
 * 히스토리라 여기에 검색 결과를 계속 남기면 컨텍스트 윈도우가 금방 넘친다(실제로 겪은
 * 문제). 이 테이블은 LLM에는 절대 다시 보내지 않고, "이 질문 때 실제로 뭐가 검색됐는지"를
 * 나중에 조회하기 위한 용도로만 쓴다.</p>
 */
@Entity
@Table(name = "rag_retrieval_logs", indexes = {
        @Index(name = "idx_rag_retrieval_logs_session_id", columnList = "session_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagRetrievalLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "query_text", columnDefinition = "TEXT")
    private String queryText;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "score")
    private Double score;

    @Column(name = "chunk_text", columnDefinition = "TEXT")
    private String chunkText;

    @Column(name = "retrieved_at")
    private LocalDateTime retrievedAt;

    @PrePersist
    protected void onCreate() {
        retrievedAt = LocalDateTime.now();
    }

    public RagRetrievalLogEntity(String sessionId, String queryText, String fileName, Double score, String chunkText) {
        this.sessionId = sessionId;
        this.queryText = queryText;
        this.fileName = fileName;
        this.score = score;
        this.chunkText = chunkText;
    }
}
