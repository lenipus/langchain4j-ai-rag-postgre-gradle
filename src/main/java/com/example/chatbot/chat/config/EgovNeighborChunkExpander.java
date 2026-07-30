package com.example.chatbot.chat.config;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 검색된 각 청크에서, 경계에서 잘린 것으로 보이는 쪽에만 바로 앞/뒤 청크(같은 문서,
 * index -1/+1)의 일부를 이어붙이는 후처리기.
 *
 * <p>{@code document.chunk-size}로 고정 길이 분할을 하다 보면, 표의 한 행이나 규정 항목
 * 하나가 청크 경계에서 그대로 잘리는 경우가 있다(chunk-overlap이 그 잘린 내용보다 짧으면
 * 겹침만으로는 못 살아남는다 - 예: 결재라인 안내 문서의 "출장" 항목이 딱 청크 크기에서
 * 끊긴 사례). 그렇다고 모든 청크에 무조건 앞뒤를 다 붙이면 컨텍스트가 최대 3배까지
 * 불어나 컨텍스트 초과(exceed_context_size_error) 위험이 커지므로, 실제로 잘렸을 가능성이
 * 큰 쪽에만, 그것도 필요한 만큼(아래 {@code expansionCapChars})만 붙인다.</p>
 *
 * <p><b>어느 쪽이 잘렸는지 판단하는 기준</b>: 청크 길이가 {@code chunk-size}에 거의
 * 근접해 있다는 것(임계값 {@value #TRUNCATION_THRESHOLD_RATIO} 이상)은 그 청크가 문단 등
 * 자연스러운 경계가 아니라 분할기가 최대 길이에 걸려 강제로 잘랐다는 신호다.
 * <ul>
 *   <li><b>뒤(next) 청크</b>는 <i>현재</i> 청크 길이가 그 임계값 이상일 때만 조회 - 현재
 *       청크 자신이 끝에서 잘렸다는 뜻이므로 이어지는 내용이 다음 청크에 있을 가능성이 크다.</li>
 *   <li><b>앞(previous) 청크</b>는 <i>이전</i> 청크 길이가 그 임계값 이상일 때만 사용 - 이전
 *       청크가 끝에서 잘렸다는 뜻이므로 지금 청크의 시작이 그 잘린 내용의 이어짐일 수 있다.</li>
 * </ul>
 * 붙일 때도 이웃 청크 전체가 아니라 경계에 가까운 {@code expansionCapChars}(설정값
 * {@code rag.retrieval.neighbor-expansion-chars}) 길이만큼만(앞 청크는 끝부분, 뒤 청크는
 * 앞부분) 잘라 붙인다. 인덱싱 시 {@code document.chunk-overlap}과는 별개 값이다 - 그
 * 쪽은 원래 용도(분할 시 겹침) 그대로 두고, 이 검색-시점 확장량만 따로 조정할 수 있게
 * 분리했다.</p>
 */
@Slf4j
public class EgovNeighborChunkExpander implements ContentRetriever {

    /**
     * 청크 길이가 chunk-size의 이 비율 이상이면 "분할기가 최대 길이에 걸려 강제로 잘랐다"고
     * 본다. 1.0이 아니라 여유를 두는 이유: 오버랩/서브 스플리터 재조합 과정에서 실제 길이가
     * chunk-size보다 살짝 짧게 끝나는 경우가 흔해서, 너무 빡빡하게(예: 0.99) 잡으면 실제로
     * 잘린 청크도 놓치기 쉽다.
     */
    private static final double TRUNCATION_THRESHOLD_RATIO = 0.9;

    private final ContentRetriever delegate;
    private final JdbcTemplate jdbcTemplate;
    private final String tableName;
    private final int chunkSize;
    private final int expansionCapChars;

    /**
     * @param delegate          실제 벡터 검색을 수행하는 하위 ContentRetriever
     * @param jdbcTemplate      이웃 청크(id/index 기반) 조회용
     * @param tableName         임베딩 테이블명({@code pgvector.table-name})
     * @param chunkSize         인덱싱 시 청크 최대 길이({@code document.chunk-size}) - 잘림 판단 기준
     * @param expansionCapChars 이웃 청크에서 실제로 덧붙일 최대 길이({@code rag.retrieval.neighbor-expansion-chars},
     *                          {@code document.chunk-overlap}과 별개)
     */
    public EgovNeighborChunkExpander(ContentRetriever delegate, JdbcTemplate jdbcTemplate, String tableName,
                                     int chunkSize, int expansionCapChars) {
        this.delegate = delegate;
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
        this.chunkSize = chunkSize;
        this.expansionCapChars = expansionCapChars;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> contents = delegate.retrieve(query);
        if (contents.isEmpty()) {
            return contents;
        }

        Set<String> alreadyPresent = new HashSet<>();
        for (Content content : contents) {
            String key = keyOf(content.textSegment().metadata());
            if (key != null) {
                alreadyPresent.add(key);
            }
        }

        List<Content> expanded = new ArrayList<>(contents.size());
        for (Content content : contents) {
            expanded.add(expand(content, alreadyPresent));
        }
        return expanded;
    }

    private boolean looksTruncated(String text) {
        return text != null && text.length() >= chunkSize * TRUNCATION_THRESHOLD_RATIO;
    }

    private Content expand(Content content, Set<String> alreadyPresent) {
        TextSegment segment = content.textSegment();
        Metadata metadata = segment.metadata();
        String id = metadata == null ? null : metadata.getString("id");
        Integer index = parseIndex(metadata);
        if (id == null || id.isBlank() || index == null) {
            return content;
        }

        String currentText = segment.text();

        // 앞: "이전" 청크 자신이 끝에서 잘렸을 때만(그래야 지금 청크 시작이 그 잘림의 이어짐).
        String before = null;
        if (!alreadyPresent.contains(id + "#" + (index - 1))) {
            String previousText = fetchChunkText(id, index - 1);
            if (looksTruncated(previousText)) {
                before = lastChars(previousText, expansionCapChars);
            }
        }

        // 뒤: "현재" 청크가 끝에서 잘렸을 때만(그래야 다음 청크가 그 잘림의 이어짐).
        String after = null;
        if (looksTruncated(currentText) && !alreadyPresent.contains(id + "#" + (index + 1))) {
            String nextText = fetchChunkText(id, index + 1);
            if (nextText != null) {
                after = firstChars(nextText, expansionCapChars);
            }
        }

        if (before == null && after == null) {
            return content;
        }

        log.debug("이웃 청크 확장 - id: {}, index: {}, 앞: {}, 뒤: {}", id, index, before != null, after != null);

        StringBuilder sb = new StringBuilder();
        if (before != null) {
            sb.append(before).append("\n");
        }
        sb.append(currentText);
        if (after != null) {
            sb.append("\n").append(after);
        }

        // content.metadata()(SCORE 등 Content 레벨 메타데이터, TextSegment.metadata()와는 별개)를
        // 그대로 넘겨야 한다 - 안 넘기면 확장된 청크는 rag_retrieval_logs에 score가 항상 null로
        // 찍힌다(원래 유사도 점수가 있었어도 새 Content로 갈아끼우면서 유실됨).
        return Content.from(TextSegment.from(sb.toString(), metadata), content.metadata());
    }

    private String lastChars(String text, int n) {
        return text.length() <= n ? text : text.substring(text.length() - n);
    }

    private String firstChars(String text, int n) {
        return text.length() <= n ? text : text.substring(0, n);
    }

    private Integer parseIndex(Metadata metadata) {
        if (metadata == null) {
            return null;
        }
        String raw = metadata.getString("index");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String fetchChunkText(String id, int index) {
        if (index < 0) {
            return null;
        }
        String sql = "SELECT text FROM " + tableName
                + " WHERE metadata::jsonb ->> 'id' = ? AND metadata::jsonb ->> 'index' = ? LIMIT 1";
        List<String> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> rs.getString("text"), id, String.valueOf(index));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String keyOf(Metadata metadata) {
        if (metadata == null) {
            return null;
        }
        String id = metadata.getString("id");
        String index = metadata.getString("index");
        if (id == null || id.isBlank() || index == null || index.isBlank()) {
            return null;
        }
        return id + "#" + index;
    }
}
