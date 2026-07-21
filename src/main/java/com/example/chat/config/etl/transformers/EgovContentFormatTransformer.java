package com.example.chat.config.etl.transformers;

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
            "[^\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F\\uA960-\\uA97F\\uD7B0-\\uD7FF" +
                    "a-zA-Z0-9\\s\\n\\t\\-_.,()\\[\\]{}\"':;!?@#$%&*+=|\\\\/<>]");

    @Override
    public Document transform(Document document) {
        if (!normalizationEnabled) {
            return document;
        }

        String originalContent = document.text();
        String normalizedContent = originalContent;

        // HTML 태그 제거
        if (removeHtmlTags) {
            normalizedContent = normalizedContent.replaceAll("<[^>]*>", "");
        }

        // 공백 정규화. \s는 줄바꿈(\n)도 포함하므로 \s+ 를 그대로 쓰면 문서의 줄바꿈 구조가
        // 전부 스페이스로 뭉개진다(항목별로 줄바꿈된 목록형 문서가 한 줄로 이어붙는 문제의
        // 원인이었다). 줄바꿈은 아래 줄바꿈 정규화 단계가 따로 처리하므로, 여기서는 가로
        // 공백(스페이스·탭)만 정리한다.
        if (normalizeWhitespace) {
            normalizedContent = normalizedContent.replaceAll("[ \\t]+", " ");
        }

        // 줄바꿈 정규화. 치환 문자열에 "\\n"(백슬래시+n 두 글자)을 쓰면 정규식 치환 문자열
        // 이스케이프 규칙상 실제 개행이 아니라 리터럴 문자 "n"이 들어간다 - 지금까지는 위
        // 공백 정규화가 줄바꿈을 먼저 다 없애버려서 이 버그가 드러나지 않았을 뿐이다.
        if (normalizeNewlines) {
            normalizedContent = normalizedContent.replaceAll("\\n{2,}", "\n");
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

        // 내용이 변경된 경우에만 새 Document 생성
        if (!originalContent.equals(normalizedContent)) {
            Metadata newMetadata = document.metadata().copy();
            newMetadata.put("original_length", String.valueOf(originalContent.length()));
            newMetadata.put("normalized_length", String.valueOf(normalizedContent.length()));
            newMetadata.put("normalization_applied", "true");
            newMetadata.put("code_blocks_removed", String.valueOf(removeCodeBlocks));
            newMetadata.put("special_chars_cleaned", String.valueOf(cleanSpecialChars));

            // if (log.isDebugEnabled()) {
            //     log.debug("정규화 적용: {} -> {} (길이: {} -> {})",
            //             originalContent.substring(0, Math.min(50, originalContent.length())),
            //             normalizedContent.substring(0, Math.min(50, normalizedContent.length())),
            //             originalContent.length(), normalizedContent.length());
            // }

            return Document.from(normalizedContent, newMetadata);
        }

        return document;
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
