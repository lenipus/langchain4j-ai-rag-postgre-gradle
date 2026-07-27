package com.example.chat.dto;

/** 이미지 첨부 업로드 응답 - imageToken을 스트리밍 요청에 그대로 실어보내면 된다 */
public record ImageAttachmentResponseDto(String imageToken) {
}
