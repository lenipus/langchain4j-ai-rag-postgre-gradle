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
import java.util.Map;
import java.util.Set;

/**
 * 특정 파일이 질의 표현과 임베딩상 잘 안 겹쳐서(예: "결재라인 안내자료"는 "출장 어떻게 해?"
 * 같은 질문과 의미적으로 멀어 dense 유사도 임계값을 못 넘김) 검색 후보에 아예 안 올라오는
 * 문제를 보완한다. 이런 문서는 보통 짧고 상시 참고자료 성격이라, 질의에 그 문서의 핵심
 * 업무 유형 키워드가 리터럴로 포함돼 있으면 유사도 점수와 무관하게 강제로 포함시킨다.
 *
 * <p>delegate(하이브리드든 dense든 최종 선택된 검색기)가 이미 top-k로 자른 뒤에 덧붙이므로,
 * fusion이나 length filter 단계에서 밀려날 걱정 없이 항상 살아남는다.</p>
 */
@Slf4j
public class EgovKeywordBoostContentRetriever implements ContentRetriever {

    /**
     * 파일명 -> 트리거 키워드 목록. 질의에 키워드가 하나라도 포함되면 이 파일 전체를
     * 강제로 검색 결과에 추가한다. 이런 성격의 참고자료가 늘어나면 여기 추가하면 된다.
     */
    private static final Map<String, List<String>> KEYWORD_BOOST_FILES = Map.of(
            "안내자료(결재라인).txt", List.of(
                    "시간외근무", "유연근무", "시차출퇴근", "출장", "교육출장",
                    "휴가", "공가", "병가", "외부강의", "동호회", "공적항공마일리지",
                    "지출결의서", "지출품의서", "결재", "결재라인", "열람자"
            )
    );

    private final ContentRetriever delegate;
    private final JdbcTemplate jdbcTemplate;
    private final String tableName;

    public EgovKeywordBoostContentRetriever(ContentRetriever delegate, JdbcTemplate jdbcTemplate, String tableName) {
        this.delegate = delegate;
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> contents = delegate.retrieve(query);

        String text = query.text();
        if (text == null || text.isBlank()) {
            return contents;
        }

        Set<String> alreadyPresentFiles = new HashSet<>();
        for (Content content : contents) {
            String fileName = content.textSegment().metadata().getString("file_name");
            if (fileName != null) {
                alreadyPresentFiles.add(fileName);
            }
        }

        List<Content> result = null;
        for (Map.Entry<String, List<String>> entry : KEYWORD_BOOST_FILES.entrySet()) {
            String fileName = entry.getKey();
            if (alreadyPresentFiles.contains(fileName)) {
                continue;
            }
            boolean matched = entry.getValue().stream().anyMatch(text::contains);
            if (!matched) {
                continue;
            }

            List<Content> boosted = fetchFile(fileName);
            if (boosted.isEmpty()) {
                log.warn("키워드 부스트 대상 파일을 찾지 못함 - 파일: {}", fileName);
                continue;
            }

            log.info("키워드 부스트 - 질의: [{}], 파일: {}, 청크 수: {}", text, fileName, boosted.size());
            if (result == null) {
                result = new ArrayList<>(contents);
            }
            result.addAll(boosted);
        }

        return result != null ? result : contents;
    }

    private List<Content> fetchFile(String fileName) {
        String sql = "SELECT text, metadata::jsonb ->> 'id' AS doc_id, metadata::jsonb ->> 'index' AS chunk_index"
                + " FROM " + tableName
                + " WHERE metadata::jsonb ->> 'file_name' = ?"
                + " ORDER BY (metadata::jsonb ->> 'index')::int ASC";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Metadata metadata = new Metadata();
            metadata.put("file_name", fileName);
            String docId = rs.getString("doc_id");
            if (docId != null) {
                metadata.put("id", docId);
            }
            String chunkIndex = rs.getString("chunk_index");
            if (chunkIndex != null) {
                metadata.put("index", chunkIndex);
            }
            return Content.from(TextSegment.from(rs.getString("text"), metadata));
        }, fileName);
    }
}
