package com.example.chat.config.etl.transformers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.DocumentTransformer;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 문서 변환기
 * - 문서 분할 (토큰 기반)
 */
@Slf4j
@Component
public class EgovEnhancedDocumentTransformer implements DocumentTransformer {

    private final DocumentSplitter documentSplitter;
    private final int minChunkLengthToEmbed;

    public EgovEnhancedDocumentTransformer(
            @Value("${document.chunk-size}") int chunkSize,
            @Value("${document.min-chunk-size-chars}") int minChunkSizeChars,
            @Value("${document.min-chunk-length-to-embed:50}") int minChunkLengthToEmbed) {

        // LangChain4j의 DocumentSplitter 생성
        // 토큰 기반 분할 (최대 토큰 수, 오버랩)
        this.documentSplitter = DocumentSplitters.recursive(
                chunkSize, // 최대 토큰 수
                Math.max(chunkSize / 10, 50) // 오버랩 (청크 크기의 10%)
        );
        this.minChunkLengthToEmbed = minChunkLengthToEmbed;

        log.info("EnhancedDocumentTransformer 초기화 - chunkSize: {}, minChunkSize: {}, minChunkLengthToEmbed: {}",
                chunkSize, minChunkSizeChars, minChunkLengthToEmbed);
    }

    @Override
    public Document transform(Document document) {
        // 단일 문서 변환은 transformAll을 호출
        List<Document> result = transformAll(List.of(document));
        return result.isEmpty() ? document : result.get(0);
    }

    @Override
    public List<Document> transformAll(List<Document> documents) {
        log.info("문서 변환 시작: {}개 문서", documents.size());

        // 문서별 크기 로깅
        for (Document doc : documents) {
            String content = doc.text();
            if (content != null) {
                int estimatedTokens = content.length() / 4;
                log.info("문서 ID: {} - 크기: {}바이트, 추정 토큰 수: {}",
                        doc.metadata().getString("id"), content.length(), estimatedTokens);
            }
        }

        // 문서 분할
        log.info("문서 분할 시작...");
        List<Document> splitDocs = new ArrayList<>();
        for (Document doc : documents) {
            List<TextSegment> segments = documentSplitter.split(doc);
            // TextSegment를 Document로 변환
            List<Document> chunks = segments.stream()
                    .map(segment -> Document.from(segment.text(), segment.metadata()))
                    .collect(Collectors.toList());
            splitDocs.addAll(chunks);
        }
        log.info("문서 분할 완료: {}개 청크 생성", splitDocs.size());

        // 너무 짧은 청크(예: PDF 페이지가 사실상 캡션 한 줄뿐인 경우) 는 검색 가치가
        // 없을 뿐 아니라, 벡터가 좁고 뾰족해서 키워드만 겹치면 엉뚱하게 높은 유사도로
        // 잡히는 부작용이 있다. 임베딩 대상에서 제외한다.
        List<Document> filteredDocs = new ArrayList<>();
        int skipped = 0;
        for (Document chunk : splitDocs) {
            String content = chunk.text();
            if (content == null || content.trim().length() < minChunkLengthToEmbed) {
                skipped++;
                continue;
            }
            filteredDocs.add(chunk);
        }
        if (skipped > 0) {
            log.info("최소 길이({}자) 미만 청크 {}개를 임베딩 대상에서 제외했습니다.", minChunkLengthToEmbed, skipped);
        }

        // 분할된 청크 크기 로깅
        for (int i = 0; i < filteredDocs.size(); i++) {
            Document chunk = filteredDocs.get(i);
            String content = chunk.text();
            if (content != null) {
                int estimatedTokens = content.length() / 4;
                log.info("청크 {} - 크기: {}바이트, 추정 토큰 수: {}",
                        i + 1, content.length(), estimatedTokens);
            }
        }

        return filteredDocs;
    }
}
