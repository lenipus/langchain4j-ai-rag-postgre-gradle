package com.example.chatbot.chat.service;

import com.example.chatbot.chat.config.EgovKeywordBoostContentRetriever;
import com.example.chatbot.chat.config.EgovLoggingContentRetriever;
import com.example.chatbot.chat.config.SynonymQueryNormalizer;
import com.example.chatbot.chat.dto.RagProcessingStage;
import com.example.chatbot.chat.repository.PersistentChatMemoryStore;
import com.example.chatbot.chat.repository.RagRetrievalLogRepository;
import com.example.chatbot.sqlgen.service.SqlGenChatbot;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 챗봇 인스턴스 생성 Factory
 * - 세션별 ChatMemory 생성 및 PersistentChatMemoryStore 연동
 * - 실제 모델 연결/생성(기본 모델이든 사용자가 선택한 다른 모델이든)은 {@link ChatModelGateway}에
 *   위임한다 - 이 클래스는 그렇게 받아온 모델을 ChatMemory/ContentRetriever와 엮어 RAG 대화를
 *   조립하는 역할만 한다.
 */
@Slf4j
@Component
public class ChatbotFactory {

    private final ContentRetriever selectedRetriever;
    private final ContentAggregator contentAggregator;
    private final RagRetrievalLogRepository ragRetrievalLogRepository;
    private final PersistentChatMemoryStore chatMemoryStore;
    private final ChatModelGateway chatModelGateway;
    private final SynonymQueryNormalizer synonymQueryNormalizer;
    private final DateCalculationTool dateCalculationTool;
    private final boolean rerankingEnabled;

    @Value("${rag.query-compression.enabled:true}")
    private boolean queryCompressionEnabled;

