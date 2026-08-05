package com.example.chatbot.chat.repository;

import com.example.chatbot.chat.entity.ChatMemoryEntity;
import com.example.chatbot.sqlgen.service.SqlGenChatbot;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
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

    /**
     * 세션당 유지할 최대 메시지 수(대화 기록 + 시스템 메시지). {@code MessageWindowChatMemory}
     * 자체의 창(window) 크기는 {@code ChatbotFactory}에서 사실상 무제한으로 크게 잡아두고,
     * 실제 개수 제한은 여기서 짝(질문+답변) 단위로 직접 처리한다 - langchain4j의 기본
     * ensureCapacity()는 인덱스 기준으로 한 개씩만 잘라내서, 잘리는 지점이 짝의 중간에
     * 걸리면 새로고침 후 히스토리가 "답변"부터 시작하는(그 짝의 질문은 이미 삭제된) 문제가
     * 있었다.
     */
    @Value("${chat.memory.max-messages:20}")
    private int maxMessages = 20;

    /**
     * 특정 세션의 모든 메시지 조회
     *
     * @param memoryId 세션 ID
     * @return ChatMessage 리스트
     */
    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(Object memoryId) {
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
        return applyPairSafeWindow(messages, maxMessages);
    }

    /**
     * 최대 개수를 넘으면 오래된 쪽부터 잘라내되, 잘리는 시작점이 ASSISTANT(답변) 메시지면
     * 그 앞의 USER(질문)까지 한 개 더 포함해서 질문부터 시작하게 한다 - 그래서 실제로는
     * maxMessages보다 하나 더(질문+답변 쌍이 안 깨지도록) 남을 수 있다. 맨 앞 SYSTEM
     * 메시지(있다면)는 이 창 계산과 무관하게 항상 유지한다.
     */
    private List<ChatMessage> applyPairSafeWindow(List<ChatMessage> messages, int maxMessages) {
        if (messages.size() <= maxMessages) {
            return messages;
        }

        int systemOffset = (!messages.isEmpty() && messages.get(0) instanceof SystemMessage) ? 1 : 0;
        List<ChatMessage> conversation = messages.subList(systemOffset, messages.size());

        int conversationLimit = maxMessages - systemOffset;
        if (conversation.size() <= conversationLimit) {
            return messages;
        }

        int cutFrom = conversation.size() - conversationLimit;
        if (cutFrom > 0 && conversation.get(cutFrom) instanceof AiMessage) {
            cutFrom--;
        }

        List<ChatMessage> result = new ArrayList<>(messages.subList(0, systemOffset));
        result.addAll(conversation.subList(cutFrom, conversation.size()));
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
        int existingCount = existing.size();

        // "중지" 직후 재질문, 또는 응답 없이 실패한 시도(예: 503) 후 재시도처럼, 직전에
        // 저장된 마지막 메시지도 USER인데 이번에도 새 USER 메시지가 그 뒤에 add()된
        // 경우 - 그대로 두면 실패한 질문이 재시도 때마다 하나씩 쌓인다. 이때는 추가가
        // 아니라 마지막 기존 USER 행을 이번 메시지로 교체한다.
        if (existingCount > 0 && messages.size() == existingCount + 1
                && "USER".equals(existing.get(existingCount - 1).getMessageType())
                && messages.get(existingCount) instanceof UserMessage) {
            ChatMessage newUserMessage = messages.get(existingCount);
            List<ChatMessage> replaced = new ArrayList<>(messages.subList(0, existingCount - 1));
            replaced.add(newUserMessage);
            messages = replaced;
            existingCount--;
        }

        // 기존 메시지 삭제
        chatMemoryRepository.deleteBySessionId(sessionId);

        // 새 메시지 저장
        int lastIndex = messages.size() - 1;
        for (int i = 0; i < messages.size(); i++) {
            boolean isLatest = (i == lastIndex);
            // 이미 있던 메시지는 예전 turn_id/model을 이어받고, 이번에 새로 생긴 메시지만 이번 값을 찍는다.
            String effectiveTurnId = (i < existingCount) ? existing.get(i).getTurnId() : turnId;
            String effectiveModel = (i < existingCount) ? existing.get(i).getModel() : model;
            ChatMemoryEntity entity = convertToEntity(sessionId, messages.get(i), isLatest, effectiveTurnId, effectiveModel);
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
