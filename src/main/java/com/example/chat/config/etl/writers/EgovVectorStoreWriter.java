package com.example.chat.config.etl.writers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

    @Value("${pgvector.table-name}")
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

                stored += batch.size();
                progressCallback.accept(stored);
            }
        } catch (Exception e) {
            log.error("벡터 저장소 저장 중 오류 발생", e);
            throw new RuntimeException("벡터 저장소 저장 중 오류 발생", e);
        }
    }

    /**
     * 원본 파일이 삭제된 문서의 임베딩을 벡터 저장소에서 제거한다.
     * PgVectorEmbeddingStore가 청크마다 저장한 metadata(JSON)의 {@code id} 값이
     * {@link com.example.chat.entity.DocumentHashEntity#getDocId()}와 동일한 값을
     * 공유하므로, 이 값으로 파일 단위 삭제가 가능하다.
     */
    public int deleteByDocIds(Collection<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return 0;
        }
        List<String> ids = new ArrayList<>(docIds);
        String sql = "DELETE FROM " + tableName + " WHERE metadata::jsonb ->> 'id' = ?";
        List<Object[]> batchArgs = ids.stream().map(id -> new Object[]{id}).toList();
        int[] results = jdbcTemplate.batchUpdate(sql, batchArgs);
        int deleted = Arrays.stream(results).sum();
        log.info("삭제된 파일 {}개에 대한 임베딩 {}건 삭제 완료", ids.size(), deleted);
        return deleted;
    }

    /** 인덱스 초기화용: 벡터 저장소의 모든 임베딩을 삭제한다. */
    public void deleteAll() {
        log.info("벡터 저장소 전체 삭제 시작 (테이블: {})", tableName);
        jdbcTemplate.execute("DELETE FROM " + tableName);
        log.info("벡터 저장소 전체 삭제 완료");
    }
}
