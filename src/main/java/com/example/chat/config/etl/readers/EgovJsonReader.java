package com.example.chat.config.etl.readers;

import com.example.chat.util.DocumentIdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 규정 문서 JSON 로더.
 *
 * <p>다음 고정 스키마를 따르는 JSON만 지원한다: 제목/원본파일명/구축일자/게시일자/키워드(배열)/
 * 내용(배열, 각 항목은 text[{title, content[]}] · table[{tb_title, tb(HTML), tb_caption[]}] ·
 * image[{img_title, img, img_caption[]}] 중 하나 이상을 가짐). 이 구조를 하나의 본문 텍스트로
 * 펼쳐서 다른 리더(PDF·DOCX·HWP 등)와 동일하게 청크 분할·임베딩 파이프라인에 태운다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovJsonReader implements EgovDocumentReader {

    private final DocumentIdUtil documentIdUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern TAG_BR = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TAG_ROW_END = Pattern.compile("(?i)</tr>");
    private static final Pattern TAG_ANY = Pattern.compile("<[^>]*>");

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("json");
    }

    @Override
    public List<Document> parse(Resource resource) throws Exception {
        Document document = parseJsonDocument(resource);
        return document == null ? List.of() : List.of(document);
    }

    private Document parseJsonDocument(Resource resource) throws Exception {
        String filename = resource.getFilename();
        if (filename == null) {
            filename = "unknown.json";
        }

        JsonNode root;
        try (var in = resource.getInputStream()) {
            root = objectMapper.readTree(in);
        }

        String title = root.path("제목").asText("");
        String originalFileName = root.path("원본파일명").asText("");
        String buildDate = root.path("구축일자").asText("");
        String postDate = root.path("게시일자").asText("");

        StringBuilder sb = new StringBuilder();
        if (!title.isBlank()) {
            sb.append(title).append("\n\n");
        }

        StringBuilder keywords = new StringBuilder();
        for (JsonNode keyword : root.path("키워드")) {
            if (!keywords.isEmpty()) {
                keywords.append(", ");
            }
            keywords.append(keyword.asText());
        }
        if (!keywords.isEmpty()) {
            sb.append("키워드: ").append(keywords).append("\n\n");
        }

        appendContentSections(root.path("내용"), sb);

        String content = sb.toString().trim();
        if (content.isEmpty()) {
            log.warn("JSON 파일 '{}': 추출된 텍스트가 없습니다.", filename);
            return null;
        }

        String safeFilename = documentIdUtil.uniquePathKey(resource, filename);
        String customId = String.format("json-%s_1", safeFilename);

        Metadata metadata = Metadata.from("id", customId);
        metadata.put("file_name", filename);
        metadata.put("source", filename);
        metadata.put("type", "json");
        metadata.put("content_length", String.valueOf(content.length()));
        metadata.put("page_number", "1");
        if (!originalFileName.isBlank()) {
            metadata.put("original_file_name", originalFileName);
        }
        if (!buildDate.isBlank()) {
            metadata.put("build_date", buildDate);
        }
        if (!postDate.isBlank()) {
            metadata.put("post_date", postDate);
        }

        log.debug("JSON Document ID: {} (길이: {})", customId, content.length());

        return Document.from(content, metadata);
    }

    private void appendContentSections(JsonNode contentArray, StringBuilder sb) {
        for (JsonNode section : contentArray) {
            for (JsonNode textItem : section.path("text")) {
                String sectionTitle = textItem.path("title").asText("");
                if (!sectionTitle.isBlank()) {
                    sb.append("## ").append(sectionTitle).append("\n");
                }
                for (JsonNode paragraph : textItem.path("content")) {
                    sb.append(paragraph.asText()).append("\n");
                }
                sb.append("\n");
            }

            for (JsonNode tableItem : section.path("table")) {
                String tableTitle = tableItem.path("tb_title").asText("");
                if (!tableTitle.isBlank()) {
                    sb.append("## ").append(tableTitle).append("\n");
                }
                String tableHtml = tableItem.path("tb").asText("");
                sb.append(flattenTableHtml(tableHtml)).append("\n\n");

                for (JsonNode caption : tableItem.path("tb_caption")) {
                    sb.append(caption.asText()).append("\n");
                }
            }

            for (JsonNode imageItem : section.path("image")) {
                String imgTitle = imageItem.path("img_title").asText("");
                if (!imgTitle.isBlank()) {
                    sb.append("## ").append(imgTitle).append("\n");
                }
                for (JsonNode caption : imageItem.path("img_caption")) {
                    sb.append(caption.asText()).append("\n");
                }
            }
        }
    }

    /**
     * 표 HTML을 셀/행 경계가 유지되는 평문으로 변환한다. 태그를 전부 지워버리면
     * (예: EgovContentFormatTransformer의 일괄 태그 제거) 셀 내용이 공백 없이 붙어버려
     * 검색 대상 텍스트로서 의미가 왜곡되므로, 행 끝은 줄바꿈으로, 나머지 태그 경계는
     * 공백으로 치환한 뒤 태그만 제거한다.
     */
    private String flattenTableHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String flattened = TAG_BR.matcher(html).replaceAll(" ");
        flattened = TAG_ROW_END.matcher(flattened).replaceAll("\n");
        flattened = TAG_ANY.matcher(flattened).replaceAll(" ");
        flattened = flattened.replaceAll("[ \\t]+", " ");
        flattened = flattened.replaceAll("\\n[ \\t]+", "\n");
        return flattened.trim();
    }
}
