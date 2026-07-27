package com.example.chat.service.impl;

import com.example.chat.service.ChatbotFactory;
import com.example.chat.service.PendingImageStore;
import com.example.chat.service.RagChatbot;
import dev.langchain4j.data.message.ImageContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EgovChatServiceImpl#resolveImageContent(String)}와, 이미지 첨부 여부에 따라
 * {@link RagChatbot}의 텍스트 전용/이미지 포함 오버로드 중 어느 쪽이 호출되는지 검증한다.
 */
class EgovChatServiceImplImageAttachmentTest {

    private final ChatbotFactory chatbotFactory = mock(ChatbotFactory.class);
    private final PendingImageStore pendingImageStore = mock(PendingImageStore.class);
    private final EgovChatServiceImpl service =
            new EgovChatServiceImpl(chatbotFactory, pendingImageStore, java.util.Optional.empty());

    // chat.image-attachment.enabled(@Value)는 스프링 컨테이너 없이 new로 생성하면 기본값
    // (false)으로 남으므로, application.yml 기본값(true)에 해당하는 상태로 맞춰준다.
    @BeforeEach
    void enableImageAttachment() {
        ReflectionTestUtils.setField(service, "imageAttachmentEnabled", true);
    }

    @Test
    @DisplayName("유효한 토큰이면 저장소에서 꺼내 ImageContent로 변환한다")
    void resolvesValidToken() {
        when(pendingImageStore.takeAndRemove("token-1"))
                .thenReturn(Optional.of(new PendingImageStore.Attachment("YWJj", "image/png")));

        ImageContent image = service.resolveImageContent("token-1");

        assertThat(image).isNotNull();
        assertThat(image.image().base64Data()).isEqualTo("YWJj");
        assertThat(image.image().mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("토큰이 없거나(null/빈 값) 저장소에 없으면 null을 반환한다")
    void returnsNullWhenTokenMissingOrUnknown() {
        assertThat(service.resolveImageContent(null)).isNull();
        assertThat(service.resolveImageContent("")).isNull();

        when(pendingImageStore.takeAndRemove("expired")).thenReturn(Optional.empty());
        assertThat(service.resolveImageContent("expired")).isNull();
    }

    @Test
    @DisplayName("이미지 토큰이 있으면 이미지 포함 오버로드를, 없으면 텍스트 전용 오버로드를 호출한다")
    void callsImageOverloadOnlyWhenImagePresent() {
        RagChatbot ragChatbot = mock(RagChatbot.class);
        when(chatbotFactory.createRagChatbot(any(), anyString())).thenReturn(ragChatbot);
        when(ragChatbot.streamChat(anyString())).thenReturn(Flux.just("텍스트만"));
        when(ragChatbot.streamChat(anyString(), any(ImageContent.class))).thenReturn(Flux.just("이미지 포함"));
        when(pendingImageStore.takeAndRemove("token-2"))
                .thenReturn(Optional.of(new PendingImageStore.Attachment("YWJj", "image/png")));

        com.example.chat.context.SessionContext.setCurrentSessionId("session-x");
        try {
            service.streamRagResponse("질문", null, "token-2").blockLast();
            verify(ragChatbot, times(1)).streamChat(anyString(), any(ImageContent.class));
            verify(ragChatbot, never()).streamChat(anyString());

            service.streamRagResponse("질문2", null, null).blockLast();
            verify(ragChatbot, times(1)).streamChat("질문2");
        } finally {
            com.example.chat.context.SessionContext.clear();
        }
    }

    @Test
    @DisplayName("기능이 꺼져있으면(chat.image-attachment.enabled=false) 유효한 토큰이 와도 무시한다")
    void ignoresTokenWhenFeatureDisabled() {
        ReflectionTestUtils.setField(service, "imageAttachmentEnabled", false);

        assertThat(service.resolveImageContent("token-3")).isNull();
        // 꺼져있으면 저장소 조회 자체를 안 해야 한다(토큰이 실수로 소비되지 않도록).
        verify(pendingImageStore, never()).takeAndRemove(anyString());
    }
}
