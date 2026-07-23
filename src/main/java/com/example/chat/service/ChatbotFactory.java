package com.example.chat.service;

import com.example.chat.config.EgovLoggingContentRetriever;
import com.example.chat.repository.PersistentChatMemoryStore;
import com.example.chat.repository.RagRetrievalLogRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 챗봇 인스턴스 생성 Factory
 * - 동적 모델 선택 지원 (요청별 다른 LLM 모델 사용 가능)
 * - 세션별 ChatMemory 생성 및 PersistentChatMemoryStore 연동
 * - 기본 모델은 빈으로 주입받아 재사용
 */
@Slf4j
@Component
public class ChatbotFactory {

    private final ContentRetriever selectedRetriever;
    private final RagRetrievalLogRepository ragRetrievalLogRepository;
    private final PersistentChatMemoryStore chatMemoryStore;
    private final StreamingChatModel defaultStreamingModel;
    private final ChatModel queryCompressionChatModel;
    private final EgovOllamaModelService ollamaModelService;

    @Value("${rag.query-compression.enabled:true}")
    private boolean queryCompressionEnabled;

    @Value("${langchain4j.ollama.chat-model.base-url}")
    private String chatModelBaseUrl;

    /** 인증이 필요할 때만 설정. api-type과는 별개 값 (있다고 무조건 openai는 아님) */
    @Value("${langchain4j.ollama.chat-model.api-key:}")
    private String chatModelApiKey;

    /** ollama(네이티브, 기본값) | openai(OpenAI 호환) */
    @Value("${langchain4j.ollama.chat-model.api-type:ollama}")
    private String chatModelApiType;

    @Value("${langchain4j.ollama.chat-model.model-name}")
    private String defaultModelName;

    @Value("${langchain4j.ollama.chat-model.temperature}")
    private Double defaultTemperature;

    @Value("${langchain4j.ollama.chat-model.timeout:60s}")
    private Duration defaultTimeout;

    /** 컨텍스트 윈도우(num_ctx). Ollama 네이티브(api-type=ollama)일 때만 적용, 0이면 Ollama 기본값 사용 */
    @Value("${langchain4j.ollama.chat-model.num-ctx:0}")
    private Integer chatModelNumCtx;

    @Value("${chat.memory.max-messages:20}")
    private int maxMessages;

    /**
     * @param hybridContentRetriever 하이브리드 검색 빈. {@code rag.retrieval.hybrid.enabled=true}
     *                               일 때만 등록되며 off(기본) 상태에서는 null 이다.
     * @param denseContentRetriever  dense 벡터 검색 빈. 항상 존재한다.
     */
    public ChatbotFactory(
            @Qualifier("hybridContentRetriever") @Autowired(required = false) ContentRetriever hybridContentRetriever,
            @Qualifier("contentRetriever") ContentRetriever denseContentRetriever,
            PersistentChatMemoryStore chatMemoryStore,
            StreamingChatModel defaultStreamingModel,
            ChatModel queryCompressionChatModel,
            RagRetrievalLogRepository ragRetrievalLogRepository,
            EgovOllamaModelService ollamaModelService) {
        // 하이브리드 빈이 등록된 경우 우선 사용하고, 없으면 기존 dense 경로를 유지한다.
        // 실제 EgovLoggingContentRetriever는 질의(턴)마다 turnId를 새로 발급해 createRagChatbot()에서
        // 매번 새로 감싸 만든다 (아래 selectedRetriever는 그 delegate로만 쓰인다).
        this.selectedRetriever = (hybridContentRetriever != null) ? hybridContentRetriever : denseContentRetriever;
        this.ragRetrievalLogRepository = ragRetrievalLogRepository;
        this.chatMemoryStore = chatMemoryStore;
        this.defaultStreamingModel = defaultStreamingModel;
        this.queryCompressionChatModel = queryCompressionChatModel;
        this.ollamaModelService = ollamaModelService;

        if (hybridContentRetriever != null) {
            log.info("ChatbotFactory - 하이브리드 ContentRetriever 사용");
        }
    }

