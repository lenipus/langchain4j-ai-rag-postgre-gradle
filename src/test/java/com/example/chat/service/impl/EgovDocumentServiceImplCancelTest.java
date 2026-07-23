package com.example.chat.service.impl;

import com.example.chat.config.etl.readers.EgovDocumentScanner;
import com.example.chat.config.etl.transformers.EgovContentFormatTransformer;
import com.example.chat.config.etl.transformers.EgovEnhancedDocumentTransformer;
import com.example.chat.config.etl.writers.EgovVectorStoreWriter;
import com.example.chat.repository.DocumentHashRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link EgovDocumentServiceImpl#cancelProcessing()}가 파일 경계에서 재인덱싱을
 * 안전하게 중단시키는지 검증한다.
 *
 * <p>문서 단위로 임베딩+해시가 즉시 커밋되는 구조(관련: {@link EgovDocumentServiceImplHashCommitTest})라,
 * 취소해도 이미 끝난 파일까지는 안전하게 남고 나머지는 다음 실행에서 이어서 처리된다.</p>
 */
class EgovDocumentServiceImplCancelTest {

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
    @DisplayName("처리 중이 아닐 때 취소 요청은 안내 메시지를 반환하고 아무 영향도 없다")
    void cancelWhenIdleReturnsMessage() {
        String result = service.cancelProcessing();

        assertThat(result).isEqualTo("진행 중인 작업이 없습니다.");
    }

    @Test
    @DisplayName("첫 번째 문서 처리 중 취소 요청이 오면 두 번째 문서는 처리하지 않는다")
    void cancelStopsBeforeNextFile() {
        Document doc1 = doc("doc-1", "첫 번째 문서 본문");
        Document doc2 = doc("doc-2", "두 번째 문서 본문");
        List<Document> allDocuments = List.of(doc1, doc2);

        when(scanner.scanAll()).thenReturn(allDocuments);
        when(documentHashRepository.findAllDocIds()).thenReturn(List.of());
        when(documentHashRepository.findById(anyString())).thenReturn(Optional.empty());
        // 정규화는 그대로 통과, 청크 분할은 1문서 = 1청크로 단순화(입력 문서를 그대로 청크로 반환).
        when(formatTransformer.transform(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(enhancedTransformer.transformAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // doc-1 청크 저장 시점에 "취소 버튼 클릭"을 시뮬레이션한다.
        doAnswer(invocation -> {
            List<Document> chunks = invocation.getArgument(0);
            if (!chunks.isEmpty() && "doc-1".equals(chunks.get(0).metadata().getString("id"))) {
                service.cancelProcessing();
                // 취소 직후에도 아직 처리 중이므로, /api/documents/status를 폴링하는
                // 프론트가 이 시점에 취소 요청 사실을 알 수 있어야 "중지" 버튼을 다시
                // 활성화하지 않는다(관련: chat.html의 cancelRequested 분기).
                assertThat(service.isCancelRequested()).isTrue();
                assertThat(service.getStatusResponse().cancelRequested()).isTrue();
            }
            return null;
        }).when(vectorStoreWriter).write(anyList());

        CompletableFuture<Integer> future = service.loadDocumentsAsync();

        assertThat(future.join()).isEqualTo(1);
        verify(documentHashRepository).save(argThat(entity -> "doc-1".equals(entity.getDocId())));
        verify(documentHashRepository, never()).save(argThat(entity -> "doc-2".equals(entity.getDocId())));
        verify(vectorStoreWriter, never()).write(List.of(doc2));
        assertThat(service.isProcessing()).isFalse();
        // 처리가 끝나면 다음 실행을 위해 취소 플래그도 함께 초기화된다.
        assertThat(service.isCancelRequested()).isFalse();
        assertThat(service.getStatusResponse().cancelRequested()).isFalse();
    }
}
