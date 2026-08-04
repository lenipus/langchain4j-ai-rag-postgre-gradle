package com.example.chatbot.chat.dto;

/**
 * SSE 스트리밍 이벤트 DTO. 답변 조각은 {@code type=token}, RAG 진행 상태는
 * {@code type=status}로 구분하여 상태 문구가 실제 답변에 섞이지 않게 한다.
 */
public record StreamTokenDto(String type, String token, String stage, String message) {

    /** 일반/SQL 채팅을 포함한 기존 토큰 생성 코드와의 호환용 생성자. */
    public StreamTokenDto(String token) {
        this("token", token, null, null);
    }

    public static StreamTokenDto token(String token) {
        return new StreamTokenDto(token);
    }

    public static StreamTokenDto status(RagProcessingStage stage) {
        return new StreamTokenDto("status", null, stage.code(), stage.message());
    }
}
