package com.example.chat.service.impl;

import com.example.chat.config.etl.readers.EgovDocumentScanner;
import com.example.chat.config.etl.transformers.EgovContentFormatTransformer;
import com.example.chat.config.etl.transformers.EgovEnhancedDocumentTransformer;
import com.example.chat.config.etl.writers.EgovVectorStoreWriter;
import com.example.chat.repository.DocumentHashRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EgovDocumentServiceImpl#getStatusResponse()}의 {@code hasDocuments}가
 * {@code totalCount}(이번 인덱싱 실행에서 파싱이 필요했던 파일 수)가 아니라, 실제 저장된
 * 해시 레코드 수({@link DocumentHashRepository#count()})로 판단되는지 검증한다.
 *
 * <p>mtime 최적화 도입 전에는 totalCount가 항상 "스캔된 전체 파일 수"였어서
 * {@code totalCount > 0}로 hasDocuments를 유추해도 문제가 없었다. 하지만 이제 안 바뀐
 * 파일은 파싱 자체를 건너뛰므로, 이미 문서가 잔뜩 색인돼 있어도 이번 실행에서 처리할 게
 * 하나도 없으면 totalCount가 0이 되어 화면에 "문서가 없습니다"로 잘못 표시되는 회귀가
 * 실제로 발생했다.</p>
 */
class EgovDocumentServiceImplHasDocumentsTest {

    private final EgovDocumentScanner scanner = mock(EgovDocumentScanner.class);
    private final EgovContentFormatTransformer formatTransformer = mock(EgovContentFormatTransformer.class);
    private final EgovEnhancedDocumentTransformer enhancedTransformer = mock(EgovEnhancedDocumentTransformer.class);
    private final EgovVectorStoreWriter vectorStoreWriter = mock(EgovVectorStoreWriter.class);
    private final DocumentHashRepository documentHashRepository = mock(DocumentHashRepository.class);

    private final EgovDocumentServiceImpl service = new EgovDocumentServiceImpl(
            scanner, formatTransformer, enhancedTransformer, vectorStoreWriter,
            documentHashRepository, Runnable::run);

    @Test
    @DisplayName("totalCount가 0이어도(이번엔 처리할 게 없어도) 저장된 해시 레코드가 있으면 hasDocuments는 true다")
    void hasDocumentsIsTrueWhenHashRecordsExistEvenIfNothingToProcessThisRun() {
        when(documentHashRepository.count()).thenReturn(103L);

        assertThat(service.getTotalCount()).isZero(); // 아직 인덱싱을 한 번도 안 돌린 초기 상태
        assertThat(service.getStatusResponse().hasDocuments()).isTrue();
    }

    @Test
    @DisplayName("저장된 해시 레코드가 하나도 없으면 hasDocuments는 false다")
    void hasDocumentsIsFalseWhenNoHashRecordsExist() {
        when(documentHashRepository.count()).thenReturn(0L);

        assertThat(service.getStatusResponse().hasDocuments()).isFalse();
    }
}
