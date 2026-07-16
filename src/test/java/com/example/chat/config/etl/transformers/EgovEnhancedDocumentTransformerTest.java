package com.example.chat.config.etl.transformers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EgovEnhancedDocumentTransformer}가 청크 분할 후, 지나치게 짧은 청크(예: PDF
 * 페이지가 사실상 캡션 한 줄뿐인 경우)를 임베딩 대상에서 제외하는지 검증한다.
 *
 * <p>이런 청크는 검색 가치가 없을 뿐 아니라, 벡터가 좁고 뾰족해서 질의어와 단어 몇 개만
 * 겹쳐도 부적절하게 높은 유사도로 잡히는 부작용이 있다(예: "휴가 신청-1" 같은 페이지
 * 캡션이 "휴가 결재선" 질의에서 진짜 답이 담긴 긴 청크보다 더 높은 순위로 잡힘).</p>
 */
class EgovEnhancedDocumentTransformerTest {

    private final EgovEnhancedDocumentTransformer transformer =
            new EgovEnhancedDocumentTransformer(4000, 350, 50);

    private Document doc(String id, String text) {
        return Document.from(text, Metadata.from("id", id));
    }

    @Test
    @DisplayName("최소 길이(50자) 미만 청크는 결과에서 제외된다")
    void filtersOutChunksShorterThanMinLength() {
        Document tiny = doc("tiny", "휴가 신청-1");
        Document normal = doc("normal", "이 문서는 충분히 긴 본문을 가지고 있어 임베딩 대상에서 제외되지 않아야 한다는 것을 확인하기 위한 샘플 텍스트입니다.");

        List<Document> result = transformer.transformAll(List.of(tiny, normal));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).metadata().getString("id")).isEqualTo("normal");
    }

    @Test
    @DisplayName("최소 길이 이상 청크는 그대로 유지된다")
    void keepsChunksAtOrAboveMinLength() {
        String exactly50Chars = "가".repeat(50);
        Document doc = doc("exact", exactly50Chars);

        List<Document> result = transformer.transformAll(List.of(doc));

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("모든 청크가 너무 짧으면 빈 리스트를 반환한다")
    void returnsEmptyWhenAllChunksTooShort() {
        Document tiny1 = doc("tiny1", "출장 신청");
        Document tiny2 = doc("tiny2", "휴가 신청-2");

        List<Document> result = transformer.transformAll(List.of(tiny1, tiny2));

        assertThat(result).isEmpty();
    }
}
