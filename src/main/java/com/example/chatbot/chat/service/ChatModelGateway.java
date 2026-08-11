package com.example.chatbot.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * 채팅 모델(LLM)과의 연결을 전담하는 게이트웨이.
 *
 * <p>연결 설정(base-url/api-key/api-type/model-name/temperature/timeout/num-ctx/반복 억제
 * 페널티), 실제
 * {@link ChatModel}/{@link StreamingChatModel} 인스턴스 생성(기본 모델이든 사용자가 선택한
 * 다른 모델이든 이 클래스의 메서드 하나씩만 부르면 됨), Ollama 서버 상태 조회(설치된 모델
 * 목록, 사용 가능 여부, 모델별 컨텍스트 길이)까지 채팅 모델과 관련된 모든 요청 전송/응답
 * 수신을 이 클래스 하나가 담당한다. 다른 서비스(예: {@link ChatbotFactory})는 이 게이트웨이만
 * 호출해서 필요한 모델/정보를 받아 쓰고, 자기 나름의 연결 설정을 따로 갖지 않는다.</p>
 *
 * <p>예전엔 이 로직이 {@code EgovLangChain4jConfig}(기본 모델 빈 생성), {@code ChatbotFactory}
 * (사용자가 다른 모델을 선택했을 때의 동적 생성), {@code EgovOllamaModelServiceImpl}(서버 상태
 * 조회)로 3곳에 나뉘어 있었고, 그러다 보니 "기본 모델용 빈 생성 코드"와 "동적 모델 생성
 * 코드"가 사실상 같은 로직인데도 복사-붙여넣기 되어 있었다(예: 기본 모델은 num_ctx를 모델
 * 실제 한계로 깎는 로직을 안 타는데 동적 모델만 타는 불일치도 있었음). 이제는 기본 모델도
 * {@code getStreamingModel(defaultModelName)}과 동일한 경로를 타므로 그런 불일치가 없다.</p>
 */
@Slf4j
@Component
public class ChatModelGateway {

    @Value("${langchain4j.chat.ollama.base-url}")
    private String chatModelBaseUrl;

    /** 인증이 필요할 때만 설정. api-type과는 별개 값 (있다고 무조건 openai는 아님) */
    @Value("${langchain4j.chat.ollama.api-key:}")
    private String chatModelApiKey;

    /** ollama(네이티브, 기본값) | openai(OpenAI 호환) */
    @Value("${langchain4j.chat.ollama.api-type:ollama}")
    private String chatModelApiType;

    @Value("${langchain4j.chat.ollama.model-name}")
    private String defaultModelName;

    @Value("${langchain4j.chat.ollama.temperature}")
    private Double chatModelTemperature;

    @Value("${langchain4j.chat.ollama.timeout:60s}")
    private Duration chatModelTimeout;

    /** 컨텍스트 윈도우(num_ctx) 상한. Ollama 네이티브(api-type=ollama)일 때만 적용, 0이면 Ollama 기본값 사용 */
    @Value("${langchain4j.chat.ollama.num-ctx:0}")
    private Integer chatModelNumCtx;

    /**
     * temperature를 낮게(특히 0) 쓰면 그리디 디코딩이 돼서 반복 루프(같은 문단을 계속
     * 되풀이하다 잘리는 현상)에 취약해진다. 이 페널티들은 반복 자체에 직접 벌점을 줘서
     * temperature를 낮게 유지하면서도 반복을 억제한다. api-type에 따라 이름과 척도가 다르다:
     * <ul>
     *   <li>Ollama 네이티브(api-type=ollama): repeat-penalty (1.0=페널티 없음, 보통 1.1~1.3)</li>
     *   <li>OpenAI 호환(api-type=openai) - 값 범위는 둘 다 0.0=페널티 없음, OpenAI 스펙상 -2.0~2.0:
     *     <ul>
     *       <li>frequency-penalty: 같은 토큰이 나온 "횟수"에 비례해 벌점이 커진다.
     *           반복이 누적될수록 그 토큰을 또 고를 확률이 계속 낮아져, 지금 같은
     *           반복 루프(같은 문단 되풀이)를 직접 억제한다.</li>
     *       <li>presence-penalty: 그 토큰이 "한 번이라도" 나왔으면 횟수와 무관하게
     *           고정 벌점을 준다. 반복 억제보다는 새로운 단어/주제를 더 쓰도록
     *           유도하는 쪽이라, 반복 루프 억제엔 frequency-penalty보다 효과가 약하다.</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    @Value("${langchain4j.chat.ollama.repeat-penalty:1.1}")
    private Double chatModelRepeatPenalty;

    @Value("${langchain4j.chat.ollama.frequency-penalty:0.3}")
    private Double chatModelFrequencyPenalty;

    @Value("${langchain4j.chat.ollama.presence-penalty:0.0}")
    private Double chatModelPresencePenalty;

    /**
     * ChatGPT(OpenAI 실제 API) 연결 설정. {@code langchain4j.chat.ollama.*}(remote LLM
     * 서버)와는 완전히 별개 backend라 base-url을 안 주면 langchain4j가 api.openai.com/v1로
     * 접속한다.
     */
    @Value("${langchain4j.chat.openai.enabled:false}")
    private boolean openAiEnabled;

