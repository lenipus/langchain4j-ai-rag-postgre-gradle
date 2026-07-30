package com.example.chatbot.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PendingImageStore}가 토큰을 1회성으로 발급/소비하는지 검증한다.
 *
 * <p>base64 이미지는 스트리밍(EventSource, GET) 요청 URL에 실어보내기엔 너무 커서, 먼저
 * 이 저장소에 업로드해 토큰만 받고 실제 스트리밍 요청에는 그 토큰만 실어보낸다.</p>
 */
class PendingImageStoreTest {

    private final PendingImageStore store = new PendingImageStore();

    @Test
    @DisplayName("저장한 이미지를 토큰으로 조회하면 원본 base64/mimeType을 그대로 돌려준다")
    void storesAndRetrievesAttachment() {
        String token = store.store("YWJjMTIz", "image/png");

        Optional<PendingImageStore.Attachment> result = store.takeAndRemove(token);

        assertThat(result).isPresent();
        assertThat(result.get().base64Data()).isEqualTo("YWJjMTIz");
        assertThat(result.get().mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("토큰은 1회성이라 두 번째 조회부터는 빈 값을 반환한다")
    void tokenIsConsumedAfterFirstRetrieval() {
        String token = store.store("YWJjMTIz", "image/png");

        store.takeAndRemove(token);
        Optional<PendingImageStore.Attachment> secondAttempt = store.takeAndRemove(token);

        assertThat(secondAttempt).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 토큰은 빈 값을 반환한다")
    void returnsEmptyForUnknownToken() {
        assertThat(store.takeAndRemove("no-such-token")).isEmpty();
    }

    @Test
    @DisplayName("여러 이미지를 각각 다른 토큰으로 저장해도 서로 섞이지 않는다")
    void storesMultipleImagesIndependently() {
        String tokenA = store.store("aaaa", "image/png");
        String tokenB = store.store("bbbb", "image/jpeg");

        assertThat(store.takeAndRemove(tokenA).get().base64Data()).isEqualTo("aaaa");
        assertThat(store.takeAndRemove(tokenB).get().base64Data()).isEqualTo("bbbb");
    }
}
