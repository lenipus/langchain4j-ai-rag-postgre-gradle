package com.example.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 검색 전에 사내 은어/축약어를 문서 원문에 실제 쓰이는 정규 용어로 치환한다. 대화
 * 기록/화면에 표시되는 사용자 원문은 안 건드리고, {@code ChatbotFactory}의 QueryTransformer
 * 단계에서 검색에만 쓰이는 질의 텍스트에 적용한다.
 *
 * <p>임베딩 기반 의미 검색은 "올해"처럼 실행 시점에 따라 뜻이 바뀌는 표현(문서엔 "2026년"
 * 같은 실제 값이 박혀있어 그냥 두면 안 맞음)이나 "법카"처럼 사내에서만 쓰는 줄임말을
 * 원천적으로 처리하지 못해 별도로 치환해준다.</p>
 */
@Slf4j
@Component
public class SynonymQueryNormalizer {

    @Value("${rag.query-normalization.enabled:true}")
    private boolean enabled;

    // 문서 원문에서 실제로 쓰이는 정규 용어로 치환. 새 은어가 생기면 여기 추가하면 된다.
    // 짧은 키가 긴 키의 일부일 수 있어("법인차"가 "법인차량"의 부분 문자열) 적용 순서는
    // buildOrderedSynonyms()에서 키 길이 내림차순으로 다시 정렬한다.
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("시내출장", "대전 출장"),
            Map.entry("시내 출장", "대전 출장"),
            Map.entry("시내", "대전"),
            Map.entry("연차", "휴가"),
            Map.entry("연가", "휴가"),
            Map.entry("동아리", "동호회"),
            Map.entry("인포멀", "동호회"),
            Map.entry("교육 출장", "교육출장"),
            Map.entry("법인 카드", "법인카드"),
            Map.entry("법카", "법인카드"),
            Map.entry("법인차량", "업무용 차량"),
            Map.entry("법인 차량", "업무용 차량"),
            Map.entry("법인차", "업무용 차량"),
            Map.entry("관용차량", "업무용 차량"),
            Map.entry("관용 차량", "업무용 차량"),
            Map.entry("관용차", "업무용 차량"));

    private static final List<Map.Entry<String, String>> ORDERED_SYNONYMS = SYNONYMS.entrySet().stream()
            .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed())
            .collect(Collectors.toList());

    // "올해"처럼 시점에 따라 뜻이 바뀌는 표현 - 고정 치환표로는 처리 못 해서 매 호출마다
    // 실행 시점의 연도로 다시 치환한다.
    private static final List<String> CURRENT_YEAR_TERMS = List.of("올해");

    public String normalize(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return text;
        }

        String result = text;
        for (Map.Entry<String, String> entry : ORDERED_SYNONYMS) {
            result = result.replace(entry.getKey(), entry.getValue());
        }

        String currentYear = String.valueOf(Year.now().getValue());
        for (String term : CURRENT_YEAR_TERMS) {
            result = result.replace(term, currentYear);
        }

        if (!result.equals(text)) {
            log.debug("질의 동의어 정규화 - 원본: [{}] -> 정규화: [{}]", text, result);
        }
        return result;
    }
}
