package com.example.chat.dto;

/** 이미지 첨부 업로드 요청 바디 */
public record ImageAttachmentRequestDto(String base64Data, String mimeType) {
}
