package com.example.chat.service.impl;

import com.example.chat.config.etl.readers.EgovDocumentScanner;
import com.example.chat.config.etl.transformers.EgovContentFormatTransformer;
import com.example.chat.config.etl.transformers.EgovEnhancedDocumentTransformer;
import com.example.chat.config.etl.writers.EgovVectorStoreWriter;
import com.example.chat.entity.DocumentHashEntity;
import com.example.chat.repository.DocumentHashRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link EgovDocumentServiceImpl#loadDocumentsAsync()}가 원본 문서 단위로 임베딩·해시 저장을
 * 커밋하는지 검증한다.
 *
 * <p>예전에는 변경된 문서 전체의 임베딩이 다 끝난 뒤 한꺼번에 해시를 저장했다. 이 방식은
 * 처리 도중(예: 두 번째 문서 임베딩 중) 예외가 발생하면, 이미 임베딩까지 성공한 첫 번째
 * 문서조차 해시가 저장되지 않아 다음 실행에서 "변경됨"으로 다시 잡혀 재처리(및 벡터
 * 저장소 중복 삽입 위험)로 이어졌다. 지금은 문서 하나의 청크 저장이 끝나는 즉시 그
 * 문서의 해시를 저장하므로, 두 번째 문서에서 실패해도 첫 번째 문서의 해시는 이미
 * 커밋돼 있어야 한다.</p>
 */
class EgovDocumentServiceImplHashCommitTest {

    private final EgovDocumentScanner scanner = mock(EgovDocumentScanner.class);
    private final EgovContentFormatTransformer formatTransformer = mock(EgovContentFormatTransformer.class);
    private final EgovEnhancedDocumentTransformer enhancedTransformer = mock(EgovEnhancedDocumentTransformer.class);
    private final EgovVectorStoreWriter vectorStoreWriter = mock(EgovVectorStoreWriter.class);
    private final DocumentHashRepository documentHashRepository = mock(DocumentHashRepository.class);

    // 비동기 본문을 호출 스레드에서 즉시 실행해 테스트를 동기적으로 검증한다.
    private final EgovDocumentServiceImpl service = new EgovDocumentServiceImpl(
            scanner, formatTransformer, enhancedTransformer, vectorStoreWriter,
            documentHashRepository, Runnable::run);

    private Document doc(String id, String text) {
        return Document.from(text, Metadata.from("id", id));
    }

    @Test
    @DisplayName("두 번째 문서 임베딩 중 실패해도 첫 번째 문서의 해시는 이미 커밋되어 있다")
    void firstDocumentHashIsCommittedBeforeSecondDocumentFails() {
        Document doc1 = doc("doc-1", "첫 번째 문서 본문");
        Document doc2 = doc("doc-2", "두 번째 문서 본문");
        List<Document> allDocuments = List.of(doc1, doc2);

        when(scanner.scanAll()).thenReturn(allDocuments);
        when(documentHashRepository.findAllDocIds()).thenReturn(List.of());
        when(documentHashRepository.findById(anyString())).thenReturn(Optional.empty());
        when(formatTransformer.transformAll(allDocuments)).thenReturn(allDocuments);
        // 청크 분할 후에도 청크의 id는 원본 문서와 동일하게 유지된다(1문서 = 1청크로 단순화).
        when(enhancedTransformer.transformAll(allDocuments)).thenReturn(allDocuments);

        // doc-1의 청크 저장은 성공, doc-2의 청크 저장에서 예외 발생(중단 시뮬레이션).
        // List.of(...) 값 매칭 대신 실제 인자 내용을 직접 검사해, List 구현체/동등성
        // 세부사항과 무관하게 doc-2 호출만 정확히 실패시킨다.
        doAnswer(invocation -> {
            List<Document> chunks = invocation.getArgument(0);
            if (!chunks.isEmpty() && "doc-2".equals(chunks.get(0).metadata().getString("id"))) {
                throw new RuntimeException("임베딩 서버 연결 끊김");
            }
            return null;
        }).when(vectorStoreWriter).write(anyList());

        CompletableFuture<Integer> future = service.loadDocumentsAsync();

        assertThatFutureFailedWith(future, "임베딩 서버 연결 끊김");

        // doc-1은 청크 저장이 끝난 직후 해시가 커밋되어야 한다.
        verify(documentHashRepository).save(argThat(entity -> "doc-1".equals(entity.getDocId())));
        // doc-2는 청크 저장 자체가 실패했으므로 해시가 저장되면 안 된다.
        verify(documentHashRepository, never()).save(argThat(entity -> "doc-2".equals(entity.getDocId())));
    }

    private void assertThatFutureFailedWith(CompletableFuture<Integer> future, String expectedMessage) {
        assertThat(future.isCompletedExceptionally()).isTrue();
        try {
            future.get();
        } catch (ExecutionException | InterruptedException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).hasMessageContaining(expectedMessage);
            return;
        }
        throw new AssertionError("future가 예외로 완료되지 않았습니다");
    }
}
