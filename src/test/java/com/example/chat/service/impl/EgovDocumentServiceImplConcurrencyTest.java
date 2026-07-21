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
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@code document.processing.concurrency} 설정에 따른 동작을 검증한다.
 *
 * <p>실측(Ollama 서버에 동시 4개 임베딩 요청 시 순차 대비 약 3.5배 단축, 실패 없음)을 근거로
 * 1~4 범위만 허용하고 벗어나면 clamp한다. 여러 파일을 동시 처리해도 전부 빠짐없이
 * 처리되는지(스레드 안전성)도 함께 확인한다.</p>
 */
class EgovDocumentServiceImplConcurrencyTest {

    private final EgovDocumentScanner scanner = mock(EgovDocumentScanner.class);
    private final EgovContentFormatTransformer formatTransformer = mock(EgovContentFormatTransformer.class);
    private final EgovEnhancedDocumentTransformer enhancedTransformer = mock(EgovEnhancedDocumentTransformer.class);
    private final EgovVectorStoreWriter vectorStoreWriter = mock(EgovVectorStoreWriter.class);
    private final DocumentHashRepository documentHashRepository = mock(DocumentHashRepository.class);

    // 실제 스레드 풀을 쓰는 경로를 검증해야 하므로, 여기서는 진짜 비동기 executor를 쓴다.
    private final EgovDocumentServiceImpl service = new EgovDocumentServiceImpl(
            scanner, formatTransformer, enhancedTransformer, vectorStoreWriter,
            documentHashRepository, Executors.newSingleThreadExecutor());

    private Document doc(String id, String text) {
        return Document.from(text, Metadata.from("id", id));
    }

    private int clampConcurrency(int configured) throws Exception {
        Method m = EgovDocumentServiceImpl.class.getDeclaredMethod("clampConcurrency", int.class);
        m.setAccessible(true);
        return (int) m.invoke(service, configured);
    }

    @Test
    @DisplayName("0 이하로 설정하면 1로 보정한다")
    void clampsBelowOneToOne() throws Exception {
        assertThat(clampConcurrency(0)).isEqualTo(1);
        assertThat(clampConcurrency(-3)).isEqualTo(1);
    }

    @Test
    @DisplayName("최대값(4)을 넘으면 4로 보정한다")
    void clampsAboveMaxToMax() throws Exception {
        assertThat(clampConcurrency(5)).isEqualTo(4);
        assertThat(clampConcurrency(100)).isEqualTo(4);
    }

    @Test
    @DisplayName("1~4 범위 안이면 그대로 사용한다")
    void keepsValueWithinRange() throws Exception {
        assertThat(clampConcurrency(1)).isEqualTo(1);
        assertThat(clampConcurrency(2)).isEqualTo(2);
        assertThat(clampConcurrency(4)).isEqualTo(4);
    }

    @Test
    @DisplayName("동시 처리(concurrency=3)에서도 모든 파일이 빠짐없이 처리된다")
    void allFilesProcessedUnderConcurrency() {
        ReflectionTestUtils.setField(service, "configuredConcurrency", 3);

        List<Document> allDocuments = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            allDocuments.add(doc("doc-" + i, "문서 " + i + " 본문"));
        }

        when(scanner.scanAll()).thenReturn(allDocuments);
        when(documentHashRepository.findAllDocIds()).thenReturn(List.of());
        when(documentHashRepository.findById(anyString())).thenReturn(Optional.empty());
        when(formatTransformer.transform(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(enhancedTransformer.transformAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        Integer result = service.loadDocumentsAsync().join();

        assertThat(result).isEqualTo(9);
        for (int i = 0; i < 9; i++) {
            String id = "doc-" + i;
            verify(documentHashRepository).save(argThat(entity -> id.equals(entity.getDocId())));
        }
        verify(vectorStoreWriter, times(9)).write(anyList());
    }
}
