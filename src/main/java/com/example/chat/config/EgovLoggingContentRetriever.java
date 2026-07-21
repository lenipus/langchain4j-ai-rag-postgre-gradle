package com.example.chat.config;

import com.example.chat.entity.RagRetrievalLogEntity;
import com.example.chat.repository.RagRetrievalLogRepository;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * ContentRetriever 데코레이터. 실제 검색은 delegate에 그대로 위임하고, LLM에 넘어가는
 * RAG 검색 결과(문서/점수/미리보기)를 로그와 {@code rag_retrieval_logs} 테이블에 남긴다.
 *
 * <p>이 테이블은 {@code chat_memory}(LLM에 매 턴 재전송되는 대화 히스토리)와 완전히
 * 별개다 — 여기 남기는 건 순수 감사/디버깅용이라 LLM에는 다시 보내지 않으므로, 세션이
 * 길어져도 컨텍스트 윈도우 문제와는 무관하다.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class EgovLoggingContentRetriever implements ContentRetriever {

    private static final int PREVIEW_LENGTH = 200;

    private final ContentRetriever delegate;
    private final RagRetrievalLogRepository ragRetrievalLogRepository;

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> results = delegate.retrieve(query);

        if (results.isEmpty()) {
            log.info("RAG 검색 결과 없음 - 쿼리: {}", query.text());
            return results;
        }

        log.info("RAG 검색 결과 {}건 - 쿼리: {}", results.size(), query.text());

        String sessionId = query.metadata() != null && query.metadata().chatMemoryId() != null
                ? query.metadata().chatMemoryId().toString()
                : null;

        for (Content content : results) {
            String fileName = content.textSegment().metadata().getString("file_name");
            Object score = content.metadata().get(dev.langchain4j.rag.content.ContentMetadata.SCORE);
            String text = content.textSegment().text();
            String preview = text.length() > PREVIEW_LENGTH ? text.substring(0, PREVIEW_LENGTH) + "..." : text;

            log.info("  - file={}, score={}, length={}, text={}", fileName, score, text.length(), preview);

            persistLog(sessionId, query.text(), fileName, score, text);
        }

        return results;
    }

    /**
     * 감사 로그 저장 실패가 RAG 응답 자체를 막으면 안 되므로, 예외는 로그만 남기고 삼킨다.
     */
    private void persistLog(String sessionId, String queryText, String fileName, Object score, String chunkText) {
        try {
            Double scoreValue = (score instanceof Number number) ? number.doubleValue() : null;
            ragRetrievalLogRepository.save(
                    new RagRetrievalLogEntity(sessionId, queryText, fileName, scoreValue, chunkText));
        } catch (Exception e) {
            log.warn("RAG 검색 결과 감사 로그 저장 실패 - 세션: {}, 원인: {}", sessionId, e.getMessage());
        }
    }
}
