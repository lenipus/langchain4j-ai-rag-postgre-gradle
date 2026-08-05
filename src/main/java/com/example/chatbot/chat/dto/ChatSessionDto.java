package com.example.chatbot.chat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionDto {
    private String sessionId;
    private String title;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastMessageAt;

    private String lastModel;

    public ChatSessionDto(String sessionId, String title, LocalDateTime createdAt) {
        this.sessionId = sessionId;
        this.title = title;
        this.createdAt = createdAt;
        this.lastMessageAt = createdAt;
    }
}
