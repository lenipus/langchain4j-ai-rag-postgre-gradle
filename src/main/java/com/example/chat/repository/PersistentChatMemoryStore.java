package com.example.chat.repository;

import com.example.chat.entity.ChatMemoryEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        return messages;
    }

    /**
     * RAG로 검색된 문서가 사용자 메시지에 삽입될 때 DefaultContentInjector가 붙이는 구분자.
     * 과거(이미 답변까지 끝난) 턴의 사용자 메시지에서만 이 뒤 내용을 잘라내 히스토리
     * 누적으로 컨텍스트 윈도우가 터지는 걸 막는다. 자세한 이유는 updateMessages() 참고.
     */
    private static final String RAG_INJECTION_MARKER = "\n\nAnswer using the following information:\n";

    /**
     * 메시지 업데이트 (턴 키 없이 - 단순 채팅 등 RAG를 쓰지 않는 흐름용).
     *
     * @param memoryId 세션 ID
     * @param messages 저장할 메시지 리스트 (마지막 원소 = 방금 추가된 메시지)
     */
    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        updateMessages(memoryId, messages, null);
    }

    /**
     * 메시지 업데이트
     *
     * <p>{@link dev.langchain4j.memory.chat.MessageWindowChatMemory#add(ChatMessage)}는 매번
     * "현재 메모리 전체를 읽고 → 새 메시지 하나를 리스트 끝에 추가 → 전체를 다시 저장"하는
     * 방식으로 동작하므로, 여기 들어오는 {@code messages}의 마지막 원소가 항상 "방금 막
     * 추가된 메시지"다. RAG 턴은 다음 순서로 add()가 두 번 불린다:</p>
     * <ol>
     *   <li>검색 결과가 삽입된 사용자 메시지 add → 이 시점엔 그 메시지가 마지막 원소이므로
     *       그대로(스트립 안 함) 저장한다. 이래야 곧이어 이 메시지를 다시 읽어 LLM에 보낼 때
     *       검색된 문서 내용이 실려 있다(안 그러면 RAG 자체가 무력화됨 - 예전에 겪은 문제).</li>
     *   <li>LLM 응답을 받은 뒤 AI 메시지 add → 이제 그 AI 메시지가 마지막이 되고, 직전
     *       사용자 메시지는 더 이상 마지막이 아니므로 이때 비로소 스트립 대상이 된다. 이
     *       턴의 생성은 이미 끝났으니 이 시점부터는 "과거 턴"으로 취급해도 안전하다.</li>
     * </ol>
     * <p>이렇게 마지막 원소만 예외로 두면, 현재 턴의 RAG 컨텍스트는 그대로 유지하면서도
     * 과거 턴들의 검색 결과는 세션이 길어져도 계속 쌓이지 않아 컨텍스트 윈도우 초과
     * (예: "40036 tokens exceeds ... 32768") 문제를 완화한다.</p>
     *
     * <p>{@code turnId}는 이번 요청(질의) 하나를 위해 {@code ChatbotFactory}가 새로 발급한
     * 키다. 이 메서드는 매번 세션의 전체 메시지를 지우고 다시 쓰므로, 이미 저장돼 있던(과거
     * 턴의) 메시지는 삭제 전에 조회해 그 turn_id를 그대로 이어받고, 이번 호출에서 "새로"
     * 생긴 메시지(기존 개수 이후의 원소)에만 이번 turnId를 찍는다. 그래서 1번(사용자 메시지
     * add) 때는 그 메시지 하나만, 2번(AI 메시지 add) 때는 그 사용자 메시지(과거 저장분을
     * 이어받아 여전히 같은 turnId)와 새로 추가된 AI 메시지 둘 다 같은 turnId를 갖게 된다.</p>
     *
     * @param memoryId 세션 ID
     * @param messages 저장할 메시지 리스트 (마지막 원소 = 방금 추가된 메시지)
     * @param turnId   이번 질의(턴)의 고유 키. null이면 turn_id를 찍지 않는다(RAG 미사용 흐름).
     */
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages, String turnId) {
        String sessionId = memoryId.toString();
        // log.debug("채팅 메모리 업데이트 - 세션: {}, 메시지 수: {}", sessionId, messages.size());

        List<ChatMemoryEntity> existing = chatMemoryRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int existingCount = existing.size();

        // 기존 메시지 삭제
        chatMemoryRepository.deleteBySessionId(sessionId);

        // 새 메시지 저장
        int lastIndex = messages.size() - 1;
        for (int i = 0; i < messages.size(); i++) {
            boolean isLatest = (i == lastIndex);
            // 이미 있던 메시지는 예전 turn_id를 이어받고, 이번에 새로 생긴 메시지만 이번 turnId를 찍는다.
            String effectiveTurnId = (i < existingCount) ? existing.get(i).getTurnId() : turnId;
            ChatMemoryEntity entity = convertToEntity(sessionId, messages.get(i), isLatest, effectiveTurnId);
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
     */
    private ChatMemoryEntity convertToEntity(String sessionId, ChatMessage message, boolean isLatest, String turnId) {
        String messageType;
        String content;

        if (message instanceof UserMessage userMessage) {
            messageType = "USER";
            content = isLatest ? userMessage.singleText() : stripRagInjection(userMessage.singleText());
        } else if (message instanceof AiMessage aiMessage) {
            messageType = "ASSISTANT";
            content = aiMessage.text();
        } else if (message instanceof SystemMessage systemMessage) {
            messageType = "SYSTEM";
            content = systemMessage.text();
        } else {
            log.warn("지원하지 않는 메시지 타입: {}", message.getClass().getSimpleName());
            return null;
        }

        ChatMemoryEntity entity = new ChatMemoryEntity(sessionId, messageType, content);
        entity.setTurnId(turnId);
        return entity;
    }

    private String stripRagInjection(String text) {
        if (text == null) {
            return null;
        }
        int index = text.indexOf(RAG_INJECTION_MARKER);
        return index >= 0 ? text.substring(0, index) : text;
    }
}
