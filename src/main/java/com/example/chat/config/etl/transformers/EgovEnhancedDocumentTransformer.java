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

    public EgovEnhancedDocumentTransformer(
            @Value("${document.chunk-size}") int chunkSize,
            @Value("${document.min-chunk-size-chars}") int minChunkSizeChars) {

        // LangChain4j의 DocumentSplitter 생성
        // 토큰 기반 분할 (최대 토큰 수, 오버랩)
        this.documentSplitter = DocumentSplitters.recursive(
                chunkSize, // 최대 토큰 수
                Math.max(chunkSize / 10, 50) // 오버랩 (청크 크기의 10%)
        );

        log.info("EnhancedDocumentTransformer 초기화 - chunkSize: {}, minChunkSize: {}",
                chunkSize, minChunkSizeChars);
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

        // 너무 짧은 청크(예: PDF 페이지가 사실상 캡션 한 줄뿐인 경우)는 여기서 미리 빼지
        // 않는다 — 색인 단계에서 제외하면 그 청크가 실제로 정답인 질의에서도 영영 검색되지
        // 않는다. 대신 검색(RAG) 단계에서 top-k보다 넉넉히 가져온 뒤 길이로 걸러내고 최종
        // top-k로 자른다 (EgovRagConfig의 rag.retrieval.overfetch-multiplier 참고).

        // 분할된 청크 크기 로깅
        for (int i = 0; i < splitDocs.size(); i++) {
            Document chunk = splitDocs.get(i);
            String content = chunk.text();
            if (content != null) {
                int estimatedTokens = content.length() / 4;
                log.info("청크 {} - 크기: {}바이트, 추정 토큰 수: {}",
                        i + 1, content.length(), estimatedTokens);
            }
        }

        return splitDocs;
    }
}
