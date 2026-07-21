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
 *
 * <p>{@code sessionId}/{@code turnId}는 이 질의 하나를 위해 {@code ChatbotFactory}가
 * 새로 만드는 이 인스턴스에 직접 주입된다. {@code query.metadata().chatMemoryId()}는
 * 쓰지 않는다 - langchain4j의 AiServices는 서비스 인터페이스 메서드에 {@code @MemoryId}
 * 파라미터가 없으면(우리는 세션마다 ChatMemory 인스턴스를 직접 새로 만들어 바인딩하는
 * 방식이라 이 파라미터가 없다) chatMemoryId를 무조건 리터럴 문자열 "default"로 채운다
 * (역컴파일로 확인: {@code findMemoryId(...).orElse("default")}). 그래서 chat_memory
 * 테이블은 ChatMemory 자신의 {@code .id(sessionId)}를 통해 실제 세션ID가 들어가는데,
 * 여기서 그 값을 읽으려 하면 항상 "default"만 찍히는 버그가 있었다.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class EgovLoggingContentRetriever implements ContentRetriever {

    private static final int PREVIEW_LENGTH = 200;

    private final ContentRetriever delegate;
    private final RagRetrievalLogRepository ragRetrievalLogRepository;
    private final String sessionId;
    private final String turnId;

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> results = delegate.retrieve(query);

        if (results.isEmpty()) {
            log.info("RAG 검색 결과 없음 - 쿼리: {}", query.text());
            return results;
        }

        log.info("RAG 검색 결과 {}건 - 쿼리: {}", results.size(), query.text());

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
                    new RagRetrievalLogEntity(sessionId, turnId, queryText, fileName, scoreValue, chunkText));
        } catch (Exception e) {
            log.warn("RAG 검색 결과 감사 로그 저장 실패 - 세션: {}, 원인: {}", sessionId, e.getMessage());
        }
    }
}
