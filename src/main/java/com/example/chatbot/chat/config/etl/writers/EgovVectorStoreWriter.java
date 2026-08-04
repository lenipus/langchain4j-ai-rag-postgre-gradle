package com.example.chatbot.chat.config.etl.writers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.function.IntConsumer;

/**
 * 벡터 저장소 Writer
 * 문서를 임베딩하여 PGVector에 저장
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovVectorStoreWriter {

    private static final int BATCH_SIZE = 5;
    private static final ObjectMapper METADATA_OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    /** PostgreSQL JSON 컬럼에 저장할 메타데이터 키 순서. */
    static final List<String> METADATA_FIELD_ORDER = List.of(
            "id",
            "index",
            "file_name",
            "source",
            "source_last_modified",
            "type",
            "page_number",
            "original_length",
            "content_length",
            "normalized_length",
            "special_chars_cleaned",
            "normalization_applied",
            "code_blocks_removed");

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

    @Value("${pgvector.table-name:document_embeddings}")
    private String tableName;

    /**
     * 문서를 임베딩하여 벡터 저장소에 저장
     */
    public void write(List<Document> documents) {
        write(documents, stored -> { });
    }

    /**
     * 문서를 배치({@value #BATCH_SIZE}개) 단위로 임베딩하여 벡터 저장소에 저장하고,
     * 배치가 끝날 때마다 누적 저장 개수를 콜백으로 알린다(진행률 표시용).
     */
    public void write(List<Document> documents, IntConsumer progressCallback) {
        if (documents.isEmpty()) {
            log.warn("저장할 문서가 없습니다.");
            return;
        }

        // 호출부(EgovDocumentServiceImpl)가 파일 단위로 이미 진행 상황을 로그로 남기므로,
        // 여기서는 배치별 상세 로그를 남기지 않고 실패 시에만 로그를 남긴다.
        try {
            int total = documents.size();
            int stored = 0;

            for (int i = 0; i < total; i += BATCH_SIZE) {
                List<Document> batch = documents.subList(i, Math.min(i + BATCH_SIZE, total));

                List<TextSegment> segments = new ArrayList<>();
                for (Document doc : batch) {
                    segments.add(TextSegment.from(doc.text(), doc.metadata()));
                }

                // 배치 전체를 한 번의 임베딩 API 호출로 처리한다(건별 호출 대비 네트워크 왕복 감소).
                List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

                // 벡터 저장소에 배치 저장
                embeddingStore.addAll(embeddings, segments);
                normalizeStoredMetadata(batch);

                stored += batch.size();
                progressCallback.accept(stored);
            }
        } catch (Exception e) {
            log.error("벡터 저장소 저장 중 오류 발생", e);
            throw new RuntimeException("벡터 저장소 저장 중 오류 발생", e);
        }
    }

    /**
     * LangChain4j Metadata와 내부 문자열 변환이 모두 HashMap을 사용하므로 put 순서만으로는
     * JSON 키 순서를 보존할 수 없다. 저장 직후 요청된 순서의 LinkedHashMap을 pretty JSON
     * 문자열로 직렬화해 다시 저장한다. 기본 PGVector 스키마의 metadata 타입은 공백과
     * 순서를 보존하는 JSON이다(JSONB는 공백과 객체 키 순서를 보존하지 않는다).
     */
    private void normalizeStoredMetadata(List<Document> batch) {
        if (!SAFE_TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalStateException("허용되지 않는 PGVector 테이블명: " + tableName);
        }

        String sql = "UPDATE " + tableName
                + " SET metadata = CAST(? AS JSON)"
                + " WHERE metadata ->> 'id' = ? AND metadata ->> 'index' = ?";

        List<Object[]> parameters = batch.stream()
                .map(document -> new Object[] {
                        formatMetadata(document.metadata()),
                        document.metadata().getString("id"),
                        document.metadata().getString("index")
                })
                .toList();
        int updated = java.util.Arrays.stream(jdbcTemplate.batchUpdate(sql, parameters)).sum();
        if (updated != batch.size()) {
            log.warn("메타데이터 순서 정규화 건수 불일치: 저장={}개, 정규화={}개", batch.size(), updated);
        }
    }

    static String formatMetadata(Metadata metadata) {
        Map<String, Object> values = metadata.toMap();
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String key : METADATA_FIELD_ORDER) {
            ordered.put(key, values.get(key));
        }
        try {
            return METADATA_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ordered);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("메타데이터 JSON 직렬화 실패", e);
        }
    }

    /**
     * 원본 파일이 삭제된 문서의 임베딩을 벡터 저장소에서 제거한다.
     * PgVectorEmbeddingStore가 청크마다 저장한 metadata의 {@code id} 값이
     * {@link com.example.chatbot.chat.entity.DocumentHashEntity#getDocId()}와 동일한 값을
     * 공유하므로, 이 값으로 파일 단위 삭제가 가능하다.
     *
     * <p>예전엔 {@code JdbcTemplate}로 {@code metadata::jsonb ->> 'id' = ?} raw SQL을
     * 직접 실행했는데, {@link EmbeddingStore#removeAll(Filter)}가 메타데이터 필터 삭제를
     * 이미 지원해서 그걸 쓰는 게 더 낫다 - 메타데이터 저장 방식(JSON/JSONB/컬럼별)이 바뀌어도
     * 라이브러리가 알아서 맞는 조건을 만들어주므로 테이블 구조 가정을 우리 코드에 두지
     * 않아도 된다.</p>
     */
    public void deleteByDocIds(Collection<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }
        Filter filter = MetadataFilterBuilder.metadataKey("id").isIn(docIds);
        embeddingStore.removeAll(filter);
        log.info("삭제된 파일 {}개에 대한 임베딩 삭제 완료", docIds.size());
    }

    /** 인덱스 초기화용: 벡터 저장소의 모든 임베딩을 삭제한다. */
    public void deleteAll() {
        log.info("벡터 저장소 전체 삭제 시작");
        embeddingStore.removeAll();
        log.info("벡터 저장소 전체 삭제 완료");
    }
}
