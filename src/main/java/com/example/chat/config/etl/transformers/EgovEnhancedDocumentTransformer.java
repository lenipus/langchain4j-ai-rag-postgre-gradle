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
 * - 문서 분할 (문자 수 기반. TokenCountEstimator를 안 쓰므로 실제 LLM 토큰 수와는 다르다)
 */
@Slf4j
@Component
public class EgovEnhancedDocumentTransformer implements DocumentTransformer {

    private final DocumentSplitter documentSplitter;

    public EgovEnhancedDocumentTransformer(
            @Value("${document.chunk-size}") int chunkSize,
            @Value("${document.chunk-overlap:400}") int chunkOverlap) {

        this.documentSplitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);

        log.info("EnhancedDocumentTransformer 초기화 - chunkSize: {}자, chunkOverlap: {}자", chunkSize, chunkOverlap);
    }

    @Override
    public Document transform(Document document) {
        // 단일 문서 변환은 transformAll을 호출
        List<Document> result = transformAll(List.of(document));
        return result.isEmpty() ? document : result.get(0);
    }

    @Override
    public List<Document> transformAll(List<Document> documents) {
        List<Document> splitDocs = new ArrayList<>();
        for (Document doc : documents) {
            List<TextSegment> segments = documentSplitter.split(doc);
            List<Document> chunks = segments.stream()
                    .map(segment -> Document.from(segment.text(), segment.metadata()))
                    .collect(Collectors.toList());
            splitDocs.addAll(chunks);
        }

        return splitDocs;
    }
}
