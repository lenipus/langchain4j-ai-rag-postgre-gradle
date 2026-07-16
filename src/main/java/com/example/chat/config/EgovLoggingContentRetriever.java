package com.example.chat.config;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * ContentRetriever 데코레이터. 실제 검색은 delegate에 그대로 위임하고,
 * LLM에 넘어가는 RAG 검색 결과(문서/점수/미리보기)만 로그로 남긴다.
 */
@Slf4j
@RequiredArgsConstructor
public class EgovLoggingContentRetriever implements ContentRetriever {

    private static final int PREVIEW_LENGTH = 200;

    private final ContentRetriever delegate;

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
        }

        return results;
    }
}
