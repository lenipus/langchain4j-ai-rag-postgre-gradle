package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.textextractor.TextExtractMethod;
import kr.dogfoot.hwpxlib.tool.textextractor.TextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

/**
 * HWPX 문서 로더
 * hwpxlib(kr.dogfoot:hwpxlib)를 사용하여 .hwpx 파일에서 텍스트를 추출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovHwpxReader implements EgovDocumentReader {

    private final DocumentIdUtil documentIdUtil;

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("hwpx");
    }

    @Override
    public List<Document> parse(Resource resource) throws Exception {
        Document document = parseHwpxDocument(resource);
        return document == null ? List.of() : List.of(document);
    }

    @Override
    public String computeDocId(Resource resource, String filename) {
        String safeFilename = documentIdUtil.uniquePathKey(resource, filename);
        return String.format("hwpx-%s_1", safeFilename);
    }

    /**
     * HWPX 파일을 파싱하여 Document 생성
     * HWPXReader는 파일 경로 기반으로 동작하므로 스트림을 임시 파일에 복사한다.
     */
    private Document parseHwpxDocument(Resource resource) throws Exception {
        String filename = resource.getFilename();
        if (filename == null) {
            filename = "unknown.hwpx";
        }

        File tempFile = null;
        try {
            tempFile = Files.createTempFile("hwpx-", ".hwpx").toFile();

            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            HWPXFile hwpxFile = HWPXReader.fromFile(tempFile);
            String content = TextExtractor.extract(
                    hwpxFile,
                    TextExtractMethod.InsertControlTextBetweenParagraphText,
                    false,
                    null);

            if (content == null || content.trim().isEmpty()) {
                log.warn("HWPX 파일 '{}'에서 추출된 텍스트가 없습니다.", filename);
                return null;
            }

            String customId = computeDocId(resource, filename);

            Metadata metadata = Metadata.from("id", customId);
            metadata.put("file_name", filename);
            metadata.put("source", filename);
            metadata.put("type", "hwpx");
            metadata.put("content_length", String.valueOf(content.length()));
            Long sourceLastModified = documentIdUtil.lastModifiedOrNull(resource);
            if (sourceLastModified != null) {
                metadata.put("source_last_modified", String.valueOf(sourceLastModified));
            }

            log.debug("HWPX Document ID: {} (길이: {})", customId, content.length());

            return Document.from(content, metadata);

        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
}
