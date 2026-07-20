package com.example.chat.config.etl.writers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * {@link EgovVectorStoreWriter}가 배치 단위로 임베딩할 때 문서마다 개별
 * {@code embed()} 호출을 하지 않고, 배치 전체를 {@code embedAll()} 한 번으로
 * 처리하는지 검증한다. 건별 호출은 네트워크 왕복이 배치 크기만큼 늘어나
 * 인덱싱 속도가 문서 크기와 무관하게 느려지는 원인이었다.
 */
class EgovVectorStoreWriterTest {

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    @SuppressWarnings("unchecked")
    private final EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EgovVectorStoreWriter writer = new EgovVectorStoreWriter(embeddingStore, embeddingModel, jdbcTemplate);

    {
        ReflectionTestUtils.setField(writer, "tableName", "document_embeddings");
    }

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
    @DisplayName("deleteByDocIds는 각 doc_id를 metadata::jsonb ->> 'id' 조건으로 배치 삭제하고, 삭제된 행 수 합계를 반환한다")
    @SuppressWarnings("unchecked")
    void deleteByDocIdsBatchDeletesByMetadataId() {
        when(jdbcTemplate.batchUpdate(anyString(), anyList())).thenReturn(new int[] { 1, 1 });

        int deleted = writer.deleteByDocIds(List.of("doc-a", "doc-b"));

        assertThat(deleted).isEqualTo(2);

        ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate).batchUpdate(contains("metadata::jsonb ->> 'id'"), captor.capture());
        List<Object[]> batchArgs = captor.getValue();
        assertThat(batchArgs).hasSize(2);
        assertThat(batchArgs.get(0)).containsExactly("doc-a");
        assertThat(batchArgs.get(1)).containsExactly("doc-b");
    }

    @Test
    @DisplayName("deleteByDocIds에 빈 목록을 주면 JdbcTemplate을 호출하지 않는다")
    void deleteByDocIdsSkipsWhenEmpty() {
        int deleted = writer.deleteByDocIds(List.of());

        assertThat(deleted).isZero();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("deleteAll은 테이블 전체에 대해 DELETE를 실행한다")
    void deleteAllExecutesUnconditionalDelete() {
        writer.deleteAll();

        verify(jdbcTemplate).execute("DELETE FROM document_embeddings");
    }
}
