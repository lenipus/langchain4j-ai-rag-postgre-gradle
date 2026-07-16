package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 일반 텍스트(.txt) 문서 로더
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovTxtReader {

    private final DocumentIdUtil documentIdUtil;

    @Value("${document.txt-path:#{null}}")
    private String txtDocumentPath;

    public List<Document> read() {
        if (txtDocumentPath == null || txtDocumentPath.isBlank()) {
            log.info("TXT 문서 경로가 설정되지 않아 건너뜁니다.");
            return List.of();
        }

        log.info("TXT 문서 읽기 시작 - 경로: {}", txtDocumentPath);

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(txtDocumentPath);

            if (resources.length == 0) {
                log.warn("TXT 파일을 찾을 수 없습니다: {}", txtDocumentPath);
                return List.of();
            }

            log.info("{}개의 TXT 파일을 찾았습니다.", resources.length);

            List<Document> documents = new ArrayList<>();
            for (Resource resource : resources) {
                try {
                    Document doc = processTxtResource(resource);
                    if (doc != null) {
                        documents.add(doc);
                    }
                } catch (Exception e) {
                    log.error("TXT 파일 '{}' 처리 중 오류 발생: {}", resource.getFilename(), e.getMessage());
                }
            }

            log.info("총 {}개의 TXT 문서를 읽었습니다.", documents.size());
            return documents;
        } catch (Exception e) {
            log.error("TXT 문서 읽기 중 오류 발생", e);
            return List.of();
        }
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

        String safeFilename = documentIdUtil.uniquePathKey(resource, filename);
        String customId = String.format("txt-%s_1", safeFilename);

        Metadata metadata = Metadata.from("id", customId);
        metadata.put("file_name", filename);
        metadata.put("source", filename);
        metadata.put("type", "txt");
        metadata.put("content_length", String.valueOf(content.length()));
        metadata.put("page_number", "1");

        log.info("TXT 문서 로드 완료: {}, 크기: {}바이트", filename, content.length());

        return Document.from(content, metadata);
    }
}
