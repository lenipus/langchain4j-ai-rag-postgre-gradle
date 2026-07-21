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
        @Index(name = "idx_rag_retrieval_logs_session_id", columnList = "session_id"),
        @Index(name = "idx_rag_retrieval_logs_turn_id", columnList = "turn_id")
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

    /**
     * 이 검색 결과가 발생한 "그 질의(턴)"의 고유 키. 같은 세션에서 여러 번 질의해도
     * 매번 새로 발급되므로, session_id + 시각 순서로 대충 묶어보던 것과 달리 정확히
     * 어떤 질의 하나에 딸린 검색 결과인지 특정할 수 있다. chat_memory.turn_id와
     * 같은 값이 찍히므로 두 테이블을 이 값으로 조인해 "이 질문/답변에 실제로 뭐가
     * 검색됐는지"를 정확히 추적할 수 있다.
     */
    @Column(name = "turn_id", length = 36)
    private String turnId;

    /**
     * 실제 검색에 쓰인 질의 텍스트. {@code rag.query-compression.enabled=true}면
     * 사용자가 친 원문이 아니라 이전 대화를 반영해 LLM이 재작성(압축)한 독립형 질문이다.
     */
    @Column(name = "query_text", columnDefinition = "TEXT")
    private String queryText;

    /**
     * 사용자가 실제로 입력한 원본 질의. 질의 압축이 꺼져 있으면 {@link #queryText}와 같다.
     */
    @Column(name = "original_query_text", columnDefinition = "TEXT")
    private String originalQueryText;

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

    public RagRetrievalLogEntity(String sessionId, String turnId, String queryText, String originalQueryText,
                                  String fileName, Double score, String chunkText) {
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.queryText = queryText;
        this.originalQueryText = originalQueryText;
        this.fileName = fileName;
        this.score = score;
        this.chunkText = chunkText;
    }
}
