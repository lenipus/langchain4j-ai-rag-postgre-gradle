package com.example.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatbotFactory#currentDateContext(LocalDate)}가 LLM에게 실제 오늘 날짜/요일을
 * 한국어로 정확히 알려주는 문장을 만드는지 검증한다. LLM은 실행 시점의 실제 날짜를 몰라
 * "다음 주 6일" 같은 상대 날짜 질문에서 연도/요일을 지어내는 문제가 있었다.
 */
class ChatbotFactoryCurrentDateContextTest {

    @Test
    @DisplayName("날짜를 한국어 연/월/일/요일 형식 문장으로 만들고 계산 절차 안내를 덧붙인다")
    void formatsDateInKorean() {
        LocalDate wednesday = LocalDate.of(2026, 7, 29);

        assertThat(ChatbotFactory.currentDateContext(wednesday))
                .startsWith("오늘은 2026년 07월 29일 수요일입니다.")
                .contains("날짜 차이를 정확히 센 다음");
    }

    @Test
    @DisplayName("요일이 바뀌면 결과도 그에 맞게 바뀐다")
    void reflectsDifferentDayOfWeek() {
        LocalDate sunday = LocalDate.of(2026, 8, 2);

        assertThat(ChatbotFactory.currentDateContext(sunday))
                .startsWith("오늘은 2026년 08월 02일 일요일입니다.");
    }
}
