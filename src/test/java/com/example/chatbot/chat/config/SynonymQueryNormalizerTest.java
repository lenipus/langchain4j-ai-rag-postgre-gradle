package com.example.chatbot.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Year;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SynonymQueryNormalizer}가 사내 은어를 문서 원문 용어로 치환하는지, 특히 짧은
 * 키가 긴 키의 부분 문자열인 경우(예: "법인차"가 "법인차량"에 포함됨)에도 깨지지 않고
 * 긴 키 우선으로 치환되는지 검증한다.
 */
class SynonymQueryNormalizerTest {

    private SynonymQueryNormalizer newNormalizer(boolean enabled) {
        SynonymQueryNormalizer normalizer = new SynonymQueryNormalizer();
        ReflectionTestUtils.setField(normalizer, "enabled", enabled);
        return normalizer;
    }

    @Test
    @DisplayName("짧은 키가 긴 키의 부분 문자열이어도 긴 키가 먼저 치환된다 (법인차량)")
    void prefersLongerKeyOverSubstringKey() {
        SynonymQueryNormalizer normalizer = newNormalizer(true);

        assertThat(normalizer.normalize("법인차량 예약 어떻게 해?"))
                .isEqualTo("업무용 차량 예약 어떻게 해?");
    }

    @Test
    @DisplayName("공백 없는 복합어도 정확히 치환된다 (시내출장)")
    void handlesCompoundWordWithoutSpace() {
        SynonymQueryNormalizer normalizer = newNormalizer(true);

        assertThat(normalizer.normalize("시내출장 신청 방법 알려줘"))
                .isEqualTo("대전 출장 신청 방법 알려줘");
    }

    @Test
    @DisplayName("법카는 법인카드로 치환된다")
    void expandsAbbreviation() {
        SynonymQueryNormalizer normalizer = newNormalizer(true);

        assertThat(normalizer.normalize("법카 한도가 얼마야?")).isEqualTo("법인카드 한도가 얼마야?");
    }

    @Test
    @DisplayName("올해는 실행 시점의 연도로 치환된다")
    void substitutesCurrentYear() {
        SynonymQueryNormalizer normalizer = newNormalizer(true);

        String result = normalizer.normalize("올해 연차는 며칠이야?");

        assertThat(result).isEqualTo(Year.now().getValue() + " 휴가는 며칠이야?");
    }

    @Test
    @DisplayName("enabled=false면 원문을 그대로 반환한다")
    void returnsOriginalWhenDisabled() {
        SynonymQueryNormalizer normalizer = newNormalizer(false);

        assertThat(normalizer.normalize("법카 한도가 얼마야?")).isEqualTo("법카 한도가 얼마야?");
    }

    @Test
    @DisplayName("치환 대상이 없는 문장은 그대로 반환된다")
    void leavesUnrelatedTextUnchanged() {
        SynonymQueryNormalizer normalizer = newNormalizer(true);

        assertThat(normalizer.normalize("겸직허가 규정 좀 알려줘")).isEqualTo("겸직허가 규정 좀 알려줘");
    }

    @Test
    @DisplayName("null/빈 문자열은 그대로 반환된다")
    void handlesNullAndBlank() {
        SynonymQueryNormalizer normalizer = newNormalizer(true);

        assertThat(normalizer.normalize(null)).isNull();
        assertThat(normalizer.normalize("")).isEqualTo("");
    }
}
