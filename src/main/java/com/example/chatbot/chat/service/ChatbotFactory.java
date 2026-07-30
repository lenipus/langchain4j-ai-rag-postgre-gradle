package com.example.chatbot.chat.service;

import com.example.chatbot.chat.config.EgovLoggingContentRetriever;
import com.example.chatbot.chat.config.SynonymQueryNormalizer;
import com.example.chatbot.chat.repository.PersistentChatMemoryStore;
import com.example.chatbot.chat.repository.RagRetrievalLogRepository;
import com.example.chatbot.sqlgen.service.SqlGenChatbot;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
    private final RagRetrievalLogRepository ragRetrievalLogRepository;
    private final PersistentChatMemoryStore chatMemoryStore;
    private final ChatModelGateway chatModelGateway;
    private final SynonymQueryNormalizer synonymQueryNormalizer;
    private final DateCalculationTool dateCalculationTool;

    @Value("${rag.query-compression.enabled:true}")
    private boolean queryCompressionEnabled;

    /**
     * @param hybridContentRetriever 하이브리드 검색 빈. {@code rag.retrieval.hybrid.enabled=true}
     *                               일 때만 등록되며 off(기본) 상태에서는 null 이다.
     * @param denseContentRetriever  dense 벡터 검색 빈. 항상 존재한다.
     */
    public ChatbotFactory(
            @Qualifier("hybridContentRetriever") @Autowired(required = false) ContentRetriever hybridContentRetriever,
            @Qualifier("contentRetriever") ContentRetriever denseContentRetriever,
            PersistentChatMemoryStore chatMemoryStore,
            ChatModelGateway chatModelGateway,
            RagRetrievalLogRepository ragRetrievalLogRepository,
            SynonymQueryNormalizer synonymQueryNormalizer,
            DateCalculationTool dateCalculationTool) {
        // 하이브리드 빈이 등록된 경우 우선 사용하고, 없으면 기존 dense 경로를 유지한다.
        // 실제 EgovLoggingContentRetriever는 질의(턴)마다 turnId를 새로 발급해 createRagChatbot()에서
        // 매번 새로 감싸 만든다 (아래 selectedRetriever는 그 delegate로만 쓰인다).
        this.selectedRetriever = (hybridContentRetriever != null) ? hybridContentRetriever : denseContentRetriever;
        this.ragRetrievalLogRepository = ragRetrievalLogRepository;
        this.chatMemoryStore = chatMemoryStore;
        this.chatModelGateway = chatModelGateway;
        this.synonymQueryNormalizer = synonymQueryNormalizer;
        this.dateCalculationTool = dateCalculationTool;

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
        StreamingChatModel streamingModel = chatModelGateway.getStreamingModel(modelName);

        // 이 질의(턴) 하나를 위한 키. rag_retrieval_logs와 chat_memory 양쪽에 같은 값이
        // 찍혀서, "이 질문/답변에 실제로 RAG가 뭘 검색해줬는지"를 정확히 조인해 추적할 수 있다.
        String turnId = UUID.randomUUID().toString();

        log.info("RAG 챗봇 생성 - 모델: {}, 세션: {}, 턴: {}, 질의 압축: {}",
                chatModelGateway.resolveModelName(modelName), sessionId, turnId, queryCompressionEnabled);

        // 질의 압축이 켜져 있으면 검색엔 압축된 질의가 쓰이므로, 사용자가 실제로 입력한
        // 원본 질의는 압축 단계(queryTransformer)에서 이 홀더에 채워 넣고
        // EgovLoggingContentRetriever가 감사 로그에 같이 남길 때 읽어간다.
        AtomicReference<String> originalQueryTextHolder = new AtomicReference<>();

        ContentRetriever loggingRetriever = new EgovLoggingContentRetriever(
                selectedRetriever, ragRetrievalLogRepository, sessionId, turnId, originalQueryTextHolder);

        AiServices<RagChatbot> builder = AiServices.builder(RagChatbot.class)
                .streamingChatModel(streamingModel)
                .systemMessageProvider(memoryId -> RagChatbot.RAG_SYSTEM_PROMPT + "\n\n" + currentDateContext())
                .tools(dateCalculationTool)
                .chatMemory(createChatMemory(sessionId, turnId));

        // 동의어 정규화(SynonymQueryNormalizer)는 질의 압축 여부와 무관하게 항상 거쳐야
        // 검색 텍스트에 반영되므로, 압축이 꺼져있어도 QueryTransformer를 그대로 둔다
        // (이전엔 이 분기에서 contentRetriever만 넘겨 QueryTransformer 자체가 없었음).
        QueryTransformer baseTransformer = queryCompressionEnabled
                // 후속 질문(대명사·생략형)이 이전 대화를 반영 못 한 채 그대로 검색되는 문제를
                // 완화하기 위해, 검색 전에 (이전 대화 + 현재 질문)을 독립형 질문으로 압축한다.
                ? new CompressingQueryTransformer(chatModelGateway.getChatModel(modelName))
                : query -> List.of(query);
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(loggingQueryTransformer(baseTransformer, originalQueryTextHolder))
                .contentRetriever(loggingRetriever)
                .build();
        builder.retrievalAugmentor(retrievalAugmentor);

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
                                                      AtomicReference<String> originalQueryTextHolder) {
        return query -> {
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

        log.info("Simple 챗봇 생성 - 모델: {}, 세션: {}", chatModelGateway.resolveModelName(modelName), sessionId);

        return AiServices.builder(SimpleChatbot.class)
                .streamingChatModel(streamingModel)
                .systemMessageProvider(memoryId -> SimpleChatbot.SIMPLE_SYSTEM_PROMPT + "\n\n" + currentDateContext())
                .tools(dateCalculationTool)
                .chatMemory(createChatMemory(sessionId))
                .build();
    }

    private static final DateTimeFormatter CURRENT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 EEEE", Locale.KOREAN);

    private static final String[] KOREAN_DAY_ABBREVIATIONS = {"월", "화", "수", "목", "금", "토", "일"};

    /**
     * LLM은 실행 시점의 실제 날짜를 모르므로("올해"가 몇 년인지, "다음 주"가 언제인지 등을
     * 스스로 계산 못 함), 시스템 메시지에 매 요청마다 오늘 날짜/요일을 넣어준다.
     * {@code @SystemMessage} 정적 어노테이션 대신 systemMessageProvider를 쓰는 이유도
     * 이 값을 요청 시점마다 새로 계산해서 넣기 위해서다(정적 어노테이션은 컴파일 타임
     * 상수만 허용해 매번 다른 날짜를 넣을 수 없음).
     */
    // 테스트에서 날짜를 고정해 검증할 수 있도록 package-private로 연다.
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

        log.info("SQL 생성 챗봇 생성 - 모델: {}, 세션: {}", chatModelGateway.resolveModelName(modelName), sessionId);

        return AiServices.builder(SqlGenChatbot.class)
                .streamingChatModel(streamingModel)
                .chatMemory(createChatMemory(sessionId))
                .build();
    }

    /**
     * MessageWindowChatMemory 자체의 창 크기는 사실상 무제한으로 크게 잡아둔다. 실제
     * chat.memory.max-messages 제한은 PersistentChatMemoryStore.getMessages()가 질문+답변
     * 짝이 안 깨지도록 직접 처리한다 - langchain4j 기본 ensureCapacity()는 인덱스 기준으로
     * 하나씩만 잘라내서, 짝의 중간이 잘리면 새로고침 시 히스토리가 "답변"부터 시작하는
     * 문제가 있었다.
     */
    private static final int CHAT_MEMORY_WINDOW_UNLIMITED = 100_000;

    /**
     * MessageWindowChatMemory 생성 (턴 키 없이 - 단순 채팅용)
     * - PersistentChatMemoryStore를 통해 PostgreSQL에 자동 저장
     */
    private MessageWindowChatMemory createChatMemory(String sessionId) {
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(CHAT_MEMORY_WINDOW_UNLIMITED)
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
                .maxMessages(CHAT_MEMORY_WINDOW_UNLIMITED)
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
}
