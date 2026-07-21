package com.example.chat.repository;

import com.example.chat.entity.ChatMemoryEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PersistentChatMemoryStore#updateMessages(Object, List)}가 "현재 턴"의 RAG 삽입
 * 텍스트는 그대로 저장하고, "과거 턴"의 것만 잘라내는지 검증한다.
 *
 * <p>MessageWindowChatMemory.add()는 매번 "메모리 전체를 읽고 → 새 메시지를 리스트 끝에
 * 추가 → 전체를 다시 저장"하므로, updateMessages()에 들어오는 리스트의 마지막 원소가
 * 항상 방금 추가된 메시지다. 이 마지막 원소만 스트립에서 제외해야, 검색 결과가 삽입된
 * 현재 턴의 사용자 메시지가 곧이어 LLM에 그대로 전달되어 RAG가 정상 동작하면서도,
 * 턴이 끝난 뒤(다음 add() 호출 시 더 이상 마지막이 아니게 됨)부터는 과거 턴으로 취급돼
 * 잘려나가 세션이 길어져도 컨텍스트 윈도우가 계속 불어나지 않는다.</p>
 */
class PersistentChatMemoryStoreTest {

    private static final String MARKER = "\n\nAnswer using the following information:\n";

    private final ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);
    private final PersistentChatMemoryStore store = new PersistentChatMemoryStore(chatMemoryRepository);

    @Test
    @DisplayName("마지막 메시지(현재 턴)의 RAG 삽입 텍스트는 그대로 저장된다")
    void keepsRagInjectionOnLatestMessage() {
        ChatMessage latestUserMessage = UserMessage.from("본부장 연봉 상한액이 얼마야??" + MARKER + "부 칙 제1조...");
        List<ChatMessage> messages = List.of(latestUserMessage);

        store.updateMessages("session-1", messages);

        ArgumentCaptor<ChatMemoryEntity> captor = ArgumentCaptor.forClass(ChatMemoryEntity.class);
        verify(chatMemoryRepository).save(captor.capture());
        assertThat(captor.getValue().getContent()).contains("Answer using the following information");
    }

    @Test
    @DisplayName("과거 턴(마지막이 아닌) 사용자 메시지의 RAG 삽입 텍스트는 잘려나간다")
    void stripsRagInjectionFromPastTurns() {
        ChatMessage pastUserMessage = UserMessage.from("겸직허가 규정 좀 알려줘" + MARKER + "제5조...");
        ChatMessage pastAiMessage = AiMessage.from("겸직허가는 사전 승인이 필요합니다.");
        ChatMessage latestUserMessage = UserMessage.from("본부장 연봉 상한액이 얼마야??" + MARKER + "부 칙 제1조...");
        List<ChatMessage> messages = List.of(pastUserMessage, pastAiMessage, latestUserMessage);

        store.updateMessages("session-2", messages);

        ArgumentCaptor<ChatMemoryEntity> captor = ArgumentCaptor.forClass(ChatMemoryEntity.class);
        verify(chatMemoryRepository, times(3)).save(captor.capture());
        List<ChatMemoryEntity> saved = captor.getAllValues();

        // 과거 사용자 메시지: 스트립됨
        assertThat(saved.get(0).getContent()).isEqualTo("겸직허가 규정 좀 알려줘");
        // AI 메시지는 애초에 마커 대상이 아니므로 그대로
        assertThat(saved.get(1).getContent()).isEqualTo("겸직허가는 사전 승인이 필요합니다.");
        // 마지막(현재 턴) 사용자 메시지: 그대로 유지
        assertThat(saved.get(2).getContent()).isEqualTo("본부장 연봉 상한액이 얼마야??" + MARKER + "부 칙 제1조...");
    }

    @Test
    @DisplayName("같은 세션에 add()가 두 번(사용자→AI) 호출되는 흐름에서, AI 메시지 추가 후에는 직전 사용자 메시지도 잘린다")
    void secondUpdateStripsThePreviouslyLatestUserMessage() {
        // 1차 add(): 검색 결과가 삽입된 사용자 메시지가 마지막 → 안 잘림 (RAG 정상 동작 보장)
        ChatMessage augmentedUserMessage = UserMessage.from("휴가 결재선이 어떻게 돼??" + MARKER + "결재라인...");
        store.updateMessages("session-3", List.of(augmentedUserMessage));

        ArgumentCaptor<ChatMemoryEntity> firstCaptor = ArgumentCaptor.forClass(ChatMemoryEntity.class);
        verify(chatMemoryRepository, times(1)).save(firstCaptor.capture());
        assertThat(firstCaptor.getValue().getContent()).contains("Answer using the following information");

        // 2차 add(): AI 응답이 추가되며 이제 사용자 메시지는 마지막이 아니게 됨 → 잘림
        ChatMessage aiMessage = AiMessage.from("결재선은 팀장-부서장-본부장 순입니다.");
        store.updateMessages("session-3", List.of(augmentedUserMessage, aiMessage));

        ArgumentCaptor<ChatMemoryEntity> secondCaptor = ArgumentCaptor.forClass(ChatMemoryEntity.class);
        verify(chatMemoryRepository, times(3)).save(secondCaptor.capture());
        List<ChatMemoryEntity> secondSaved = secondCaptor.getAllValues().subList(1, 3);
        assertThat(secondSaved.get(0).getContent()).isEqualTo("휴가 결재선이 어떻게 돼??");
        assertThat(secondSaved.get(1).getContent()).isEqualTo("결재선은 팀장-부서장-본부장 순입니다.");
    }

    @Test
    @DisplayName("turnId를 지정해 호출하면 새로 추가되는 메시지에 그 turnId가 찍힌다")
    void stampsProvidedTurnIdOnNewMessage() {
        ChatMessage userMessage = UserMessage.from("겸직허가 규정 좀 알려줘");

        store.updateMessages("session-4", List.of(userMessage), "turn-A");

        ArgumentCaptor<ChatMemoryEntity> captor = ArgumentCaptor.forClass(ChatMemoryEntity.class);
        verify(chatMemoryRepository).save(captor.capture());
        assertThat(captor.getValue().getTurnId()).isEqualTo("turn-A");
    }

    @Test
    @DisplayName("이미 저장돼 있던 메시지는 새 turnId로 다시 저장돼도 원래 turnId를 그대로 유지한다")
    void preservesExistingTurnIdForAlreadyStoredMessage() {
        ChatMemoryEntity previouslyStoredUserMessage = new ChatMemoryEntity("session-5", "USER", "휴가 결재선이 어떻게 돼??" + MARKER + "결재라인...");
        previouslyStoredUserMessage.setTurnId("turn-A");
        previouslyStoredUserMessage.setCreatedAt(LocalDateTime.now());
        when(chatMemoryRepository.findBySessionIdOrderByCreatedAtAsc("session-5"))
                .thenReturn(List.of(previouslyStoredUserMessage));

        ChatMessage sameUserMessage = UserMessage.from("휴가 결재선이 어떻게 돼??" + MARKER + "결재라인...");
        ChatMessage newAiMessage = AiMessage.from("결재선은 팀장-부서장-본부장 순입니다.");

        // 2차 add(): AI 메시지가 새로 추가되는 이 호출에서도 turnId는 여전히 "turn-A"로 같다
        // (같은 요청 안에서 발급된 하나의 turnId를 ChatbotFactory가 재사용하기 때문).
        store.updateMessages("session-5", List.of(sameUserMessage, newAiMessage), "turn-A");

        ArgumentCaptor<ChatMemoryEntity> captor = ArgumentCaptor.forClass(ChatMemoryEntity.class);
        verify(chatMemoryRepository, times(2)).save(captor.capture());
        List<ChatMemoryEntity> saved = captor.getAllValues();
        assertThat(saved.get(0).getTurnId()).isEqualTo("turn-A"); // 과거 저장분 turnId 유지
        assertThat(saved.get(1).getTurnId()).isEqualTo("turn-A"); // 새로 추가된 AI 메시지도 같은 turnId
    }
}
