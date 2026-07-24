package com.example.chat.config.etl.writers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link EgovVectorStoreWriter}가 배치 단위로 임베딩할 때 문서마다 개별
 * {@code embed()} 호출을 하지 않고, 배치 전체를 {@code embedAll()} 한 번으로
 * 처리하는지, 그리고 삭제 관련 메서드가 {@link EmbeddingStore}의 삭제 API를
 * 올바르게 호출하는지 검증한다.
 */
class EgovVectorStoreWriterTest {

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    @SuppressWarnings("unchecked")
    private final EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
    private final EgovVectorStoreWriter writer = new EgovVectorStoreWriter(embeddingStore, embeddingModel);

    private Document doc(String id, String text) {
        Metadata metadata = Metadata.from("id", id);
        return Document.from(text, metadata);
    }

    @Test
    @DisplayName("7개 문서(배치 크기 5)를 저장하면 embedAll이 배치당 한 번(총 2번) 호출되고, 건별 embed()는 호출되지 않는다")
    void embedsInBatchesNotOneByOne() {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            documents.add(doc("doc-" + i, "내용 " + i));
        }

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<?> segments = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(Embedding.from(new float[] { 0f }));
            }
            return Response.from(embeddings);
        });

        writer.write(documents);

        verify(embeddingModel, times(2)).embedAll(anyList());
        verify(embeddingModel, never()).embed(anyString());
        verify(embeddingStore, times(2)).addAll(anyList(), anyList());
    }

    @Test
    @DisplayName("진행률 콜백은 배치 완료마다 누적 저장 개수로 호출된다")
    void reportsCumulativeProgressPerBatch() {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            documents.add(doc("doc-" + i, "내용 " + i));
        }

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<?> segments = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                embeddings.add(Embedding.from(new float[] { 0f }));
            }
            return Response.from(embeddings);
        });

        List<Integer> progress = new ArrayList<>();
        writer.write(documents, progress::add);

        assertThat(progress).containsExactly(5, 7);
    }

    @Test
    @DisplayName("deleteByDocIds는 metadata의 id가 주어진 목록에 속하는 임베딩을 삭제하는 필터로 removeAll을 호출한다")
    void deleteByDocIdsRemovesByMetadataIdFilter() {
        List<String> docIds = List.of("doc-a", "doc-b");

        writer.deleteByDocIds(docIds);

        Filter expectedFilter = MetadataFilterBuilder.metadataKey("id").isIn(docIds);
        verify(embeddingStore).removeAll(expectedFilter);
    }

    @Test
    @DisplayName("deleteByDocIds에 빈 목록을 주면 embeddingStore를 호출하지 않는다")
    void deleteByDocIdsSkipsWhenEmpty() {
        writer.deleteByDocIds(List.of());

        verifyNoInteractions(embeddingStore);
    }

    @Test
    @DisplayName("deleteAll은 embeddingStore 전체 삭제(removeAll())를 호출한다")
    void deleteAllExecutesUnconditionalDelete() {
        writer.deleteAll();

        verify(embeddingStore).removeAll();
    }
}