    /**
     * @param hybridContentRetriever 하이브리드 검색 빈. {@code rag.retrieval.hybrid.enabled=true}
     *                               일 때만 등록되며 off(기본) 상태에서는 null 이다.
     * @param denseContentRetriever  dense 벡터 검색 빈. 항상 존재한다.
     * @param contentAggregator      리랭커 기반 재정렬 빈. {@code rag.reranker.enabled=true}일 때만
     *                               등록되며, off(기본) 상태에서는 null이라 기본 동작(재정렬 없이
     *                               검색 순서 그대로 top-k 유지)으로 폴백한다.
     */
    public ChatbotFactory(
            @Qualifier("hybridContentRetriever") @Autowired(required = false) ContentRetriever hybridContentRetriever,
            @Qualifier("contentRetriever") ContentRetriever denseContentRetriever,
            @Autowired(required = false) ContentAggregator contentAggregator,
            PersistentChatMemoryStore chatMemoryStore,
            ChatModelGateway chatModelGateway,
            RagRetrievalLogRepository ragRetrievalLogRepository,
            SynonymQueryNormalizer synonymQueryNormalizer,
            DateCalculationTool dateCalculationTool,
            JdbcTemplate jdbcTemplate,
            @Value("${pgvector.table-name:document_embeddings}") String embeddingTableName) {

        ContentRetriever chosenRetriever = (hybridContentRetriever != null) ? hybridContentRetriever : denseContentRetriever;
        this.selectedRetriever = new EgovKeywordBoostContentRetriever(chosenRetriever, jdbcTemplate, embeddingTableName);
        this.contentAggregator = (contentAggregator != null) ? contentAggregator : new DefaultContentAggregator();
        this.rerankingEnabled = contentAggregator != null;
        this.ragRetrievalLogRepository = ragRetrievalLogRepository;
        this.chatMemoryStore = chatMemoryStore;
        this.chatModelGateway = chatModelGateway;
        this.synonymQueryNormalizer = synonymQueryNormalizer;
        this.dateCalculationTool = dateCalculationTool;

        if (hybridContentRetriever != null) {
            log.info("ChatbotFactory - 하이브리드 ContentRetriever 사용");
        }
        log.info("ChatbotFactory - 리랭커 사용 여부: {}", contentAggregator != null);
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
    public RagChatbot createRagChatbot(String modelName, String sessionId,
                                       Consumer<RagProcessingStage> progressReporter) {
        StreamingChatModel streamingModel = chatModelGateway.getStreamingModel(modelName);
        String resolvedModelName = chatModelGateway.resolveModelName(modelName);

        String turnId = UUID.randomUUID().toString();

        log.info("RAG 챗봇 생성 - 모델: {}, 세션: {}, 턴: {}, 질의 압축: {}", chatModelGateway.resolveModelName(modelName), sessionId, turnId, queryCompressionEnabled);

        AtomicReference<String> originalQueryTextHolder = new AtomicReference<>();
        ContentRetriever loggingRetriever = new EgovLoggingContentRetriever(selectedRetriever, ragRetrievalLogRepository, sessionId, turnId, originalQueryTextHolder, progressReporter);

        QueryTransformer baseTransformer = queryCompressionEnabled ? new CompressingQueryTransformer(chatModelGateway.getChatModel(modelName)) : query -> List.of(query);
        ContentAggregator progressAggregator = queryToContents -> {
            progressReporter.accept(rerankingEnabled ? RagProcessingStage.RERANKING : RagProcessingStage.ORGANIZING_RESULTS);
            List<Content> aggregated = contentAggregator.aggregate(queryToContents);
            progressReporter.accept(RagProcessingStage.GENERATING_ANSWER);
            return aggregated;
        };
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(loggingQueryTransformer(baseTransformer, originalQueryTextHolder, progressReporter))
                .contentRetriever(loggingRetriever)
                .contentAggregator(progressAggregator)
                .build();

        AiServices<RagChatbot> builder = AiServices.builder(RagChatbot.class)
                .streamingChatModel(streamingModel)
                .systemMessageProvider(memoryId -> RagChatbot.RAG_SYSTEM_PROMPT + "\n\n" + currentDateContext())
                .tools(dateCalculationTool)
                .chatMemory(createChatMemory(sessionId, turnId, resolvedModelName))
                .retrievalAugmentor(retrievalAugmentor);

        return builder.build();
    }

    /**
     * {@link QueryTransformer} 데코레이터. 동의어 정규화(SynonymQueryNormalizer) 후 delegate에
     * 넘기고, 압축 전/후 질의를 로그로 남긴다. 원본 질의(정규화 전, 사용자가 실제로 입력한
     * 그대로)는 {@code originalQueryTextHolder}에 채워 넣어 {@link EgovLoggingContentRetriever}가
     * {@code rag_retrieval_logs.original_query_text}에 같이 저장할 수 있게 한다 - 화면 표시나
     * chat_memory 저장에는 영향 없고, 검색에 쓰이는 텍스트에만 정규화가 적용된다.
     */
    private QueryTransformer loggingQueryTransformer(QueryTransformer delegate,
                                                      AtomicReference<String> originalQueryTextHolder,
                                                      Consumer<RagProcessingStage> progressReporter) {
        return query -> {
            progressReporter.accept(RagProcessingStage.TRANSFORMING_QUERY);
            originalQueryTextHolder.set(query.text());

            String normalizedText = synonymQueryNormalizer.normalize(query.text());
            Query normalizedQuery = query.metadata() == null
                    ? Query.from(normalizedText)
                    : Query.from(normalizedText, query.metadata());

            Collection<Query> transformed = delegate.transform(normalizedQuery);
            for (Query t : transformed) {
                log.info("질의 압축 - 원본: [{}] -> 정규화: [{}] -> 압축: [{}]",
                        query.text(), normalizedQuery.text(), t.text());
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
        StreamingChatModel streamingModel = chatModelGateway.getStreamingModel(modelName);
        String resolvedModelName = chatModelGateway.resolveModelName(modelName);

        log.info("Simple 챗봇 생성 - 모델: {}, 세션: {}", resolvedModelName, sessionId);

        return AiServices.builder(SimpleChatbot.class)
                .streamingChatModel(streamingModel)
                .systemMessageProvider(memoryId -> SimpleChatbot.SIMPLE_SYSTEM_PROMPT + "\n\n" + currentDateContext())
                .tools(dateCalculationTool)
                .chatMemory(createChatMemory(sessionId, resolvedModelName))
                .build();
    }

    private static final DateTimeFormatter CURRENT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 EEEE", Locale.KOREAN);

    private static final String[] KOREAN_DAY_ABBREVIATIONS = {"월", "화", "수", "목", "금", "토", "일"};

    static String currentDateContext(LocalDate date) {
        return "오늘은 " + date.format(CURRENT_DATE_FORMATTER) + "입니다. "
                + "요일이나 기간을 계산할 때는 이 날짜를 기준으로 날짜 차이를 정확히 센 다음 "
                + "그 계산 과정을 거쳐서 결론을 답하세요.\n\n"
                + "아래는 이번 달과 다음 달의 날짜별 요일표입니다. 표에 있는 날짜의 요일은 "
                + "직접 계산하지 말고 이 표에서 찾아서 답하세요.\n"
                + monthCalendarText(date)
                + monthCalendarText(date.plusMonths(1));
    }

    private static String monthCalendarText(LocalDate anyDateInMonth) {
        LocalDate monthStart = anyDateInMonth.withDayOfMonth(1);
        int daysInMonth = monthStart.lengthOfMonth();
        StringBuilder sb = new StringBuilder();
        sb.append(monthStart.getYear()).append("년 ").append(monthStart.getMonthValue()).append("월: ");
        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = monthStart.withDayOfMonth(day);
            sb.append(day).append("일(")
                    .append(KOREAN_DAY_ABBREVIATIONS[date.getDayOfWeek().getValue() - 1])
                    .append(") ");
        }
        return sb.append("\n").toString();
    }

    private static String currentDateContext() {
        return currentDateContext(LocalDate.now());
    }

    /**
     * SQL 생성 챗봇 인스턴스 생성
     *
     * @param modelName 사용할 모델명 (null이면 기본 모델)
     * @param sessionId 세션 ID (메모리 관리용)
     * @return SqlGenChatbot 인스턴스
     */
    public SqlGenChatbot createSqlGenChatbot(String modelName, String sessionId) {
        StreamingChatModel streamingModel = chatModelGateway.getStreamingModel(modelName);
        String resolvedModelName = chatModelGateway.resolveModelName(modelName);

        log.info("SQL 생성 챗봇 생성 - 모델: {}, 세션: {}", resolvedModelName, sessionId);

        return AiServices.builder(SqlGenChatbot.class)
                .streamingChatModel(streamingModel)
                .chatMemory(createChatMemory(sessionId, resolvedModelName))
                .build();
    }

    private static final int CHAT_MEMORY_WINDOW_UNLIMITED = 100_000;

    /**
     * MessageWindowChatMemory 생성 (턴 키 없이 - 단순 채팅/SQL 생성용)
     * - chatMemoryStore를 modelName으로 감싸, 이 세션에서 저장되는 ASSISTANT 메시지에
     *   실제 사용한 모델명이 찍히게 한다(화면에 모델 배지로 표시하기 위함).
     */
    private MessageWindowChatMemory createChatMemory(String sessionId, String modelName) {
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(CHAT_MEMORY_WINDOW_UNLIMITED)
                .chatMemoryStore(new TurnTaggingChatMemoryStore(chatMemoryStore, null, modelName))
                .build();
    }

    /**
     * MessageWindowChatMemory 생성 (RAG용)
     * - chatMemoryStore를 turnId/modelName으로 감싸, 이 턴에서 저장되는 메시지에
     *   turnId와(ASSISTANT 메시지는) 모델명이 찍히게 한다.
     */
    private MessageWindowChatMemory createChatMemory(String sessionId, String turnId, String modelName) {
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(CHAT_MEMORY_WINDOW_UNLIMITED)
                .chatMemoryStore(new TurnTaggingChatMemoryStore(chatMemoryStore, turnId, modelName))
                .build();
    }

    /**
     * {@link ChatMemoryStore} 데코레이터. updateMessages()만 가로채 이번 질의(턴)의
     * turnId/modelName을 넘겨주고, 나머지는 delegate에 그대로 위임한다. create*Chatbot() 호출마다
     * 새로 만들어져 turnId/modelName을 클로저로 들고 있으므로, 실제 저장이 어느 스레드에서
     * 일어나든(ThreadLocal과 달리) 안전하게 전달된다.
     */
    @RequiredArgsConstructor
    private static class TurnTaggingChatMemoryStore implements ChatMemoryStore {

        private final PersistentChatMemoryStore delegate;
        private final String turnId;
        private final String modelName;

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return delegate.getMessages(memoryId, modelName);
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            delegate.updateMessages(memoryId, messages, turnId, modelName);
        }

        @Override
        public void deleteMessages(Object memoryId) {
            delegate.deleteMessages(memoryId);
        }
    }
}
