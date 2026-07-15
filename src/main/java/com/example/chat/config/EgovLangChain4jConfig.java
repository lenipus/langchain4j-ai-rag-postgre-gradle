package com.example.chat.config;

// import com.example.chat.util.ConfigUtils;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
// import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
// import dev.langchain4j.model.embedding.onnx.PoolingMode;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
// import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
// import java.nio.file.Paths;
import java.time.Duration;

/**
 * LangChain4j 설정 클래스
 * - 채팅 모델과 임베딩 모델을 각각 독립적으로 로컬/원격 전환 가능
 *   - api-type이 ollama(기본값)면 로컬/원격 Ollama 네이티브 API 사용
 *   - api-type이 openai면 OpenAI 호환 서버 사용 (base-url에 /v1 포함해서 설정)
 * - PGVector 임베딩 저장소
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EgovLangChain4jConfig /* implements InitializingBean */ {

    // private final ConfigUtils configUtils;
    private final DataSource dataSource;

    // ===== 채팅(LLM) 모델 설정 =====

    @Value("${langchain4j.ollama.chat-model.base-url}")
    private String chatModelBaseUrl;

    /** 인증이 필요할 때만 설정. api-type과는 별개 값 (있다고 무조건 openai는 아님) */
    @Value("${langchain4j.ollama.chat-model.api-key:}")
    private String chatModelApiKey;

    /** ollama(네이티브, 기본값) | openai(OpenAI 호환) */
    @Value("${langchain4j.ollama.chat-model.api-type:ollama}")
    private String chatModelApiType;

    @Value("${langchain4j.ollama.chat-model.model-name}")
    private String chatModelName;

    @Value("${langchain4j.ollama.chat-model.temperature}")
    private Double chatModelTemperature;

    @Value("${langchain4j.ollama.chat-model.timeout:60s}")
    private Duration chatModelTimeout;

    /** 컨텍스트 윈도우(num_ctx). Ollama 네이티브(api-type=ollama)일 때만 적용, 0이면 Ollama 기본값 사용 */
    @Value("${langchain4j.ollama.chat-model.num-ctx:0}")
    private Integer chatModelNumCtx;

    // ===== 임베딩 모델 설정 (채팅 모델과 완전히 독립) =====

    @Value("${langchain4j.ollama.embedding-model.base-url:http://localhost:31434}")
    private String embeddingModelBaseUrl;

    /** 인증이 필요할 때만 설정. api-type과는 별개 값 (있다고 무조건 openai는 아님) */
    @Value("${langchain4j.ollama.embedding-model.api-key:}")
    private String embeddingModelApiKey;

    /** ollama(네이티브, 기본값) | openai(OpenAI 호환) */
    @Value("${langchain4j.ollama.embedding-model.api-type:ollama}")
    private String embeddingModelApiType;

    @Value("${langchain4j.ollama.embedding-model.model-name:embeddinggemma:300m}")
    private String embeddingModelName;

    // ===== PGVector 설정 =====

    @Value("${pgvector.table-name:document_embeddings}")
    private String pgvectorTableName;

    @Value("${pgvector.dimension:768}")
    private Integer pgvectorDimension;

    @Value("${pgvector.create-table:true}")
    private Boolean createTable;

    /*
    @Override
    public void afterPropertiesSet() {
        // 외부 설정 파일에서 모델 경로 로드
        EgovEmbeddingConfig config = configUtils.loadConfig();
        if (config != null) {
            this.modelPath = config.getModelPath();
            this.tokenizerPath = config.getTokenizerPath();

            log.info("Initializing ONNX Embedding Model...");
            log.info("Model path: {}", modelPath);
            log.info("Tokenizer path: {}", tokenizerPath);
            log.info("OS: {} / Architecture: {}", System.getProperty("os.name"), System.getProperty("os.arch"));

            try {
                // 외부 파일 시스템 경로를 직접 사용 (임시 파일 복사 불필요)
                log.info("Creating OnnxEmbeddingModel instance...");
                this.embeddingModel = new OnnxEmbeddingModel(
                        Paths.get(modelPath),
                        Paths.get(tokenizerPath),
                        PoolingMode.MEAN);

                log.info("ONNX Embedding Model initialized successfully");
                log.info("Embedding dimension: {}", embeddingModel.dimension());

            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
                handleNativeLibraryError(e);
            } catch (Exception e) {
                log.error("Failed to initialize ONNX Embedding Model", e);
                throw new RuntimeException("Failed to initialize ONNX Embedding Model: " + e.getMessage(), e);
            }
        } else {
            throw new RuntimeException("Failed to load embedding configuration");
        }
    }

    private void handleNativeLibraryError(Throwable e) {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        String javaVersion = System.getProperty("java.version");

        String errorMessage = buildErrorMessage(osName, osArch, javaVersion, e.getMessage());
        log.error(errorMessage);

        throw new RuntimeException("ONNX Runtime 초기화 실패: " + e.getMessage(), e);
    }

    private String buildErrorMessage(String osName, String osArch, String javaVersion, String errorDetail) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n================================================\n");
        sb.append("ONNX Runtime 초기화 실패\n");
        sb.append("================================================\n");
        sb.append("운영체제: ").append(System.getProperty("os.name")).append(" (").append(osArch).append(")\n");
        sb.append("Java 버전: ").append(javaVersion).append("\n");
        sb.append("오류: ").append(errorDetail).append("\n\n");

        if (osName.contains("windows")) {
            sb.append(getWindowsSolution(osArch));
        } else if (osName.contains("linux")) {
            sb.append(getLinuxSolution());
        } else if (osName.contains("mac")) {
            sb.append(getMacSolution());
        } else {
            sb.append(getDefaultSolution());
        }

        sb.append("\n추가 정보:\n");
        sb.append("- 지원 플랫폼: Windows x64, Linux x64, macOS x64/ARM64\n");
        sb.append("================================================\n");

        return sb.toString();
    }

    private String getWindowsSolution(String osArch) {
        StringBuilder sb = new StringBuilder();
        sb.append("해결 방법:\n");
        sb.append("1. Visual C++ Redistributable (최신 버전)을 설치하세요.\n");

        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            sb.append("   ARM64: https://aka.ms/vs/17/release/vc_redist.arm64.exe\n");
        } else if (osArch.contains("x86") && !osArch.contains("64")) {
            sb.append("   x86: https://aka.ms/vs/17/release/vc_redist.x86.exe\n");
        } else {
            sb.append("   x64: https://aka.ms/vs/17/release/vc_redist.x64.exe\n");
        }

        sb.append("   참고: https://learn.microsoft.com/ko-kr/cpp/windows/latest-supported-vc-redist\n");
        sb.append("2. 설치 후 시스템을 재시작하세요.\n");
        sb.append("3. 애플리케이션을 다시 실행하세요.\n");

        return sb.toString();
    }

    private String getLinuxSolution() {
        return """
                해결 방법:
                1. 필요한 시스템 라이브러리를 설치하세요:
                   Ubuntu/Debian: sudo apt-get update && sudo apt-get install -y libgomp1
                   CentOS/RHEL: sudo yum install -y libgomp
                2. 애플리케이션을 다시 실행하세요.
                """;
    }

    private String getMacSolution() {
        return """
                해결 방법:
                1. Xcode Command Line Tools를 설치하세요:
                   xcode-select --install
                2. 애플리케이션을 다시 실행하세요.
                """;
    }

    private String getDefaultSolution() {
        return """
                해결 방법:
                1. ONNX Runtime이 지원하는 플랫폼인지 확인하세요.
                2. 필요한 시스템 라이브러리가 설치되어 있는지 확인하세요.
                """;
    }
    */

    /**
     * OpenAI 호환 빌더에 넘길 API 키. 인증이 필요 없는 서버라 비어있으면
     * OpenAI 클라이언트가 요구하는 자리 채움 값("not-needed")으로 대체한다.
     */
    private static String resolveApiKey(String apiKey) {
        return (apiKey == null || apiKey.isBlank()) ? "not-needed" : apiKey;
    }

    /**
     * 임베딩 모델 빈.
     * embedding-model.api-type이 openai면 OpenAI 호환 서버, 아니면(기본값 ollama) 로컬/원격 Ollama 네이티브를 사용한다.
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        boolean useOpenAi = "openai".equalsIgnoreCase(embeddingModelApiType);
        log.info("Initializing Embedding Model... (apiType={})", embeddingModelApiType);
        log.info("Base URL: {}", embeddingModelBaseUrl);
        log.info("Embedding model name: {}", embeddingModelName);

        if (useOpenAi) {
            return OpenAiEmbeddingModel.builder()
                    .baseUrl(embeddingModelBaseUrl)
                    .apiKey(resolveApiKey(embeddingModelApiKey))
                    .modelName(embeddingModelName)
                    .build();
        }
        return OllamaEmbeddingModel.builder()
                .baseUrl(embeddingModelBaseUrl)
                .modelName(embeddingModelName)
                .build();
    }

    /**
     * 채팅 모델 빈 (비스트리밍).
     * api-type이 openai면 OpenAI 호환 서버, 아니면(기본값 ollama) Ollama 네이티브를 사용한다.
     */
    @Bean
    public ChatModel chatLanguageModel() {
        boolean useOpenAi = "openai".equalsIgnoreCase(chatModelApiType);
        log.info("Initializing Chat Model... (apiType={})", chatModelApiType);
        log.info("Base URL: {}", chatModelBaseUrl);
        log.info("Model name: {}", chatModelName);
        log.info("Temperature: {}", chatModelTemperature);

        if (useOpenAi) {
            return OpenAiChatModel.builder()
                    .baseUrl(chatModelBaseUrl)
                    .apiKey(resolveApiKey(chatModelApiKey))
                    .modelName(chatModelName)
                    .temperature(chatModelTemperature)
                    .timeout(chatModelTimeout)
                    .build();
        }
        var builder = OllamaChatModel.builder()
                .baseUrl(chatModelBaseUrl)
                .modelName(chatModelName)
                .temperature(chatModelTemperature)
                .timeout(chatModelTimeout);
        if (chatModelNumCtx != null && chatModelNumCtx > 0) {
            builder.numCtx(chatModelNumCtx);
        }
        return builder.build();
    }

    /**
     * 스트리밍 채팅 모델 빈.
     * api-type이 openai면 OpenAI 호환 서버, 아니면(기본값 ollama) Ollama 네이티브를 사용한다.
     */
    @Bean
    public StreamingChatModel streamingChatLanguageModel() {
        boolean useOpenAi = "openai".equalsIgnoreCase(chatModelApiType);
        log.info("Initializing Streaming Chat Model... (apiType={})", chatModelApiType);

        if (useOpenAi) {
            return OpenAiStreamingChatModel.builder()
                    .baseUrl(chatModelBaseUrl)
                    .apiKey(resolveApiKey(chatModelApiKey))
                    .modelName(chatModelName)
                    .temperature(chatModelTemperature)
                    .timeout(chatModelTimeout)
                    .build();
        }
        var builder = OllamaStreamingChatModel.builder()
                .baseUrl(chatModelBaseUrl)
                .modelName(chatModelName)
                .temperature(chatModelTemperature)
                .timeout(chatModelTimeout);
        if (chatModelNumCtx != null && chatModelNumCtx > 0) {
            builder.numCtx(chatModelNumCtx);
        }
        return builder.build();
    }

    /**
     * PGVector 임베딩 저장소 빈
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("Initializing PGVector Embedding Store (Spring DataSource 재사용)...");
        log.info("Table name: {}", pgvectorTableName);
        log.info("Dimension: {}", pgvectorDimension);
        log.info("Create table: {}", createTable);

        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(pgvectorTableName)
                .dimension(pgvectorDimension)
                .createTable(createTable)
                .build();
    }
}
