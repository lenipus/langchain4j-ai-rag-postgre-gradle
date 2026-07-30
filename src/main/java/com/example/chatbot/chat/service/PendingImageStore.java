package com.example.chatbot.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 채팅에 첨부할 이미지를 짧게 보관하는 저장소.
 *
 * <p>base64 이미지는 URL 쿼리 파라미터(EventSource 스트리밍 요청)로 실어보내기엔 너무 크므로,
 * 먼저 이 저장소에 업로드해 토큰만 발급받고({@code POST /api/chat/attach-image}), 실제
 * 스트리밍 요청에는 그 토큰만 실어보낸다. 토큰은 1회성이라 조회 즉시 제거되고, 끝까지
 * 안 쓰인 채 남은 것들은 {@link #store}를 호출할 때마다 함께 청소된다(별도 스케줄러 없음).
 */
@Slf4j
@Component
public class PendingImageStore {

    private static final Duration TTL = Duration.ofMinutes(10);

    public record Attachment(String base64Data, String mimeType) {
    }

    private record Entry(String base64Data, String mimeType, Instant storedAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public String store(String base64Data, String mimeType) {
        sweepExpired();
        String token = UUID.randomUUID().toString();
        entries.put(token, new Entry(base64Data, mimeType, Instant.now()));
        return token;
    }

    /** 토큰을 1회성으로 소비한다 - 조회 즉시 제거해 같은 이미지가 다시 쓰이지 않게 한다. */
    public Optional<Attachment> takeAndRemove(String token) {
        Entry entry = entries.remove(token);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new Attachment(entry.base64Data(), entry.mimeType()));
    }

    private void sweepExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        entries.entrySet().removeIf(e -> e.getValue().storedAt().isBefore(cutoff));
    }
}
