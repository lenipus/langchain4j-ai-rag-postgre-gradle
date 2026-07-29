package com.example.chat.config;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EgovNeighborChunkExpander}가 "실제로 잘린 것으로 보이는 쪽"에만 이웃 청크를
 * 붙이고, 붙일 때도 chunk-overlap 길이만큼만 잘라 붙이는지 검증한다.
 *
 * <p>고정 길이 청크 분할 때문에 문장·표 행이 경계에서 잘리는 문제(예: 결재라인 안내 문서의
 * "출장" 항목이 청크 크기에서 그대로 끊긴 실제 사례)를 완화하되, 모든 청크에 무조건
 * 앞뒤를 다 붙이면 컨텍스트가 최대 3배까지 불어나 컨텍스트 초과 위험이 커지므로, 청크
 * 길이가 chunk-size에 근접한(=분할기가 강제로 잘랐을 가능성이 큰) 경우에만, 그것도
 * chunk-overlap 길이만큼만 선택적으로 확장한다.</p>
 */
class EgovNeighborChunkExpanderTest {

    private static final String TABLE = "document_embeddings";
    private static final int CHUNK_SIZE = 100;
    private static final int EXPANSION_CAP = 20;

    private Content contentOf(String text, String id, int index) {
        Metadata metadata = new Metadata();
        metadata.put("id", id);
        metadata.put("index", String.valueOf(index));
        return Content.from(TextSegment.from(text, metadata));
    }

