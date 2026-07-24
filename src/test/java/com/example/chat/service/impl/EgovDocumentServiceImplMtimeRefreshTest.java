package com.example.chat.service.impl;

import com.example.chat.config.etl.readers.EgovDocumentScanner;
import com.example.chat.config.etl.transformers.EgovContentFormatTransformer;
import com.example.chat.config.etl.transformers.EgovEnhancedDocumentTransformer;
import com.example.chat.config.etl.writers.EgovVectorStoreWriter;
import com.example.chat.entity.DocumentHashEntity;
import com.example.chat.repository.DocumentHashRepository;
import com.example.chat.util.DocumentHashUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * {@link EgovDocumentServiceImpl#loadDocumentsAsync()}가, {@link EgovDocumentScanner}의 mtime
 * 사전 체크 때문에 다시 파싱된 파일이라도 실제 텍스트 내용은 안 바뀐 경우 mtime만 갱신하고
 * 재임베딩은 건너뛰는지 검증한다.
 *
 * <p>이 갱신이 없으면 "mtime이 저장된 값과 다르다(scanner 판단) → 파싱함 → 해시 비교하니
 * 내용은 안 바뀜(service 판단) → 근데 mtime을 저장 안 함 → 다음 실행 때도 다시 mtime이
 * 다르다고 판단해 또 파싱" 무한 반복이 생긴다 - 실제로 겪은 버그(재시작할 때마다 모든
 * 파일이 매번 재파싱됨).</p>
 */
class EgovDocumentServiceImplMtimeRefreshTest {

    private final EgovDocumentScanner scanner = mock(EgovDocumentScanner.class);
    private final EgovContentFormatTransformer formatTransformer = mock(EgovContentFormatTransformer.class);
    private final EgovEnhancedDocumentTransformer enhancedTransformer = mock(EgovEnhancedDocumentTransformer.class);
    private final EgovVectorStoreWriter vectorStoreWriter = mock(EgovVectorStoreWriter.class);
    private final DocumentHashRepository documentHashRepository = mock(DocumentHashRepository.class);

    private final EgovDocumentServiceImpl service = new EgovDocumentServiceImpl(
            scanner, formatTransformer, enhancedTransformer, vectorStoreWriter,
            documentHashRepository, Runnable::run);

    @Test
    @DisplayName("mtime 체크로 다시 파싱됐지만 텍스트 내용이 안 바뀐 문서는 mtime만 갱신하고 재임베딩하지 않는다")
    void refreshesSourceLastModifiedWithoutReembeddingWhenContentUnchanged() {
        String text = "안 바뀐 문서 본문";
        String hash = DocumentHashUtil.calculateHash(text);

        Metadata metadata = Metadata.from("id", "doc-1");
        metadata.put("source_last_modified", "2000");
        Document document = Document.from(text, metadata);

        // 이미 저장돼있던 해시(같은 값 = 내용 안 바뀜)이지만, mtime은 예전 값(1000)이라
        // scanner가 "다르다"고 보고 다시 파싱시킨 상황을 흉내낸다.
        DocumentHashEntity existing = new DocumentHashEntity("doc-1", hash);
        existing.setSourceLastModified(1000L);

        when(scanner.scanAll()).thenReturn(new EgovDocumentScanner.ScanResult(List.of(document), Set.of("doc-1")));
        when(documentHashRepository.findAllDocIds()).thenReturn(List.of("doc-1"));
        when(documentHashRepository.findById("doc-1")).thenReturn(Optional.of(existing));

        CompletableFuture<Integer> future = service.loadDocumentsAsync();

        assertThat(future.join()).isZero(); // 실제로 재임베딩한 파일은 0개
        verify(vectorStoreWriter, never()).write(anyList());
        verify(documentHashRepository).save(argThat(entity ->
                "doc-1".equals(entity.getDocId()) && Long.valueOf(2000L).equals(entity.getSourceLastModified())));
    }
}
