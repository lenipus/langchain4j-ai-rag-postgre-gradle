package com.example.chatbot.chat.repository;

import com.example.chatbot.chat.entity.ChatMemoryEntity;
import com.example.chatbot.chat.service.ChatModelGateway;
import com.example.chatbot.sqlgen.service.SqlGenChatbot;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j의 ChatMemoryStore 인터페이스를 구현하여
 * AiServices와 자동 통합
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersistentChatMemoryStore implements ChatMemoryStore {

    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatModelGateway chatModelGateway;

    /**
     * 토큰 수 추정용. 실제로 붙는 모델(Qwen 등 로컬 GGUF)의 정확한 토크나이저가 아니라
     * OpenAI 계열(o200k_base) 근사치다 - 한국어는 이 인코딩 기준으로 실제보다 토큰 수가
     * 더 많이 잡히는 경향이 있어(음절 하나가 여러 토큰으로 쪼개짐), 예산을 넘겨 자르기보다는
     * 안전하게(여유 있게) 자르는 쪽으로 치우친다.
     */
    private static final TokenCountEstimator TOKEN_ESTIMATOR = new OpenAiTokenCountEstimator("gpt-4o");

    @Value("${chat.memory.max-history-turns:10}")
    private int maxHistoryTurns;

    /**
     * 특정 세션의 모든 메시지 조회 (모델을 모를 때 - fallback 토큰 예산 사용)
     *
     * @param memoryId 세션 ID
     * @return ChatMessage 리스트
     */
    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(Object memoryId) {
        return getMessages(memoryId, null);
    }

    /**
     * 특정 세션의 메시지를, 이번 턴에 실제로 쓰는 모델의 컨텍스트 길이에 맞춰 토큰
     * 예산만큼 조회한다.
     *
     * @param memoryId  세션 ID
     * @param modelName 이번 턴에 쓰는 모델명(토큰 예산 계산용, 모르면 null)
     * @return ChatMessage 리스트
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(Object memoryId, String modelName) {
        String sessionId = memoryId.toString();
        // log.debug("채팅 메모리 조회 - 세션: {}", sessionId);

        List<ChatMemoryEntity> entities = chatMemoryRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId);

        List<ChatMessage> messages = new ArrayList<>();
        for (ChatMemoryEntity entity : entities) {
            ChatMessage message = convertToLangChain4jMessage(entity);
            if (message != null) {
                messages.add(message);
            }
        }

        // log.debug("채팅 메모리 조회 완료 - 세션: {}, 메시지 수: {}", sessionId, messages.size());
        int tokenBudget = chatModelGateway.resolveHistoryTokenBudget(modelName);
        return applyPairSafeTokenWindow(messages, tokenBudget);
    }

    private List<ChatMessage> applyPairSafeTokenWindow(List<ChatMessage> messages, int maxTokens) {
        if (messages.isEmpty()) {
            return messages;
        }

        int systemOffset = messages.get(0) instanceof SystemMessage ? 1 : 0;
        List<ChatMessage> conversation = messages.subList(systemOffset, messages.size());

        int tokenSum = 0;
        int includeFrom = conversation.size();
        for (int i = conversation.size() - 1; i >= 0; i--) {
            int msgTokens = TOKEN_ESTIMATOR.estimateTokenCountInMessage(conversation.get(i));
            if (tokenSum + msgTokens > maxTokens) {
                break;
            }
            tokenSum += msgTokens;
            includeFrom = i;
        }

        int turnLimitedStart = Math.max(0, conversation.size() - maxHistoryTurns * 2);
        includeFrom = Math.max(includeFrom, turnLimitedStart);

        while (includeFrom < conversation.size() && conversation.get(includeFrom) instanceof AiMessage) {
            includeFrom++;
        }

        if (includeFrom == 0) {
            return messages;
        }
        List<ChatMessage> result = new ArrayList<>(messages.subList(0, systemOffset));
        result.addAll(conversation.subList(includeFrom, conversation.size()));
        return result;
    }

    /**
     * RAG로 검색된 문서가 사용자 메시지에 삽입될 때 DefaultContentInjector가 붙이는 구분자.
     * 과거(이미 답변까지 끝난) 턴의 사용자 메시지에서만 이 뒤 내용을 잘라내 히스토리
     * 누적으로 컨텍스트 윈도우가 터지는 걸 막는다. 자세한 이유는 updateMessages() 참고.
     */
    private static final String RAG_INJECTION_MARKER = "\n\nAnswer using the following information:\n";

    /** SQL 생성 모드가 스키마 컨텍스트를 붙일 때 쓰는 구분자. 역할은 위 RAG 마커와 동일. */
    private static final String SQLGEN_CONTEXT_MARKER = SqlGenChatbot.SCHEMA_CONTEXT_MARKER;

    /**
     * 메시지 업데이트 (턴 키 없이 - 단순 채팅 등 RAG를 쓰지 않는 흐름용).
     *
     * @param memoryId 세션 ID
     * @param messages 저장할 메시지 리스트 (마지막 원소 = 방금 추가된 메시지)
     */
    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        updateMessages(memoryId, messages, null, null);
    }

    /**
     * 메시지 업데이트
     *
     * @param memoryId 세션 ID
     * @param messages 저장할 메시지 리스트 (마지막 원소 = 방금 추가된 메시지)
     * @param turnId   이번 질의(턴)의 고유 키. null이면 turn_id를 찍지 않는다(RAG 미사용 흐름).
     * @param model    이번 턴에 실제로 사용한 모델명. ASSISTANT 메시지에만 채워진다.
     */
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages, String turnId, String model) {
        String sessionId = memoryId.toString();
        // log.debug("채팅 메모리 업데이트 - 세션: {}, 메시지 수: {}", sessionId, messages.size());

        List<ChatMemoryEntity> existing = chatMemoryRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // LLM에 보낼 컨텍스트만 windowing
        List<ChatMessage> existingAsMessages = new ArrayList<>();
        for (ChatMemoryEntity entity : existing) {
            ChatMessage converted = convertToLangChain4jMessage(entity);
            if (converted != null) {
                existingAsMessages.add(converted);
            }
        }
        int tokenBudget = chatModelGateway.resolveHistoryTokenBudget(model);
        int newStart = applyPairSafeTokenWindow(existingAsMessages, tokenBudget).size();

        boolean isRetryReplace = !existing.isEmpty()
                && "USER".equals(existing.get(existing.size() - 1).getMessageType())
                && newStart == messages.size() - 1
                && messages.get(newStart) instanceof UserMessage;

        if (isRetryReplace) {
            chatMemoryRepository.delete(existing.get(existing.size() - 1));
        } else if (!existing.isEmpty() && newStart < messages.size()
                && "USER".equals(existing.get(existing.size() - 1).getMessageType())) {
            ChatMemoryEntity lastExisting = existing.get(existing.size() - 1);
            String stripped = stripInjectedContext(lastExisting.getContent());
            if (!stripped.equals(lastExisting.getContent())) {
                lastExisting.setContent(stripped);
                chatMemoryRepository.save(lastExisting);
            }
        }

        List<ChatMessage> newMessages = messages.subList(newStart, messages.size());
        int lastIndex = newMessages.size() - 1;
        for (int i = 0; i < newMessages.size(); i++) {
            boolean isLatest = (i == lastIndex);
            ChatMemoryEntity entity = convertToEntity(sessionId, newMessages.get(i), isLatest, turnId, model);
            if (entity != null) {
                chatMemoryRepository.save(entity);
            }
        }

        // log.debug("채팅 메모리 업데이트 완료 - 세션: {}", sessionId);
    }

    /**
     * 특정 세션의 모든 메시지 삭제
     *
     * @param memoryId 세션 ID
     */
    @Override
    @Transactional
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.info("채팅 메모리 삭제 - 세션: {}", sessionId);

        chatMemoryRepository.deleteBySessionId(sessionId);
    }

    /**
     * Entity를 LangChain4j ChatMessage로 변환
     */
    private ChatMessage convertToLangChain4jMessage(ChatMemoryEntity entity) {
        String messageType = entity.getMessageType();
        String content = entity.getContent();

        return switch (messageType) {
            case "USER" -> UserMessage.from(content);
            case "ASSISTANT" -> AiMessage.from(content);
            case "SYSTEM" -> SystemMessage.from(content);
            default -> {
                log.warn("알 수 없는 메시지 타입: {}", messageType);
                yield null;
            }
        };
    }

    /**
     * LangChain4j ChatMessage를 Entity로 변환
     *
     * @param isLatest 이 배치에서 방금 추가된(가장 마지막) 메시지인지 여부. 사용자 메시지이면서
     *                 이게 false일 때만(=과거 턴) RAG 삽입 텍스트를 잘라낸다.
     * @param turnId   이 메시지가 속한 질의(턴)의 고유 키 (없으면 null)
     * @param model    이 메시지가 ASSISTANT일 때 실제로 사용한 모델명 (없으면 null)
     */
    private ChatMemoryEntity convertToEntity(String sessionId, ChatMessage message, boolean isLatest,
                                              String turnId, String model) {
        String messageType;
        String content;
        ImageContent image = null;

        if (message instanceof UserMessage userMessage) {
            messageType = "USER";
            UserMessageParts parts = extractParts(userMessage);
            content = isLatest ? parts.text() : stripInjectedContext(parts.text());
            image = parts.image();
        } else if (message instanceof AiMessage aiMessage) {
            messageType = "ASSISTANT";
            content = aiMessage.text();
            if (content == null || content.isBlank()) {
                log.warn("빈 AI 응답이라 저장하지 않음 - 세션: {}", sessionId);
                return null;
            }
        } else if (message instanceof SystemMessage systemMessage) {
            messageType = "SYSTEM";
            content = systemMessage.text();
        } else {
            log.warn("지원하지 않는 메시지 타입: {}", message.getClass().getSimpleName());
            return null;
        }

        ChatMemoryEntity entity = new ChatMemoryEntity(sessionId, messageType, content);
        entity.setTurnId(turnId);
        if ("ASSISTANT".equals(messageType)) {
            entity.setModel(model);
        }
        if (image != null) {
            entity.setImageBase64(image.image().base64Data());
            entity.setImageMimeType(image.image().mimeType());
        }
        return entity;
    }

    /** 첨부 이미지가 있었음을 텍스트에도 남기는 마커(과거 턴 텍스트 로그·검색용). */
    private static final String IMAGE_ATTACHED_MARKER = "\n[이미지 첨부됨]";

    private record UserMessageParts(String text, ImageContent image) {
    }

    /**
     * UserMessage에서 텍스트와 첨부 이미지를 뽑는다. 텍스트 하나뿐인 일반 메시지는
     * {@link UserMessage#singleText()}가 되지만, 이미지가 같이 첨부된 메시지는 Content가
     * 2개(텍스트+이미지)라 그게 예외를 던진다 - 이미지가 섞여 있어도 텍스트/이미지를 각각
     * 골라낸다. 이미지는 {@code getMessages()}로 다시 불러올 땐 재구성하지 않으므로(과거
     * 턴 컨텍스트가 계속 불어나는 걸 막기 위해) 여기 들어오는 image는 항상 방금 막
     * add()된 현재 턴의 것뿐이다.
     */
    private UserMessageParts extractParts(UserMessage userMessage) {
        if (userMessage.hasSingleText()) {
            return new UserMessageParts(userMessage.singleText(), null);
        }
        StringBuilder text = new StringBuilder();
        ImageContent image = null;
        for (Content content : userMessage.contents()) {
            if (content instanceof TextContent textContent) {
                text.append(textContent.text());
            } else if (content instanceof ImageContent imageContent) {
                image = imageContent;
            }
        }
        if (image != null) {
            text.append(IMAGE_ATTACHED_MARKER);
        }
        return new UserMessageParts(text.toString(), image);
    }

    /** RAG 삽입 마커와 SQL 생성 스키마 컨텍스트 마커 중 먼저 나오는 위치에서 잘라낸다. */
    private String stripInjectedContext(String text) {
        if (text == null) {
            return null;
        }
        int ragIndex = text.indexOf(RAG_INJECTION_MARKER);
        int sqlGenIndex = text.indexOf(SQLGEN_CONTEXT_MARKER);
        int cutIndex = -1;
        if (ragIndex >= 0) {
            cutIndex = ragIndex;
        }
        if (sqlGenIndex >= 0 && (cutIndex < 0 || sqlGenIndex < cutIndex)) {
            cutIndex = sqlGenIndex;
        }
        return cutIndex >= 0 ? text.substring(0, cutIndex) : text;
    }
}