    @SuppressWarnings("unchecked")
    private void stubChunk(JdbcTemplate jdbcTemplate, String id, int index, String text) {
        List<String> result = text == null ? List.of() : List.of(text);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(id), eq(String.valueOf(index))))
                .thenReturn(result);
    }

    private String repeat(String base, int totalLength) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < totalLength) {
            sb.append(base);
        }
        return sb.substring(0, totalLength);
    }

    private EgovNeighborChunkExpander expanderOf(JdbcTemplate jdbcTemplate, ContentRetriever delegate) {
        return new EgovNeighborChunkExpander(delegate, jdbcTemplate, TABLE, CHUNK_SIZE, EXPANSION_CAP);
    }

    @Test
    @DisplayName("현재 청크가 chunk-size에 근접해(=잘렸을 가능성 큼) 있으면 다음 청크의 앞부분을 " +
            "expansion-cap 길이만큼만 잘라 붙인다")
    void appendsCappedNextChunkWhenCurrentLooksTruncated() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        String nextFull = repeat("다음청크내용", 200);
        stubChunk(jdbcTemplate, "doc-1", 1, nextFull);

        String truncatedCurrent = repeat("현재청크", CHUNK_SIZE); // chunk-size에 근접 = 잘린 것으로 간주
        ContentRetriever delegate = q -> List.of(contentOf(truncatedCurrent, "doc-1", 0));
        ContentRetriever expander = expanderOf(jdbcTemplate, delegate);

        List<Content> result = expander.retrieve(Query.from("아무 질의"));

        String expected = truncatedCurrent + "\n" + nextFull.substring(0, EXPANSION_CAP);
        assertThat(result.get(0).textSegment().text()).isEqualTo(expected);
    }

    @Test
    @DisplayName("현재 청크가 chunk-size보다 훨씬 짧으면(자연스럽게 끝남) 다음 청크는 조회조차 하지 않는다")
    void doesNotFetchNextChunkWhenCurrentEndsNaturally() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        // index=0의 "이전"(index=-1)은 음수라 fetchChunkText가 DB 조회 자체를 안 하고 바로 null.

        String shortCurrent = "짧게 자연스럽게 끝난 청크";
        ContentRetriever delegate = q -> List.of(contentOf(shortCurrent, "doc-1", 0));
        ContentRetriever expander = expanderOf(jdbcTemplate, delegate);

        List<Content> result = expander.retrieve(Query.from("아무 질의"));

        assertThat(result.get(0).textSegment().text()).isEqualTo(shortCurrent);
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), eq("doc-1"), eq("1"));
    }

    @Test
    @DisplayName("이전 청크가 chunk-size에 근접해(=잘렸을 가능성 큼) 있으면 그 끝부분을 " +
            "expansion-cap 길이만큼만 잘라 앞에 붙인다")
    void appendsCappedPreviousChunkWhenPreviousLooksTruncated() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        String previousFull = repeat("이전청크내용", CHUNK_SIZE);
        stubChunk(jdbcTemplate, "doc-1", 0, previousFull);

        String shortCurrent = "짧은 다음 청크";
        ContentRetriever delegate = q -> List.of(contentOf(shortCurrent, "doc-1", 1));
        ContentRetriever expander = expanderOf(jdbcTemplate, delegate);

        List<Content> result = expander.retrieve(Query.from("아무 질의"));

        String expectedBefore = previousFull.substring(previousFull.length() - EXPANSION_CAP);
        assertThat(result.get(0).textSegment().text()).isEqualTo(expectedBefore + "\n" + shortCurrent);
    }

    @Test
    @DisplayName("이전 청크가 짧게 자연스럽게 끝났으면(=안 잘림) 앞에 아무것도 붙이지 않는다")
    void doesNotAttachPreviousChunkWhenItEndedNaturally() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubChunk(jdbcTemplate, "doc-1", 0, "짧게 자연스럽게 끝난 이전 청크");

        String shortCurrent = "짧은 다음 청크";
        ContentRetriever delegate = q -> List.of(contentOf(shortCurrent, "doc-1", 1));
        ContentRetriever expander = expanderOf(jdbcTemplate, delegate);

        List<Content> result = expander.retrieve(Query.from("아무 질의"));

        assertThat(result.get(0).textSegment().text()).isEqualTo(shortCurrent);
    }

    @Test
    @DisplayName("이웃 청크가 검색 결과 자체에 이미 있으면 중복으로 또 붙이지 않는다")
    void doesNotDuplicateNeighborAlreadyInResults() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        String truncated0 = repeat("청크0", CHUNK_SIZE);
        ContentRetriever delegate = q -> List.of(
                contentOf(truncated0, "doc-1", 0),
                contentOf("청크1", "doc-1", 1));
        ContentRetriever expander = expanderOf(jdbcTemplate, delegate);

        List<Content> result = expander.retrieve(Query.from("아무 질의"));

        assertThat(result).hasSize(2);
        // index=0이 잘린 것으로 보여 "다음(1)"을 붙이려 하지만, 1은 이미 결과에 있으므로 조회를 건너뛴다.
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), eq("doc-1"), eq("1"));
        assertThat(result.get(0).textSegment().text()).isEqualTo(truncated0);
    }

    @Test
    @DisplayName("이웃 청크를 붙여 확장해도 원래 청크의 score(Content 레벨 메타데이터)는 유지된다")
    void preservesScoreMetadataWhenExpanded() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        String nextFull = repeat("다음청크내용", 200);
        stubChunk(jdbcTemplate, "doc-1", 1, nextFull);

        String truncatedCurrent = repeat("현재청크", CHUNK_SIZE);
        Metadata metadata = new Metadata();
        metadata.put("id", "doc-1");
        metadata.put("index", "0");
        Content scoredContent = Content.from(
                TextSegment.from(truncatedCurrent, metadata),
                Map.of(ContentMetadata.SCORE, 0.87));
        ContentRetriever delegate = q -> List.of(scoredContent);
        ContentRetriever expander = expanderOf(jdbcTemplate, delegate);

        List<Content> result = expander.retrieve(Query.from("아무 질의"));

        // 텍스트는 이웃 청크가 붙어 원본과 달라졌어야(확장이 실제로 일어났어야) 이 테스트가 유효하다.
        assertThat(result.get(0).textSegment().text()).isNotEqualTo(truncatedCurrent);
        assertThat(result.get(0).metadata().get(ContentMetadata.SCORE)).isEqualTo(0.87);
    }

    @Test
    @DisplayName("id/index 메타데이터가 없는 청크는 그대로 반환한다(하이브리드 lexical 단독 결과 등)")
    void returnsOriginalWhenMetadataMissing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ContentRetriever delegate = q -> List.of(Content.from(TextSegment.from("메타데이터 없는 청크")));
        ContentRetriever expander = expanderOf(jdbcTemplate, delegate);

        List<Content> result = expander.retrieve(Query.from("아무 질의"));

        assertThat(result.get(0).textSegment().text()).isEqualTo("메타데이터 없는 청크");
    }
}
