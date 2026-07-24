package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 일반 텍스트(.txt) 문서 로더
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovTxtReader implements EgovDocumentReader {

    private final DocumentIdUtil documentIdUtil;

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("txt");
    }

    @Override
    public List<Document> parse(Resource resource) throws Exception {
        Document document = processTxtResource(resource);
        return document == null ? List.of() : List.of(document);
    }

    @Override
    public String computeDocId(Resource resource, String filename) {
        String safeFilename = documentIdUtil.uniquePathKey(resource, filename);
        return String.format("txt-%s_1", safeFilename);
    }

    private Document processTxtResource(Resource resource) throws Exception {
        String filename = resource.getFilename();
        if (filename == null) {
            filename = "unknown.txt";
        }

        String content;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            content = reader.lines().collect(Collectors.joining("\n"));
        }

        if (content.trim().isEmpty()) {
            log.warn("빈 파일 건너뜀: {}", filename);
            return null;
        }

        String customId = computeDocId(resource, filename);

        Metadata metadata = Metadata.from("id", customId);
        metadata.put("file_name", filename);
        metadata.put("source", filename);
        metadata.put("type", "txt");
        metadata.put("content_length", String.valueOf(content.length()));
        metadata.put("page_number", "1");
        Long sourceLastModified = documentIdUtil.lastModifiedOrNull(resource);
        if (sourceLastModified != null) {
            metadata.put("source_last_modified", String.valueOf(sourceLastModified));
        }

        log.info("TXT 문서 로드 완료: {}, 크기: {}바이트", filename, content.length());

        return Document.from(content, metadata);
    }
}
