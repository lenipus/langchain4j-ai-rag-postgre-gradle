package com.example.chat.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EgovLengthFilteringContentRetriever}가 짧은 청크(스퓨리어스 매칭 위험)를
 * 걸러내고 최종 top-k로 자르는지 검증한다.
 */
class EgovLengthFilteringContentRetrieverTest {

    private static final int MIN_CHUNK_LENGTH = 10;

    private Content content(String text) {
        return Content.from(TextSegment.from(text));
    }

    @Test
    @DisplayName("최소 길이 미만 청크는 걸러지고, 나머지가 최종 topK로 잘린다")
    void filtersShortChunksAndTrimsToFinalTopK() {
        // delegate 가 overfetch로 5개를 반환했다고 가정: 짧은 것 2개 + 충분히 긴 것 3개.
        ContentRetriever delegate = q -> List.of(
                content("짧음1"),
                content("충분히 긴 청크 하나입니다"),
                content("짧음2"),
                content("충분히 긴 청크 둘입니다"),
                content("충분히 긴 청크 셋입니다"));

        ContentRetriever filtering = new EgovLengthFilteringContentRetriever(delegate, MIN_CHUNK_LENGTH, 2);

        List<Content> result = filtering.retrieve(Query.from("아무 질의"));

        // 짧은 청크 2개는 제외되고, 남은 3개 중 최종 topK(2)로 잘린다.
        assertThat(result).hasSize(2);
        assertThat(result.get(0).textSegment().text()).isEqualTo("충분히 긴 청크 하나입니다");
        assertThat(result.get(1).textSegment().text()).isEqualTo("충분히 긴 청크 둘입니다");
    }

    @Test
    @DisplayName("전부 짧은 청크뿐이면 빈 리스트를 반환한다")
    void returnsEmptyWhenAllChunksTooShort() {
        ContentRetriever delegate = q -> List.of(content("짧음1"), content("짧음2"));

        ContentRetriever filtering = new EgovLengthFilteringContentRetriever(delegate, MIN_CHUNK_LENGTH, 5);

        assertThat(filtering.retrieve(Query.from("아무 질의"))).isEmpty();
    }

    @Test
    @DisplayName("필터링 후 남은 개수가 topK보다 적으면 있는 만큼만 반환한다(개수를 억지로 채우지 않음)")
    void returnsFewerThanTopKWhenNotEnoughLongChunks() {
        ContentRetriever delegate = q -> List.of(content("짧음1"), content("충분히 긴 청크 하나입니다"));

        ContentRetriever filtering = new EgovLengthFilteringContentRetriever(delegate, MIN_CHUNK_LENGTH, 5);

        List<Content> result = filtering.retrieve(Query.from("아무 질의"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).textSegment().text()).isEqualTo("충분히 긴 청크 하나입니다");
    }
}
