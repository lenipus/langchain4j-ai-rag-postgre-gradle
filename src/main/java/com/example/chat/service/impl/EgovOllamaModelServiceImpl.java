package com.example.chat.service.impl;

import com.example.chat.service.EgovOllamaModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ollama 모델 관리 서비스 구현 (채팅 모델 드롭다운용)
 * - api-type이 ollama(기본값): ollama CLI 명령어로 로컬 조회
 * - api-type이 openai: HTTP로 /models 엔드포인트 조회 (필요 시 Bearer 인증)
 */
@Slf4j
@Service
public class EgovOllamaModelServiceImpl extends EgovAbstractServiceImpl implements EgovOllamaModelService {

    @Value("${langchain4j.ollama.chat-model.base-url}")
    private String chatModelBaseUrl;

    /** 인증이 필요할 때만 설정. api-type과는 별개 값 (있다고 무조건 openai는 아님) */
    @Value("${langchain4j.ollama.chat-model.api-key:}")
    private String chatModelApiKey;

    /** ollama(네이티브, 기본값) | openai(OpenAI 호환) */
    @Value("${langchain4j.ollama.chat-model.api-type:ollama}")
    private String chatModelApiType;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private boolean isRemoteMode() {
        return "openai".equalsIgnoreCase(chatModelApiType);
    }

