package com.example.chat.config.etl.writers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
        log.info("벡터 저장소에 {}개 문서 저장 시작", documents.size());

        if (documents.isEmpty()) {
            log.warn("저장할 문서가 없습니다.");
            return;
        }

        // 문서 정보 로깅
        for (int i = 0; i < Math.min(documents.size(), 3); i++) {
            Document doc = documents.get(i);
            log.debug("문서 {}: ID={}, 크기={}바이트",
                    i, doc.metadata().getString("id"), doc.text().length());
        }

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
                log.info("벡터 저장소 저장 진행률: {}/{}", stored, total);
            }

            log.info("벡터 저장소에 {}개 문서 저장 완료", documents.size());
        } catch (Exception e) {
            log.error("벡터 저장소 저장 중 오류 발생", e);
            throw new RuntimeException("벡터 저장소 저장 중 오류 발생", e);
        }
    }
}
