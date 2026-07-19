package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 마크다운 문서 로더
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovMarkdownReader implements EgovDocumentReader {

    private final DocumentIdUtil documentIdUtil;

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("md");
    }

    @Override
    public List<Document> parse(Resource resource) throws IOException {
        Document document = processMarkdownResource(resource);
        return document == null ? List.of() : List.of(document);
    }

    private Document processMarkdownResource(Resource resource) throws IOException {
        String filename = resource.getFilename();
        if (filename == null) {
            log.warn("파일명이 null입니다: {}", resource.getDescription());
            return null;
        }

        String content = readResourceContent(resource);
        if (content == null || content.trim().isEmpty()) {
            log.warn("빈 파일 건너뜀: {}", filename);
            return null;
        }

        Metadata metadata = createEnhancedMetadata(resource, filename, content);

        log.info("마크다운 문서 로드 완료: {}, 크기: {}바이트", filename, content.length());

        return Document.from(content, metadata);
    }

    private Metadata createEnhancedMetadata(Resource resource, String filename, String content) {
        String docId = "doc-" + documentIdUtil.uniquePathKey(resource, filename);

        Metadata metadata = Metadata.from("id", docId);
        metadata.put("source", filename);
        // PDF·HWP·HWPX 리더와 동일하게 file_name을 남긴다(출처 표기 시 리더 간 메타데이터 정합).
        metadata.put("file_name", filename);
        metadata.put("type", "markdown");
        metadata.put("content_length", String.valueOf(content.length()));
        // (?s) DOTALL: 여러 줄 마크다운에서도 '.'이 줄바꿈을 포함해 전체 매칭되도록 한다
        metadata.put("has_headers", String.valueOf(content.matches("(?s).*#{1,6}\\s.*")));
        metadata.put("has_code_blocks", String.valueOf(content.contains("```")));
        metadata.put("has_links", String.valueOf(content.matches("(?s).*\\[.*\\]\\(.*\\).*")));
        metadata.put("has_images", String.valueOf(content.matches("(?s).*!\\[.*\\]\\(.*\\).*")));
        metadata.put("line_count", String.valueOf(content.split("\n").length));
        return metadata;
    }

    private String readResourceContent(Resource resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
