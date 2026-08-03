package com.example.chatbot.chat.config;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ReRankingContentAggregator}(리랭커 서버 호출)를 감싸는 데코레이터. 두 가지를 보완한다.
 *
 * <ol>
 *   <li><b>리랭커 서버 장애 대응</b> - 리랭커는 별도 프로세스(llama.cpp 컨테이너)라 그게
 *       내려가 있으면 RAG 전체가 죽어버릴 수 있다. 호출이 실패하면 예외를 삼키고 delegate로
 *       폴백(원래 검색 순서대로 top-k만 자름)해서, 리랭킹 품질만 포기하고 답변 자체는
 *       계속 나가게 한다.</li>
 *   <li><b>키워드 부스트 문서 보존</b> - {@link EgovKeywordBoostContentRetriever}가 유사도
 *       점수와 무관하게 강제로 끼워 넣은 문서를, 리랭커가 다시 낮은 점수로 판단해 최종
 *       top-k에서 잘라내면 그 강제 포함의 의미가 없어진다. 리랭킹 결과에 없으면 뒤에
 *       추가로 붙여서 항상 살아남게 한다.</li>
 * </ol>
 */
@Slf4j
public class EgovResilientRerankingAggregator implements ContentAggregator {

    private static final int PREVIEW_LENGTH = 200;

    private final ContentAggregator reRankingAggregator;
    private final ContentAggregator fallbackAggregator;
    private final Set<String> protectedFileNames;
    private final int maxResults;

    /**
     * @param fallbackAggregator delegate {@link dev.langchain4j.rag.content.aggregator.DefaultContentAggregator}는
     *                           개수를 안 잘라주므로(순서만 재정렬), 리랭커 실패 시에도 {@code maxResults}로
     *                           직접 잘라야 candidate-pool(예: 20개)이 그대로 프롬프트에 다 들어가는 걸 막는다.
     */
    public EgovResilientRerankingAggregator(ContentAggregator reRankingAggregator,
                                             ContentAggregator fallbackAggregator,
                                             Set<String> protectedFileNames,
                                             int maxResults) {
        this.reRankingAggregator = reRankingAggregator;
        this.fallbackAggregator = fallbackAggregator;
        this.protectedFileNames = protectedFileNames;
        this.maxResults = maxResults;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        List<Content> aggregated;
        try {
            aggregated = reRankingAggregator.aggregate(queryToContents);
            logRerankedResults(aggregated);
        } catch (Exception e) {
            log.warn("리랭커 호출 실패 - 원래 검색 순서로 대체합니다. 원인: {}", e.getMessage(), e);
            List<Content> fallback = fallbackAggregator.aggregate(queryToContents);
            aggregated = fallback.size() > maxResults ? new ArrayList<>(fallback.subList(0, maxResults)) : fallback;
        }

        return preserveProtectedFiles(aggregated, queryToContents);
    }

    /**
     * 리랭커가 최종적으로 고른 top-k와 그 점수를 로그로 남긴다. {@code ReRankingContentAggregator}가
     * 채점 후 {@link Content#metadata()}에 {@link ContentMetadata#RERANKED_SCORE}로 점수를 실어주므로
     * 그걸 그대로 읽는다(점수는 0~1 확률이 아니라 bge-reranker 원시 로짓 - 상대 순위가 중요).
     */
    private void logRerankedResults(List<Content> reranked) {
        log.info("리랭킹 결과 {}건", reranked.size());
        for (Content content : reranked) {
            String fileName = content.textSegment().metadata().getString("file_name");
            Object score = content.metadata().get(ContentMetadata.RERANKED_SCORE);
            String text = content.textSegment().text();
            String preview = text.length() > PREVIEW_LENGTH ? text.substring(0, PREVIEW_LENGTH) + "..." : text;
            log.info("  - file={}, score={}, text={}", fileName, score, preview);
        }
    }

    private List<Content> preserveProtectedFiles(List<Content> aggregated,
                                                  Map<Query, Collection<List<Content>>> queryToContents) {
        if (protectedFileNames.isEmpty()) {
            return aggregated;
        }

        Set<String> presentFileNames = new HashSet<>();
        for (Content content : aggregated) {
            String fileName = content.textSegment().metadata().getString("file_name");
            if (fileName != null) {
                presentFileNames.add(fileName);
            }
        }

        List<Content> result = null;
        for (Collection<List<Content>> lists : queryToContents.values()) {
            for (List<Content> contents : lists) {
                for (Content content : contents) {
                    String fileName = content.textSegment().metadata().getString("file_name");
                    if (fileName == null || !protectedFileNames.contains(fileName)
                            || presentFileNames.contains(fileName)) {
                        continue;
                    }
                    log.debug("리랭킹에서 잘린 키워드 부스트 문서를 다시 포함 - 파일: {}", fileName);
                    if (result == null) {
                        result = new ArrayList<>(aggregated);
                    }
                    result.add(content);
                    presentFileNames.add(fileName);
                }
            }
        }

        return result != null ? result : aggregated;
    }
}
