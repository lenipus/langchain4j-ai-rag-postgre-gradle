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
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link EgovDocumentServiceImpl#loadDocumentsAsync()}가 원본 문서 단위로 임베딩·해시 저장을
 * 커밋하는지, 그리고 한 파일의 실패가 나머지 파일 처리를 막지 않는지 검증한다.
 *
 * <p>예전에는 변경된 문서 전체의 임베딩이 다 끝난 뒤 한꺼번에 해시를 저장했다. 이 방식은
 * 처리 도중(예: 두 번째 문서 임베딩 중) 예외가 발생하면, 이미 임베딩까지 성공한 첫 번째
 * 문서조차 해시가 저장되지 않아 다음 실행에서 "변경됨"으로 다시 잡혀 재처리(및 벡터
 * 저장소 중복 삽입 위험)로 이어졌다. 지금은 문서 하나의 청크 저장이 끝나는 즉시 그
 * 문서의 해시를 저장하고, 개별 파일 실패는 로그만 남기고 다음 파일로 넘어가므로(전체
 * 배치를 중단시키지 않음), 두 번째 문서에서 실패해도 첫 번째 문서의 해시는 커밋되고
 * loadDocumentsAsync() 자체는 정상적으로(예외 없이) 완료돼야 한다.</p>
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
    @DisplayName("두 번째 문서 임베딩 중 실패해도 첫 번째 문서의 해시는 커밋되고 전체 작업은 정상 완료된다")
    void firstDocumentHashIsCommittedEvenWhenSecondDocumentFails() {
        Document doc1 = doc("doc-1", "첫 번째 문서 본문");
        Document doc2 = doc("doc-2", "두 번째 문서 본문");
        List<Document> allDocuments = List.of(doc1, doc2);

        when(scanner.scanAll()).thenReturn(
                new EgovDocumentScanner.ScanResult(allDocuments, Set.of("doc-1", "doc-2")));
        when(documentHashRepository.findAllDocIds()).thenReturn(List.of());
        when(documentHashRepository.findById(anyString())).thenReturn(Optional.empty());
        // 정규화는 그대로 통과, 청크 분할은 1문서 = 1청크로 단순화(입력 문서를 그대로 청크로 반환).
        when(formatTransformer.transform(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(enhancedTransformer.transformAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

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

        // doc-2 실패는 로그로만 남고 배치 전체를 실패시키지 않는다 - 성공한 파일 수(1)로 정상 완료.
        assertThat(future.isCompletedExceptionally()).isFalse();
        assertThat(future.join()).isEqualTo(1);

        // doc-1은 청크 저장이 끝난 직후 해시가 커밋되어야 한다.
        verify(documentHashRepository).save(argThat(entity -> "doc-1".equals(entity.getDocId())));
        // doc-2는 청크 저장 자체가 실패했으므로 해시가 저장되면 안 된다.
        verify(documentHashRepository, never()).save(argThat(entity -> "doc-2".equals(entity.getDocId())));
    }
}