    /**
     * RAG 챗봇 인스턴스 생성
     * - 세션별 ChatMemory 생성하여 AiServices에 주입
     * - ContentRetriever를 통한 자동 RAG 검색
     *
     * @param modelName 사용할 모델명 (null이면 기본 모델)
     * @param sessionId 세션 ID (메모리 관리용)
     * @return RagChatbot 인스턴스
     */
    public RagChatbot createRagChatbot(String modelName, String sessionId) {
        StreamingChatModel streamingModel = isDefaultModel(modelName)
                ? defaultStreamingModel
                : createStreamingModel(modelName);

        // 이 질의(턴) 하나를 위한 키. rag_retrieval_logs와 chat_memory 양쪽에 같은 값이
        // 찍혀서, "이 질문/답변에 실제로 RAG가 뭘 검색해줬는지"를 정확히 조인해 추적할 수 있다.
        String turnId = UUID.randomUUID().toString();

        log.info("RAG 챗봇 생성 - 모델: {}, 세션: {}, 턴: {}, 질의 압축: {}",
                isDefaultModel(modelName) ? defaultModelName : modelName, sessionId, turnId, queryCompressionEnabled);

        // 질의 압축이 켜져 있으면 검색엔 압축된 질의가 쓰이므로, 사용자가 실제로 입력한
        // 원본 질의는 압축 단계(queryTransformer)에서 이 홀더에 채워 넣고
        // EgovLoggingContentRetriever가 감사 로그에 같이 남길 때 읽어간다.
        AtomicReference<String> originalQueryTextHolder = new AtomicReference<>();

        ContentRetriever loggingRetriever = new EgovLoggingContentRetriever(
                selectedRetriever, ragRetrievalLogRepository, sessionId, turnId, originalQueryTextHolder);

        AiServices<RagChatbot> builder = AiServices.builder(RagChatbot.class)
                .streamingChatModel(streamingModel)
                .chatMemory(createChatMemory(sessionId, turnId));

        if (queryCompressionEnabled) {
            // 후속 질문(대명사·생략형)이 이전 대화를 반영 못 한 채 그대로 검색되는 문제를
            // 완화하기 위해, 검색 전에 (이전 대화 + 현재 질문)을 독립형 질문으로 압축한다.
            ChatModel compressionModel = isDefaultModel(modelName)
                    ? queryCompressionChatModel
                    : createQueryCompressionModel(modelName);
            RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                    .queryTransformer(loggingQueryTransformer(
                            new CompressingQueryTransformer(compressionModel), originalQueryTextHolder))
                    .contentRetriever(loggingRetriever)
                    .build();
            builder.retrievalAugmentor(retrievalAugmentor);
        } else {
            builder.contentRetriever(loggingRetriever);
        }

        return builder.build();
    }

    /**
     * {@link QueryTransformer} 데코레이터. 압축 전/후 질의를 로그로 남기고, 원본 질의는
     * {@code originalQueryTextHolder}에 채워 넣어 {@link EgovLoggingContentRetriever}가
     * {@code rag_retrieval_logs.original_query_text}에 같이 저장할 수 있게 한다.
     */
    private QueryTransformer loggingQueryTransformer(QueryTransformer delegate,
                                                      AtomicReference<String> originalQueryTextHolder) {
        return query -> {
            originalQueryTextHolder.set(query.text());
            Collection<Query> transformed = delegate.transform(query);
            for (Query t : transformed) {
                log.info("질의 압축 - 원본: [{}] -> 압축: [{}]", query.text(), t.text());
            }
            return transformed;
        };
    }

    /**
     * Simple 챗봇 인스턴스 생성
     *
     * @param modelName 사용할 모델명 (null이면 기본 모델)
     * @param sessionId 세션 ID (메모리 관리용)
     * @return SimpleChatbot 인스턴스
     */
    public SimpleChatbot createSimpleChatbot(String modelName, String sessionId) {
        StreamingChatModel streamingModel = isDefaultModel(modelName)
                ? defaultStreamingModel
                : createStreamingModel(modelName);

        log.info("Simple 챗봇 생성 - 모델: {}, 세션: {}",
                isDefaultModel(modelName) ? defaultModelName : modelName, sessionId);

        return AiServices.builder(SimpleChatbot.class)
                .streamingChatModel(streamingModel)
                .chatMemory(createChatMemory(sessionId))
                .build();
    }

    /**
     * SQL 생성 챗봇 인스턴스 생성
     *
     * @param modelName 사용할 모델명 (null이면 기본 모델)
     * @param sessionId 세션 ID (메모리 관리용)
     * @return SqlGenChatbot 인스턴스
     */
    public SqlGenChatbot createSqlGenChatbot(String modelName, String sessionId) {
        StreamingChatModel streamingModel = isDefaultModel(modelName)
                ? defaultStreamingModel
                : createStreamingModel(modelName);

        log.info("SQL 생성 챗봇 생성 - 모델: {}, 세션: {}",
                isDefaultModel(modelName) ? defaultModelName : modelName, sessionId);

        return AiServices.builder(SqlGenChatbot.class)
                .streamingChatModel(streamingModel)
                .chatMemory(createChatMemory(sessionId))
                .build();
    }

