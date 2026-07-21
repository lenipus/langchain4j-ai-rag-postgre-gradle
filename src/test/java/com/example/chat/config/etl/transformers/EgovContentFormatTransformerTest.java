package com.example.chat.config.etl.transformers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EgovContentFormatTransformer}의 공백 정규화가 줄바꿈 구조를 보존하는지 검증한다.
 *
 * <p>이전 구현은 {@code \s+ -> " "} 정규식을 썼는데, 자바 정규식에서 {@code \s}는 줄바꿈도
 * 포함하므로 항목별로 줄바꿈된 목록형 문서(예: 결재라인 안내자료)가 전부 한 줄로 뭉개져
 * LLM이 서로 다른 항목을 구분하지 못하고 뒤섞어 답변하는 문제가 있었다.</p>
 */
class EgovContentFormatTransformerTest {

    private final EgovContentFormatTransformer transformer = new EgovContentFormatTransformer();

    {
        ReflectionTestUtils.setField(transformer, "normalizationEnabled", true);
        ReflectionTestUtils.setField(transformer, "removeHtmlTags", true);
        ReflectionTestUtils.setField(transformer, "normalizeWhitespace", true);
        ReflectionTestUtils.setField(transformer, "normalizeNewlines", true);
        ReflectionTestUtils.setField(transformer, "removeCodeBlocks", false);
        ReflectionTestUtils.setField(transformer, "cleanSpecialChars", false);
    }

    private Document doc(String text) {
        return Document.from(text, Metadata.from("id", "doc-1"));
    }

    @Test
    @DisplayName("줄바꿈으로 구분된 목록 항목은 한 줄로 뭉개지지 않고 유지된다")
    void preservesLineBreaksBetweenListItems() {
        String content = "휴가(공가)\n- 결재라인 : 팀원 → 팀장\n\n병가\n- 결재라인 : 본인 → 팀장";

        Document result = transformer.transform(doc(content));

        assertThat(result.text()).contains("휴가(공가)\n");
        assertThat(result.text()).contains("병가\n");
        assertThat(result.text()).doesNotContain("팀장 병가");
    }

    @Test
    @DisplayName("스페이스·탭 연속은 여전히 하나로 합쳐진다")
    void stillCollapsesRepeatedSpacesAndTabs() {
        String content = "항목1  \t  항목2";

        Document result = transformer.transform(doc(content));

        assertThat(result.text()).isEqualTo("항목1 항목2");
    }

    @Test
    @DisplayName("연속된 빈 줄(3줄 이상)은 한 줄로 줄어든다")
    void collapsesExcessiveBlankLines() {
        String content = "첫 문단\n\n\n\n둘째 문단";

        Document result = transformer.transform(doc(content));

        assertThat(result.text()).isEqualTo("첫 문단\n둘째 문단");
    }
}
