package com.example.chatbot.chat.config.etl.transformers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentTransformer;
import dev.langchain4j.data.document.Metadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 문서 내용 정규화 변환기
 * - HTML 태그 제거
 * - 공백 및 줄바꿈 정규화
 * - 코드 블록 제거 (선택)
 * - 특수문자 정리
 */
@Slf4j
@Component
public class EgovContentFormatTransformer implements DocumentTransformer {

    // 정규화 설정
    @Value("${document.normalization.enabled}")
    private boolean normalizationEnabled;

    @Value("${document.normalization.remove-html-tags}")
    private boolean removeHtmlTags;

    @Value("${document.normalization.normalize-whitespace}")
    private boolean normalizeWhitespace;

    @Value("${document.normalization.normalize-newlines}")
    private boolean normalizeNewlines;

    @Value("${document.normalization.remove-code-blocks}")
    private boolean removeCodeBlocks;

    @Value("${document.normalization.clean-special-chars}")
    private boolean cleanSpecialChars;

    // 정규식 패턴들
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern SPECIAL_CHARS_PATTERN = Pattern.compile(
            "[^\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F\\uA960-\\uA97F\\uD7B0-\\uD7FF\\u2190-\\u2195" +
                "a-zA-Z0-9\\s\\n\\t\\-_.,()\\[\\]{}\"':;!?@#$%&*+=|\\\\/<>]");

    // 원문자 ⓪(U+24EA), ①~⑳(U+2460~U+2473)를 "0)", "1)" ... "20)"로 치환한다.
    private static final Pattern CIRCLED_NUMBER_PATTERN = Pattern.compile("[\\u24EA\\u2460-\\u2473]");

    private static String replaceCircledNumbers(String text) {
        return CIRCLED_NUMBER_PATTERN.matcher(text).replaceAll(match -> {
            int codePoint = match.group().codePointAt(0);
            int number = (codePoint == 0x24EA) ? 0 : (codePoint - 0x2460 + 1);
            return number + ")";
        });
    }

    @Override
    public Document transform(Document document) {
        if (!normalizationEnabled) {
            return Document.from(document.text(), standardizeMetadata(
                    document.metadata(), document.text().length(), document.text().length(), false));
        }

        String originalContent = document.text();
        String normalizedContent = originalContent;

        // HTML 태그 제거
        if (removeHtmlTags) {
            normalizedContent = normalizedContent.replaceAll("<[^>]*>", "");
        }

        normalizedContent = replaceCircledNumbers(normalizedContent);

        if (normalizeWhitespace) {
            normalizedContent = normalizedContent.replaceAll("[ \\t]+", " ");
        }

        if (normalizeNewlines) {
            // 줄바꿈 형식만 LF로 통일하고, 문단 경계(빈 줄)는 유지한다. 세 줄 이상
            // 연속된 경우만 두 줄로 줄인다. 두 줄을 한 줄로 합치면 recursive splitter가
            // 문단 경계를 인식하지 못하고 긴 문장을 다시 합치는 문제가 생긴다.
            normalizedContent = normalizedContent
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .replaceAll("\\n{3,}", "\n\n");
        }

        // 코드 블록 제거
        if (removeCodeBlocks) {
            normalizedContent = CODE_BLOCK_PATTERN.matcher(normalizedContent).replaceAll("");
        }

        // 특수문자 정리
        if (cleanSpecialChars) {
            normalizedContent = SPECIAL_CHARS_PATTERN.matcher(normalizedContent).replaceAll("");
        }

        // 앞뒤 공백 제거
        normalizedContent = normalizedContent.trim();

        Metadata newMetadata = standardizeMetadata(
                document.metadata(), originalContent.length(), normalizedContent.length(), true);
        return Document.from(normalizedContent, newMetadata);
    }

    /**
     * 리더별 차이와 실제 내용 변경 여부에 상관없이 임베딩 메타데이터의 공통 필드를
     * 동일하게 만든다. {@code index}는 이후 DocumentSplitter가 청크별로 추가한다.
     *
     * <p>LangChain4j Metadata 내부 구현은 HashMap이므로 JSON 키 순서를 데이터 계약으로
     * 사용할 수는 없다. 이 메서드는 키의 표시 순서가 아니라 키 집합과 의미를 통일한다.</p>
     */
    private Metadata standardizeMetadata(Metadata source, int originalLength, int normalizedLength,
            boolean normalizationApplied) {
        Metadata metadata = source.copy();
        metadata.put("original_length", String.valueOf(originalLength));
        metadata.put("normalized_length", String.valueOf(normalizedLength));
        metadata.put("normalization_applied", String.valueOf(normalizationApplied));
        metadata.put("code_blocks_removed", String.valueOf(normalizationApplied && removeCodeBlocks));
        metadata.put("special_chars_cleaned", String.valueOf(normalizationApplied && cleanSpecialChars));

        // HWPX/DOCX처럼 페이지 정보가 없는 단일 Document 리더도 동일한 공통 필드를 갖게 한다.
        if (!metadata.containsKey("page_number")) {
            metadata.put("page_number", "1");
        }
        return metadata;
    }

    @Override
    public List<Document> transformAll(List<Document> documents) {
        if (!normalizationEnabled) {
            log.info("문서 정규화가 비활성화되어 있습니다. 원본 문서를 그대로 반환합니다.");
            return documents;
        }

        log.info("문서 형식 변환 시작: {}개 문서 (HTML: {}, 공백: {}, 줄바꿈: {}, 코드블록: {}, 특수문자: {})",
                documents.size(), removeHtmlTags, normalizeWhitespace, normalizeNewlines, removeCodeBlocks,
                cleanSpecialChars);

        List<Document> normalizedDocuments = documents.stream()
                .map(this::transform)
                .collect(Collectors.toList());

        log.info("문서 형식 변환 완료: {}개 문서", normalizedDocuments.size());
        return normalizedDocuments;
    }
}
