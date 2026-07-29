package com.example.chat.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * LLM이 날짜 계산이 필요할 때 암산하는 대신 호출할 수 있는 도구(langchain4j tool/function
 * calling). 로컬/소형 모델일수록 요일·날짜 차이 계산을 자주 틀리는 문제가 있어(직접
 * 계산 절차를 안내하거나 달력을 그려보라고 해도 틀리는 경우가 반복 확인됨), 실제 계산은
 * 이 클래스(순수 자바 {@link LocalDate} 연산)가 대신 하고 그 결과만 모델에게 돌려준다 -
 * 모델이 이 도구를 실제로 호출하는지, 결과를 답변에 제대로 반영하는지는 모델 자신의
 * 판단에 달려있어 100% 보장은 아니다.
 */
@Slf4j
@Component
public class DateCalculationTool {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String[] KOREAN_DAY_NAMES =
            {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};

    @Tool("주어진 날짜(yyyy-MM-dd 형식)가 무슨 요일인지 정확히 계산해서 반환한다. " +
            "요일을 암산하지 말고 반드시 이 도구를 사용하라.")
    public String dayOfWeek(@P("계산할 날짜, yyyy-MM-dd 형식 (예: 2026-08-06)") String isoDate) {
        LocalDate date = parseOrNull(isoDate);
        if (date == null) {
            return "날짜 형식이 올바르지 않습니다. yyyy-MM-dd 형식으로 다시 요청하세요.";
        }
        String result = date.format(ISO_DATE) + "은(는) " + koreanDayName(date) + "입니다.";
        log.debug("날짜 계산 도구 호출 - dayOfWeek({}) -> {}", isoDate, result);
        return result;
    }

    @Tool("기준 날짜(yyyy-MM-dd)로부터 며칠 뒤/전의 날짜와 요일을 정확히 계산해서 반환한다. " +
            "'며칠 후', '다음 주 X일' 같은 상대 날짜 계산 시 암산하지 말고 반드시 이 도구를 사용하라.")
    public String addDays(
            @P("기준 날짜, yyyy-MM-dd 형식") String isoDate,
            @P("더할 일수. 이전 날짜를 구하려면 음수를 사용한다") int days) {
        LocalDate baseDate = parseOrNull(isoDate);
        if (baseDate == null) {
            return "날짜 형식이 올바르지 않습니다. yyyy-MM-dd 형식으로 다시 요청하세요.";
        }
        LocalDate result = baseDate.plusDays(days);
        String response = result.format(ISO_DATE) + "은(는) " + koreanDayName(result) + "입니다.";
        log.debug("날짜 계산 도구 호출 - addDays({}, {}) -> {}", isoDate, days, response);
        return response;
    }

    private LocalDate parseOrNull(String isoDate) {
        try {
            return LocalDate.parse(isoDate, ISO_DATE);
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    private String koreanDayName(LocalDate date) {
        return KOREAN_DAY_NAMES[date.getDayOfWeek().getValue() - 1];
    }
}
