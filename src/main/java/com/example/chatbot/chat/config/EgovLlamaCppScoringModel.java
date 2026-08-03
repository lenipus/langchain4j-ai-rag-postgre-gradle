package com.example.chatbot.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * llama.cpp {@code llama-server}를 {@code --reranking}(={@code --embedding} + {@code --pooling rank})
 * 모드로 띄운 별도 컨테이너의 {@code /v1/rerank} 엔드포인트를 호출하는 {@link ScoringModel} 구현.
 *
 * <p>Ollama는 아직 리랭킹 전용 API를 지원하지 않아서(2026-08 기준), 이 서버만 별도 프로세스로
 * 띄워 호출한다. 점수는 0~1 확률이 아니라 원시 로짓(logit)이라 절대값보다 상대적인 순위가
 * 중요하다 - 실측(bge-reranker-v2-m3): 관련 문서 -0.1 근처, 무관 문서 -11 근처.</p>
 */
@Slf4j
public class EgovLlamaCppScoringModel implements ScoringModel {

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EgovLlamaCppScoringModel(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments.isEmpty()) {
            return Response.from(List.of());
        }

        List<String> documents = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            documents.add(segment.text());
        }

        try {
            String requestBody = objectMapper.writeValueAsString(
                    Map.of("query", query, "documents", documents));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/rerank"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("리랭커 응답 오류 - status: " + response.statusCode()
                        + ", body: " + response.body());
            }

            Double[] scores = new Double[segments.size()];
            JsonNode results = objectMapper.readTree(response.body()).path("results");
            Iterator<JsonNode> it = results.elements();
            while (it.hasNext()) {
                JsonNode result = it.next();
                int index = result.path("index").asInt();
                if (index >= 0 && index < scores.length) {
                    scores[index] = result.path("relevance_score").asDouble();
                }
            }

            // 혹시 응답에 일부 index가 누락되면(비정상 상황) 최하점으로 채워 넣어 NPE를 막는다.
            List<Double> scoreList = new ArrayList<>(scores.length);
            for (Double score : scores) {
                scoreList.add(score != null ? score : Double.NEGATIVE_INFINITY);
            }

            return Response.from(scoreList);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("리랭커 호출 실패 - baseUrl: " + baseUrl, e);
        }
    }
}