    @Value("${langchain4j.chat.openai.api-key:}")
    private String openAiApiKey;

    @Value("${langchain4j.chat.openai.model-name:gpt-4o}")
    private String openAiModelName;

    @Value("${langchain4j.chat.openai.temperature:0.3}")
    private Double openAiTemperature;

    @Value("${langchain4j.chat.openai.timeout:120s}")
    private Duration openAiTimeout;

    /** 모델 목록에서 ChatGPT 항목을 구분하는 접두사. 프론트는 이 값을 그대로 model 파라미터로 돌려보낸다. */
    private static final String OPENAI_MODEL_PREFIX = "chatgpt:";

    private boolean isOpenAiModel(String modelName) {
        return modelName != null && modelName.startsWith(OPENAI_MODEL_PREFIX);
    }

    /**
     * ChatGPT를 모델 목록에 노출할지 여부. enabled=true고 api-key가 설정돼 있어야 하며,
     * remote LLM 서버 가용성과는 무관하다(호출부가 서버 다운 여부로 이 값을 걸러내면 안 됨).
     */
    public Optional<String> getOpenAiModelOption() {
        if (!openAiEnabled || openAiApiKey == null || openAiApiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(OPENAI_MODEL_PREFIX + openAiModelName);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // 모델별 컨텍스트 길이는 자주 안 바뀌므로, 조회 한 번이면 계속 재사용한다(매 요청마다
    // /api/show를 다시 부르지 않도록).
    private final Map<String, Integer> contextLengthCache = new ConcurrentHashMap<>();

    private boolean isRemoteMode() {
        return "openai".equalsIgnoreCase(chatModelApiType);
    }

    /**
     * modelName이 없으면(null/빈 문자열) 기본 모델명을 반환한다. 호출부(예: {@code ChatbotFactory}의
     * 로그 메시지)가 "결국 어느 모델이 쓰이는지" 표시할 때도 쓸 수 있도록 공개해둔다.
     */
    public String resolveModelName(String modelName) {
        return (modelName == null || modelName.trim().isEmpty()) ? defaultModelName : modelName;
    }

    /**
     * OpenAI 호환 빌더에 넘길 API 키. 인증이 필요 없는 서버라 비어있으면
     * OpenAI 클라이언트가 요구하는 자리 채움 값("not-needed")으로 대체한다.
     */
    private static String resolveApiKey(String apiKey) {
        return (apiKey == null || apiKey.isBlank()) ? "not-needed" : apiKey;
    }

    /**
     * 스트리밍 채팅 모델을 생성한다. modelName이 null/빈 문자열이면 기본 모델(설정값)로,
     * 아니면 그 모델명으로 만든다 - 호출부는 "기본 모델인지 아닌지"를 몰라도 된다.
     * api-type이 openai면 OpenAI 호환 서버, 아니면(기본값 ollama) Ollama 네이티브를 사용한다.
     */
    public StreamingChatModel getStreamingModel(String modelName) {
        if (isOpenAiModel(modelName)) {
            String actualModelName = modelName.substring(OPENAI_MODEL_PREFIX.length());
            log.info("스트리밍 모델 생성 - backend: openai(ChatGPT), model: {}, temperature: {}, timeout: {}",
                    actualModelName, openAiTemperature, openAiTimeout);
            return OpenAiStreamingChatModel.builder()
                    .apiKey(openAiApiKey)
                    .modelName(actualModelName)
                    .temperature(openAiTemperature)
                    .timeout(openAiTimeout)
                    .logRequests(true)
                    .logResponses(true)
                    .build();
        }
        String effectiveModelName = resolveModelName(modelName);
        if (isRemoteMode()) {
            log.info("스트리밍 모델 생성 - backend: openai 호환(remote), model: {}, temperature: {}, timeout: {}, "
                            + "frequencyPenalty: {}, presencePenalty: {}",
                    effectiveModelName, chatModelTemperature, chatModelTimeout,
                    chatModelFrequencyPenalty, chatModelPresencePenalty);
            return OpenAiStreamingChatModel.builder()
                    .baseUrl(chatModelBaseUrl)
                    .apiKey(resolveApiKey(chatModelApiKey))
                    .modelName(effectiveModelName)
                    .temperature(chatModelTemperature)
                    .timeout(chatModelTimeout)
                    .frequencyPenalty(chatModelFrequencyPenalty)
                    .presencePenalty(chatModelPresencePenalty)
                    .logRequests(true)
                    .logResponses(true)
                    .build();
        }
        int numCtx = resolveNumCtx(effectiveModelName);
        log.info("스트리밍 모델 생성 - backend: ollama, model: {}, temperature: {}, timeout: {}, "
                        + "repeatPenalty: {}, numCtx: {}",
                effectiveModelName, chatModelTemperature, chatModelTimeout, chatModelRepeatPenalty, numCtx);
        var builder = OllamaStreamingChatModel.builder()
                .baseUrl(chatModelBaseUrl)
                .modelName(effectiveModelName)
                .temperature(chatModelTemperature)
                .timeout(chatModelTimeout)
                .repeatPenalty(chatModelRepeatPenalty)
                .logRequests(true)
                .logResponses(true);
        if (numCtx > 0) {
            builder.numCtx(numCtx);
        }
        return builder.build();
    }

    /**
     * 비스트리밍 채팅 모델을 생성한다(질의 압축, 프롬프트 테스트 등에 사용). 규칙은
     * {@link #getStreamingModel(String)}과 동일하다.
     */
    public ChatModel getChatModel(String modelName) {
        if (isOpenAiModel(modelName)) {
            String actualModelName = modelName.substring(OPENAI_MODEL_PREFIX.length());
            log.info("비스트리밍 모델 생성 - backend: openai(ChatGPT), model: {}, temperature: {}, timeout: {}",
                    actualModelName, openAiTemperature, openAiTimeout);
            return OpenAiChatModel.builder()
                    .apiKey(openAiApiKey)
                    .modelName(actualModelName)
                    .temperature(openAiTemperature)
                    .timeout(openAiTimeout)
                    .logRequests(true)
                    .logResponses(true)
                    .build();
        }
        String effectiveModelName = resolveModelName(modelName);
        if (isRemoteMode()) {
            log.info("비스트리밍 모델 생성 - backend: openai 호환(remote), model: {}, temperature: {}, timeout: {}, "
                            + "frequencyPenalty: {}, presencePenalty: {}",
                    effectiveModelName, chatModelTemperature, chatModelTimeout,
                    chatModelFrequencyPenalty, chatModelPresencePenalty);
            return OpenAiChatModel.builder()
                    .baseUrl(chatModelBaseUrl)
                    .apiKey(resolveApiKey(chatModelApiKey))
                    .modelName(effectiveModelName)
                    .temperature(chatModelTemperature)
                    .timeout(chatModelTimeout)
                    .frequencyPenalty(chatModelFrequencyPenalty)
                    .presencePenalty(chatModelPresencePenalty)
                    .logRequests(true)
                    .logResponses(true)
                    .build();
        }
        int numCtx = resolveNumCtx(effectiveModelName);
        log.info("비스트리밍 모델 생성 - backend: ollama, model: {}, temperature: {}, timeout: {}, "
                        + "repeatPenalty: {}, numCtx: {}",
                effectiveModelName, chatModelTemperature, chatModelTimeout, chatModelRepeatPenalty, numCtx);
        var builder = OllamaChatModel.builder()
                .baseUrl(chatModelBaseUrl)
                .modelName(effectiveModelName)
                .temperature(chatModelTemperature)
                .timeout(chatModelTimeout)
                .repeatPenalty(chatModelRepeatPenalty)
                .logRequests(true)
                .logResponses(true);
        if (numCtx > 0) {
            builder.numCtx(numCtx);
        }
        return builder.build();
    }

    /**
     * 실제로 Ollama에 요청할 num_ctx 값을 모델별로 정한다.
     *
     * <p>{@code langchain4j.chat.ollama.num-ctx}(설정값, 예: 40960)는 상한선으로 쓰고,
     * 실제 선택된 모델이 그보다 작은 컨텍스트만 지원하면({@link #getContextLength}로 조회)
     * 모델 한계에 맞춰 깎는다. 모델 정보를 못 가져오면 설정값을 그대로 쓰고, 설정값이
     * 없으면(0) 모델 한계를 그대로 쓴다 - 둘 다 없으면 0을 반환해 Ollama 자체 기본값에 맡긴다.
     *
     * <p>예: 상한 40960 기준으로 gemma2:2b(한계 8192)는 8192로, qwen3:4b(한계 40960)는
     * 40960 그대로, hyperclova(한계 131072)는 상한인 40960으로 깎인다.</p>
     */
    // 테스트에서 직접 검증할 수 있도록 package-private로 연다.
    int resolveNumCtx(String modelName) {
        Integer modelMaxContext = getContextLength(modelName).orElse(null);
        if (modelMaxContext == null) {
            return chatModelNumCtx != null ? chatModelNumCtx : 0;
        }
        if (chatModelNumCtx != null && chatModelNumCtx > 0) {
            return Math.min(chatModelNumCtx, modelMaxContext);
        }
        return modelMaxContext;
    }

    /**
     * {@code /api/show}는 Ollama 네이티브 API 전용이라 원격(openai 호환) 모드에서는
     * 시도하지 않고 바로 empty를 반환한다. {@code model_info} 안의 키 이름은 아키텍처마다
     * 다르므로(예: {@code qwen3.context_length}, {@code gemma2.context_length})
     * {@code .context_length}로 끝나는 키를 찾아서 쓴다.
     */
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

    /** 데몬 응답 지연 등으로 일시적 실패(빈 목록/불가 판정)가 났을 때 재시도 전 대기 시간(1차) */
    private static final long TRANSIENT_FAILURE_RETRY_DELAY_MS = 500;

    /**
     * 일시적 실패 시 재시도할 최대 횟수. 원격(openai 호환) 모드는 공인 IP가 아닌 가정용
     * 회선/동적 DNS(iptime 등) 뒤에 있는 서버로 나가는 경우가 있어, 로컬 ollama 데몬보다
     * 응답이 느리거나 일시적으로 끊기는 일이 더 잦다 - 재시도를 매 시도마다 대기 시간을
     * 늘려서(500ms, 1000ms, 1500ms) 그 유예 시간을 벌어준다.
     */
    private static final int MAX_TRANSIENT_FAILURE_RETRIES = 3;

    /**
     * 설치된 Ollama 모델 목록 조회 (임베딩 전용 모델 제외)
     *
     * <p>{@code ollama --version}(가용성 체크)은 데몬 없이도 성공하지만 {@code ollama list}는
     * 백그라운드 데몬이 응답해야 하므로, 데몬이 막 기동된 직후처럼 데몬 응답이 늦는 시점에는
     * 가용은 true이면서 목록만 비어 오는 경우가 있다. 원격(openai 호환) 모드의 HTTP 호출도
     * 마찬가지로 타임아웃/일시적 오류가 나면 빈 목록으로 수렴하므로 같은 재시도로 흡수된다
     * (참고: {@link #getRemoteModels()}는 예외를 내부에서 잡아 빈 목록을 반환함).
     * 빈 목록이면 점점 늘어나는 대기 시간을 두고 최대 {@value #MAX_TRANSIENT_FAILURE_RETRIES}번
     * 재시도한다.</p>
     *
     * @return 모델명 리스트
     */
    public List<String> getInstalledModels() {
        List<String> models = fetchInstalledModels();
        int attempt = 0;
        while (models.isEmpty() && attempt < MAX_TRANSIENT_FAILURE_RETRIES) {
            attempt++;
            long delay = TRANSIENT_FAILURE_RETRY_DELAY_MS * attempt;
            log.warn("모델 목록이 비어있어 {}ms 후 재시도합니다 ({}/{}).", delay, attempt, MAX_TRANSIENT_FAILURE_RETRIES);
            sleepQuietly(delay);
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
     * Ollama 서비스 사용 가능 여부 확인.
     *
     * <p>단발성 체크라 데몬이 막 기동된 직후 등에는 일시적으로 false가 나올 수 있어서,
     * {@link #getInstalledModels()}와 같은 재시도(점점 늘어나는 대기 시간, 최대
     * {@value #MAX_TRANSIENT_FAILURE_RETRIES}번)를 적용한다. 이 체크가 재시도 없이 false로
     * 끝나버리면 호출부(컨트롤러)가 모델 목록 조회 자체를 시도하지 않고 바로 "사용 불가"로
     * 응답해버리기 때문이다.</p>
     *
     * @return true if Ollama is available, false otherwise
     */
    public boolean isAvailable() {
        boolean available = checkAvailability();
        int attempt = 0;
        while (!available && attempt < MAX_TRANSIENT_FAILURE_RETRIES) {
            attempt++;
            long delay = TRANSIENT_FAILURE_RETRY_DELAY_MS * attempt;
            log.warn("Ollama 사용 가능 여부 확인에 실패해 {}ms 후 재시도합니다 ({}/{}).", delay, attempt, MAX_TRANSIENT_FAILURE_RETRIES);
            sleepQuietly(delay);
            available = checkAvailability();
        }
        return available;
    }

    // 테스트에서 실제 프로세스/HTTP 호출 없이 재시도 동작만 검증할 수 있도록 protected로 연다.
    protected boolean checkAvailability() {
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