    // 모델별 컨텍스트 길이는 자주 안 바뀌므로, 조회 한 번이면 계속 재사용한다(매 요청마다
    // /api/show를 다시 부르지 않도록).
    private final Map<String, Integer> contextLengthCache = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     *
     * <p>{@code /api/show}는 Ollama 네이티브 API 전용이라 원격(openai 호환) 모드에서는
     * 시도하지 않고 바로 empty를 반환한다. {@code model_info} 안의 키 이름은 아키텍처마다
     * 다르므로(예: {@code qwen3.context_length}, {@code gemma2.context_length})
     * {@code .context_length}로 끝나는 키를 찾아서 쓴다.</p>
     */
    @Override
    public Optional<Integer> getContextLength(String modelName) {
        if (modelName == null || modelName.isBlank() || isRemoteMode()) {
            return Optional.empty();
        }

        Integer cached = contextLengthCache.get(modelName);
        if (cached != null) {
            return Optional.of(cached);
        }

        String url = chatModelBaseUrl.replaceAll("/+$", "") + "/api/show";
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of("model", modelName));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("모델 정보 조회 실패 - model: {}, status: {}", modelName, response.statusCode());
                return Optional.empty();
            }

            JsonNode modelInfo = objectMapper.readTree(response.body()).path("model_info");
            Iterator<String> fieldNames = modelInfo.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                if (field.endsWith(".context_length")) {
                    int contextLength = modelInfo.get(field).asInt();
                    contextLengthCache.put(modelName, contextLength);
                    log.debug("모델 컨텍스트 길이 조회 - model: {}, context_length: {}", modelName, contextLength);
                    return Optional.of(contextLength);
                }
            }
            log.warn("모델 정보에서 context_length를 찾지 못했습니다 - model: {}", modelName);
            return Optional.empty();
        } catch (IOException | InterruptedException e) {
            log.warn("모델 컨텍스트 길이 조회 중 오류 발생 - model: {}, url: {}", modelName, url, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    /** ollama list 등이 데몬 응답 지연 등으로 빈 목록을 반환했을 때 재시도 전 대기 시간 */
    private static final long EMPTY_RESULT_RETRY_DELAY_MS = 300;

    /**
     * 설치된 Ollama 모델 목록 조회 (임베딩 전용 모델 제외)
     *
     * <p>{@code ollama --version}(가용성 체크)은 데몬 없이도 성공하지만 {@code ollama list}는
     * 백그라운드 데몬이 응답해야 하므로, 데몬이 막 기동된 직후처럼 데몬 응답이 늦는 시점에는
     * 가용은 true이면서 목록만 비어 오는 경우가 있다. 빈 목록이면 한 번만 짧게 재시도한다.</p>
     *
     * @return 모델명 리스트
     */
    @Override
    public List<String> getInstalledModels() {
        List<String> models = fetchInstalledModels();
        if (models.isEmpty()) {
            log.warn("모델 목록이 비어있어 {}ms 후 재시도합니다.", EMPTY_RESULT_RETRY_DELAY_MS);
            sleepQuietly(EMPTY_RESULT_RETRY_DELAY_MS);
            models = fetchInstalledModels();
        }
        return models;
    }

    // 테스트에서 실제 프로세스/HTTP 호출 없이 재시도 동작만 검증할 수 있도록 protected로 연다.
    protected List<String> fetchInstalledModels() {
        return isRemoteMode() ? getRemoteModels() : getLocalModels();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 원격 Ollama 호환 서버의 OpenAI 호환 /models 엔드포인트에서 모델 목록 조회
     */
    private List<String> getRemoteModels() {
        List<String> models = new ArrayList<>();
        String url = chatModelBaseUrl.replaceAll("/+$", "") + "/models";

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET();
            if (chatModelApiKey != null && !chatModelApiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + chatModelApiKey);
            }
            HttpRequest request = requestBuilder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("원격 모델 목록 조회 실패 - status: {}, url: {}", response.statusCode(), url);
                return models;
            }

            JsonNode data = objectMapper.readTree(response.body()).path("data");
            for (JsonNode model : data) {
                String modelName = model.path("id").asText(null);
                if (modelName == null || modelName.isBlank()) {
                    continue;
                }
                if (modelName.toLowerCase().contains("embed")) {
                    log.debug("임베딩 전용 모델 제외: {}", modelName);
                    continue;
                }
                models.add(modelName);
                log.debug("발견된 모델: {}", modelName);
            }
        } catch (IOException | InterruptedException e) {
            log.error("원격 모델 목록 조회 중 오류 발생 - url: {}", url, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("발견된 Ollama 모델 수: {}", models.size());
        return models;
    }

    /**
     * 로컬 PC에 설치된 ollama CLI(ollama list)로 모델 목록 조회
     */
    private List<String> getLocalModels() {
        List<String> models = new ArrayList<>();

        try {
            String[] command = new String[] { "ollama", "list" };
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            setupEnvironment(processBuilder);

            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean isFirstLine = true;

                while ((line = reader.readLine()) != null) {
                    if (isFirstLine) {
                        isFirstLine = false;
                        continue;
                    }
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 1) {
                        String modelName = parts[0].trim();
                        if (modelName.toLowerCase().contains("embed")) {
                            log.debug("임베딩 전용 모델 제외: {}", modelName);
                            continue;
                        }
                        if (!modelName.isEmpty() && !modelName.equals("NAME")) {
                            models.add(modelName);
                            log.debug("발견된 모델: {}", modelName);
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("ollama list 명령어가 비정상 종료되었습니다. 종료 코드: {}", exitCode);
            }

        } catch (IOException e) {
            log.error("ollama list 명령어 실행 중 IOException 발생", e);
        } catch (InterruptedException e) {
            log.error("ollama list 명령어 실행 중 InterruptedException 발생", e);
            Thread.currentThread().interrupt();
        }

        log.info("발견된 Ollama 모델 수: {}", models.size());
        return models;
    }

    /**
     * Ollama 서비스 사용 가능 여부 확인
     *
     * @return true if Ollama is available, false otherwise
     */
    @Override
    public boolean isOllamaAvailable() {
        return isRemoteMode() ? isRemoteAvailable() : isLocalAvailable();
    }

    /**
     * 원격 Ollama 호환 서버 접속 가능 여부 확인 (/models 엔드포인트 HTTP 응답으로 판단)
     */
    private boolean isRemoteAvailable() {
        String url = chatModelBaseUrl.replaceAll("/+$", "") + "/models";
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET();
            if (chatModelApiKey != null && !chatModelApiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + chatModelApiKey);
            }
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean available = response.statusCode() == 200;
            log.debug("원격 Ollama 사용 가능 여부: {} (status: {})", available, response.statusCode());
            return available;
        } catch (IOException | InterruptedException e) {
            log.debug("원격 Ollama 사용 가능 여부 확인 중 오류 발생", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * 로컬 ollama CLI 사용 가능 여부 확인 (ollama --version)
     */
    private boolean isLocalAvailable() {
        try {
            String[] command = new String[] { "ollama", "--version" };
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            setupEnvironment(processBuilder);

            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            boolean available = exitCode == 0;
            log.debug("Ollama 사용 가능 여부: {}", available);
            return available;

        } catch (IOException | InterruptedException e) {
            log.debug("Ollama 사용 가능 여부 확인 중 오류 발생 (설치되지 않았을 수 있음)", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * OS별 환경 설정
     *
     * @param processBuilder ProcessBuilder 인스턴스
     */
    private void setupEnvironment(ProcessBuilder processBuilder) {
        String os = System.getProperty("os.name").toLowerCase();
        log.debug("현재 OS: {}", os);

        if (os.contains("win")) {
            // Windows: PATH에 일반적인 Ollama 설치 경로 추가
            String path = System.getenv("PATH");
            String ollamaPath = System.getenv("LOCALAPPDATA") + "\\Programs\\Ollama";
            processBuilder.environment().put("PATH", path + ";" + ollamaPath);
        } else if (os.contains("mac")) {
            // macOS: PATH에 Homebrew 및 일반적인 설치 경로 추가
            String path = System.getenv("PATH");
            processBuilder.environment().put("PATH",
                    path + ":/usr/local/bin:/opt/homebrew/bin");
        } else {
            // Linux: PATH에 일반적인 설치 경로 추가
            String path = System.getenv("PATH");
            processBuilder.environment().put("PATH",
                    path + ":/usr/local/bin:/usr/bin");
        }
    }
}
