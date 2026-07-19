package com.example.chat.config.etl.transformers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EgovEnhancedDocumentTransformer}가 청크 분할 결과를 길이와 무관하게 그대로
 * 유지하는지 검증한다.
 *
 * <p>이전에는 이 트랜스포머가 지나치게 짧은 청크(예: PDF 페이지가 캡션 한 줄뿐인 경우)를
 * 색인 단계에서 미리 제외했으나, 그렇게 하면 그 청크가 실제로 정답인 질의에서도 영영
 * 검색되지 않는 문제가 있었다. 그래서 길이 필터링은 검색(RAG) 단계로 옮겼다 — 자세한
 * 내용은 {@link com.example.chat.config.EgovLengthFilteringContentRetriever} 참고.</p>
 */
class EgovEnhancedDocumentTransformerTest {

    private final EgovEnhancedDocumentTransformer transformer =
            new EgovEnhancedDocumentTransformer(4000, 350);

    private Document doc(String id, String text) {
        return Document.from(text, Metadata.from("id", id));
    }

    @Test
    @DisplayName("짧은 청크도 색인 단계에서는 제외되지 않고 그대로 유지된다")
    void keepsShortChunks() {
        Document tiny = doc("tiny", "휴가 신청-1");
        Document normal = doc("normal", "이 문서는 충분히 긴 본문을 가지고 있는 샘플 텍스트입니다.");

        List<Document> result = transformer.transformAll(List.of(tiny, normal));

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(d -> d.metadata().getString("id")))
                .containsExactlyInAnyOrder("tiny", "normal");
    }

    @Test
    @DisplayName("모든 청크가 짧아도 결과가 비지 않는다")
    void doesNotDropAllShortChunks() {
        Document tiny1 = doc("tiny1", "출장 신청");
        Document tiny2 = doc("tiny2", "휴가 신청-2");

        List<Document> result = transformer.transformAll(List.of(tiny1, tiny2));

        assertThat(result).hasSize(2);
    }
}
