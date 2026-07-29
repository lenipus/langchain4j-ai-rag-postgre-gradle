package com.example.chat.controller;

import com.example.chat.service.ChatModelGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama 모델 관리 API 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/ollama")
@RequiredArgsConstructor
public class EgovOllamaModelController {

    private final ChatModelGateway chatModelGateway;

    @Value("${langchain4j.ollama.chat-model.model-name}")
    private String defaultModel;

    /**
     * 설치된 Ollama 모델 목록 조회
     *
     * @return 모델 목록 및 상태 정보
     */
    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> getInstalledModels() {
        log.info("Ollama 모델 목록 요청 수신");

        Map<String, Object> response = new HashMap<>();

        try {
            // remote LLM 서버 가용 여부. ChatGPT는 이 서버와 별개 backend라 무관하게 항상 확인한다.
            boolean isRemoteAvailable = chatModelGateway.isAvailable();

            List<String> models = new ArrayList<>();
            if (isRemoteAvailable) {
                models.addAll(chatModelGateway.getInstalledModels());
            }
            chatModelGateway.getOpenAiModelOption().ifPresent(models::add);

            boolean anyAvailable = isRemoteAvailable || !models.isEmpty();
            response.put("available", anyAvailable);
            response.put("models", models);
            response.put("count", models.size());
            response.put("defaultModel", defaultModel);

            if (anyAvailable) {
                response.put("success", true);
                log.info("모델 목록 조회 성공: {}개 모델", models.size());
            } else {
                response.put("success", false);
                response.put("message", "Ollama가 설치되지 않았거나 사용할 수 없습니다.");
                log.warn("사용 가능한 모델이 없습니다");
            }

        } catch (Exception e) {
            log.error("모델 목록 조회 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "모델 목록 조회 중 오류가 발생했습니다: " + e.getMessage());
            response.put("models", List.of());
            response.put("count", 0);
        }

        return ResponseEntity.ok(response);
    }
}