    /**
     * MessageWindowChatMemory 생성 (턴 키 없이 - 단순 채팅용)
     * - 최근 N개 메시지만 유지
     * - PersistentChatMemoryStore를 통해 PostgreSQL에 자동 저장
     */
    private MessageWindowChatMemory createChatMemory(String sessionId) {
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(maxMessages)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    /**
     * MessageWindowChatMemory 생성 (RAG용)
     * - chatMemoryStore를 turnId로 감싸, 이 턴에서 저장되는 메시지에 turnId가 찍히게 한다.
     */
    private MessageWindowChatMemory createChatMemory(String sessionId, String turnId) {
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(maxMessages)
                .chatMemoryStore(new TurnTaggingChatMemoryStore(chatMemoryStore, turnId))
                .build();
    }

    /**
     * {@link ChatMemoryStore} 데코레이터. updateMessages()만 가로채 이번 질의(턴)의
     * turnId를 넘겨주고, 나머지는 delegate에 그대로 위임한다. createRagChatbot() 호출마다
     * 새로 만들어져 turnId를 클로저로 들고 있으므로, 실제 저장이 어느 스레드에서
     * 일어나든(ThreadLocal과 달리) 안전하게 전달된다.
     */
    @RequiredArgsConstructor
    private static class TurnTaggingChatMemoryStore implements ChatMemoryStore {

        private final PersistentChatMemoryStore delegate;
        private final String turnId;

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return delegate.getMessages(memoryId);
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            delegate.updateMessages(memoryId, messages, turnId);
        }

        @Override
        public void deleteMessages(Object memoryId) {
            delegate.deleteMessages(memoryId);
        }
    }

    /**
     * 스트리밍 모델 생성.
     * api-type이 openai면 OpenAI 호환 서버, 아니면(기본값 ollama) Ollama 네이티브를 사용한다.
     */
    private StreamingChatModel createStreamingModel(String modelName) {
        if ("openai".equalsIgnoreCase(chatModelApiType)) {
            String apiKey = (chatModelApiKey == null || chatModelApiKey.isBlank()) ? "not-needed" : chatModelApiKey;
            return OpenAiStreamingChatModel.builder()
                    .baseUrl(chatModelBaseUrl)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature(defaultTemperature)
                    .timeout(defaultTimeout)
                    .build();
        }
        var builder = OllamaStreamingChatModel.builder()
                .baseUrl(chatModelBaseUrl)
                .modelName(modelName)
                .temperature(defaultTemperature)
                .timeout(defaultTimeout);
        int numCtx = resolveNumCtx(modelName);
        if (numCtx > 0) {
            builder.numCtx(numCtx);
        }
        return builder.build();
    }

    /**
     * 질의 압축용(비스트리밍) 모델 생성. createStreamingModel()과 동일한 규칙(api-type에 따라
     * OpenAI 호환/Ollama 네이티브)을 따르되, 스트리밍이 필요 없는 {@link ChatModel}을 만든다.
     */
    private ChatModel createQueryCompressionModel(String modelName) {
        if ("openai".equalsIgnoreCase(chatModelApiType)) {
            String apiKey = (chatModelApiKey == null || chatModelApiKey.isBlank()) ? "not-needed" : chatModelApiKey;
            return OpenAiChatModel.builder()
                    .baseUrl(chatModelBaseUrl)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature(defaultTemperature)
                    .timeout(defaultTimeout)
                    .build();
        }
        var builder = OllamaChatModel.builder()
                .baseUrl(chatModelBaseUrl)
                .modelName(modelName)
                .temperature(defaultTemperature)
                .timeout(defaultTimeout);
        int numCtx = resolveNumCtx(modelName);
        if (numCtx > 0) {
            builder.numCtx(numCtx);
        }
        return builder.build();
    }

    /**
     * 실제로 Ollama에 요청할 num_ctx 값을 모델별로 정한다.
     *
     * <p>{@code langchain4j.ollama.chat-model.num-ctx}(설정값, 예: 40960)는 상한선으로 쓰고,
     * 실제 선택된 모델이 그보다 작은 컨텍스트만 지원하면({@link EgovOllamaModelService#getContextLength}로
     * 조회) 모델 한계에 맞춰 깎는다. 모델 정보를 못 가져오면 설정값을 그대로 쓰고, 설정값이
     * 없으면(0) 모델 한계를 그대로 쓴다 - 둘 다 없으면 0을 반환해 Ollama 자체 기본값에 맡긴다.
     *
     * <p>예: 상한 40960 기준으로 gemma2:2b(한계 8192)는 8192로, qwen3:4b(한계 40960)는
     * 40960 그대로, hyperclova(한계 131072)는 상한인 40960으로 깎인다.</p>
     */
    // 테스트에서 직접 검증할 수 있도록 package-private로 연다.
    int resolveNumCtx(String modelName) {
        Integer modelMaxContext = ollamaModelService.getContextLength(modelName).orElse(null);
        if (modelMaxContext == null) {
            return chatModelNumCtx != null ? chatModelNumCtx : 0;
        }
        if (chatModelNumCtx != null && chatModelNumCtx > 0) {
            return Math.min(chatModelNumCtx, modelMaxContext);
        }
        return modelMaxContext;
    }

    /**
     * 기본 모델인지 확인
     */
    private boolean isDefaultModel(String modelName) {
        return modelName == null || modelName.trim().isEmpty() || modelName.equals(defaultModelName);
    }
}
