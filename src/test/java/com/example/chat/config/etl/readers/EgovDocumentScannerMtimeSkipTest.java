package com.example.chat.config.etl.readers;

import com.example.chat.entity.DocumentHashEntity;
import com.example.chat.repository.DocumentHashRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EgovDocumentScanner#scanAll()}가 파일의 mtime이 DB에 저장된 값과 같으면 비싼
 * 파싱({@link EgovDocumentReader#parse})을 건너뛰는지 검증한다.
 *
 * <p>안 바뀐 PDF도 매번 전체 텍스트 추출을 다시 하던 낭비(사용자가 재인덱싱 로그에서
 * 직접 확인)를 없애기 위해 도입한 최적화 - 파싱 전에 {@link EgovDocumentReader#computeDocId}로
 * ID만 먼저 계산해 DB의 mtime과 비교한다.</p>
 */
class EgovDocumentScannerMtimeSkipTest {

    @TempDir
    Path tempDir;

    private final DocumentHashRepository documentHashRepository = mock(DocumentHashRepository.class);

    /** 실제 리더(PDF 등) 대신, 파싱 호출 횟수를 셀 수 있는 최소 구현. */
    private static class CountingTxtReader implements EgovDocumentReader {
        final AtomicInteger parseCallCount = new AtomicInteger(0);

        @Override
        public Set<String> supportedExtensions() {
            return Set.of("txt");
        }

        @Override
        public List<Document> parse(Resource resource) {
            parseCallCount.incrementAndGet();
            String id = computeDocId(resource, resource.getFilename());
            return List.of(Document.from("내용", Metadata.from("id", id)));
        }

        @Override
        public String computeDocId(Resource resource, String filename) {
            return "txt-" + filename + "_1";
        }
    }

    private EgovDocumentScanner newScanner(CountingTxtReader reader) {
        EgovDocumentScanner scanner = new EgovDocumentScanner(List.of(reader), documentHashRepository);
        ReflectionTestUtils.setField(scanner, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(scanner, "allowedUploadExtensions", new String[]{"txt"});
        return scanner;
    }

    private long writeFileAndGetModifiedTime(String filename, String content) throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, content);
        return file.toFile().lastModified();
    }

    @Test
    @DisplayName("DB에 기록이 없는 새 파일은 파싱한다")
    void parsesNewFileWithNoStoredRecord() throws IOException {
        writeFileAndGetModifiedTime("new.txt", "hello");
        when(documentHashRepository.findById(eq("txt-new.txt_1"))).thenReturn(Optional.empty());

        CountingTxtReader reader = new CountingTxtReader();
        EgovDocumentScanner.ScanResult result = newScanner(reader).scanAll();

        assertThat(reader.parseCallCount.get()).isEqualTo(1);
        assertThat(result.documentsToProcess()).hasSize(1);
        assertThat(result.currentDocIds()).containsExactly("txt-new.txt_1");
    }

    @Test
    @DisplayName("mtime이 DB에 저장된 값과 같으면 파싱을 건너뛴다")
    void skipsParsingWhenModifiedTimeUnchanged() throws IOException {
        long modifiedTime = writeFileAndGetModifiedTime("unchanged.txt", "hello");
        DocumentHashEntity stored = new DocumentHashEntity("txt-unchanged.txt_1", "somehash");
        stored.setSourceLastModified(modifiedTime);
        when(documentHashRepository.findById(eq("txt-unchanged.txt_1"))).thenReturn(Optional.of(stored));

        CountingTxtReader reader = new CountingTxtReader();
        EgovDocumentScanner.ScanResult result = newScanner(reader).scanAll();

        assertThat(reader.parseCallCount.get()).isZero();
        assertThat(result.documentsToProcess()).isEmpty();
        // 파싱은 건너뛰어도 "현재 존재하는 파일"로는 여전히 집계돼야 삭제 정리 로직이
        // 이 파일을 "삭제됨"으로 오판하지 않는다.
        assertThat(result.currentDocIds()).containsExactly("txt-unchanged.txt_1");
    }

    @Test
    @DisplayName("mtime이 DB에 저장된 값과 다르면(파일이 바뀜) 다시 파싱한다")
    void parsesWhenModifiedTimeDiffers() throws IOException {
        writeFileAndGetModifiedTime("changed.txt", "hello");
        DocumentHashEntity stored = new DocumentHashEntity("txt-changed.txt_1", "oldhash");
        stored.setSourceLastModified(1L); // 실제 파일의 mtime과 다른 값
        when(documentHashRepository.findById(eq("txt-changed.txt_1"))).thenReturn(Optional.of(stored));

        CountingTxtReader reader = new CountingTxtReader();
        EgovDocumentScanner.ScanResult result = newScanner(reader).scanAll();

        assertThat(reader.parseCallCount.get()).isEqualTo(1);
        assertThat(result.documentsToProcess()).hasSize(1);
    }

    @Test
    @DisplayName("DB에 기록은 있지만 mtime이 없던(이 기능 도입 전) 레코드는 안전하게 다시 파싱한다")
    void parsesWhenStoredModifiedTimeIsNull() throws IOException {
        writeFileAndGetModifiedTime("legacy.txt", "hello");
        DocumentHashEntity stored = new DocumentHashEntity("txt-legacy.txt_1", "oldhash");
        // sourceLastModified를 설정하지 않음 - 이 기능 도입 전 저장된 레코드를 흉내낸다.
        when(documentHashRepository.findById(eq("txt-legacy.txt_1"))).thenReturn(Optional.of(stored));

        CountingTxtReader reader = new CountingTxtReader();
        EgovDocumentScanner.ScanResult result = newScanner(reader).scanAll();

        assertThat(reader.parseCallCount.get()).isEqualTo(1);
    }
}
