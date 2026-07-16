package com.example.chat.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EgovLoggingContentRetriever}는 delegate의 검색 결과를 그대로 통과시키며
 * 로그만 남긴다. 결과 변형이 없는지(순수 pass-through)만 검증한다.
 */
class EgovLoggingContentRetrieverTest {

    @Test
    @DisplayName("검색 결과가 있으면 그대로 반환한다")
    void passesThroughNonEmptyResults() {
        ContentRetriever delegate = mock(ContentRetriever.class);
        Query query = Query.from("본부장 연봉 상한액이 얼마야??");
        List<Content> expected = List.of(Content.from(TextSegment.from("부 칙 제1조...")));
        when(delegate.retrieve(query)).thenReturn(expected);

        EgovLoggingContentRetriever retriever = new EgovLoggingContentRetriever(delegate);
        List<Content> result = retriever.retrieve(query);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 리스트를 그대로 반환한다")
    void passesThroughEmptyResults() {
        ContentRetriever delegate = mock(ContentRetriever.class);
        Query query = Query.from("겸직허가 규정 좀 알려줘");
        when(delegate.retrieve(query)).thenReturn(List.of());

        EgovLoggingContentRetriever retriever = new EgovLoggingContentRetriever(delegate);
        List<Content> result = retriever.retrieve(query);

        assertThat(result).isEmpty();
    }
}
