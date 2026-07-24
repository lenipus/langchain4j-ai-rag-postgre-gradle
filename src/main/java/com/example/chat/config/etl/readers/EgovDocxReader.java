package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * DOCX 문서 로더
 * Apache POI XWPFDocument를 사용하여 .docx 파일에서 텍스트를 추출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovDocxReader implements EgovDocumentReader {

    private final DocumentIdUtil documentIdUtil;

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("docx");
    }

    @Override
    public List<Document> parse(Resource resource) throws IOException {
        Document document = parseDocxDocument(resource);
        return document == null ? List.of() : List.of(document);
    }

    @Override
    public String computeDocId(Resource resource, String filename) {
        String safeFilename = documentIdUtil.uniquePathKey(resource, filename);
        return String.format("docx-%s_1", safeFilename);
    }

    /**
     * DOCX 파일을 파싱하여 Document 생성
     */
    private Document parseDocxDocument(Resource resource) throws IOException {
        String filename = resource.getFilename();
        if (filename == null) {
            filename = "unknown.docx";
        }

        try (InputStream inputStream = resource.getInputStream();
             XWPFDocument xwpfDocument = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(xwpfDocument)) {

            String content = extractor.getText();

            if (content == null || content.trim().isEmpty()) {
                log.warn("빈 DOCX 파일 건너뜀: {}", filename);
                return null;
            }

            String customId = computeDocId(resource, filename);

            Metadata metadata = Metadata.from("id", customId);
            metadata.put("file_name", filename);
            metadata.put("source", filename);
            metadata.put("type", "docx");
            metadata.put("content_length", String.valueOf(content.length()));
            Long sourceLastModified = documentIdUtil.lastModifiedOrNull(resource);
            if (sourceLastModified != null) {
                metadata.put("source_last_modified", String.valueOf(sourceLastModified));
            }

            log.debug("DOCX Document ID: {} (길이: {})", customId, content.length());

            return Document.from(content, metadata);
        }
    }
}
