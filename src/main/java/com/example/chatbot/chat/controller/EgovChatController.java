package com.example.chatbot.chat.controller;

import com.example.chatbot.chat.context.SessionContext;
import com.example.chatbot.chat.dto.ChatMessageDto;
import com.example.chatbot.chat.dto.ImageAttachmentRequestDto;
import com.example.chatbot.chat.dto.ImageAttachmentResponseDto;
import com.example.chatbot.chat.dto.StreamTokenDto;
import com.example.chatbot.chat.service.EgovChatService;
import com.example.chatbot.chat.service.EgovChatSessionService;
import com.example.chatbot.chat.service.PendingImageStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
public class EgovChatController {

    private final EgovChatService egovChatService;
    private final EgovChatSessionService egovChatSessionService;
    private final PendingImageStore pendingImageStore;

    // false면 업로드 엔드포인트 자체를 막는다(프런트 UI를 우회해 직접 호출해도 안전).
    @Value("${chat.image-attachment.enabled:true}")
    private boolean imageAttachmentEnabled;

    /**
     * 이미지 첨부 업로드 - base64 이미지는 스트리밍(EventSource, GET) 요청 URL에 실어보내기엔
     * 너무 크므로, 먼저 여기 업로드해 토큰만 받고 스트리밍 요청에는 그 토큰만 실어보낸다.
     */
    @PostMapping("/ai/chat/attach-image")
    public ImageAttachmentResponseDto attachImage(@RequestBody ImageAttachmentRequestDto request) {
        if (!imageAttachmentEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이미지 첨부 기능이 비활성화되어 있습니다.");
        }
        String token = pendingImageStore.store(request.base64Data(), request.mimeType());
        return new ImageAttachmentResponseDto(token);
    }

    /**
     * RAG 기반 스트리밍 응답 생성
     */
    @GetMapping(value = "/ai/rag/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<StreamTokenDto> streamRagResponse(
            @RequestParam(value = "message", defaultValue = "Tell me about this document") String message,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "imageToken", required = false) String imageToken) {
        log.info("RAG 기반 스트리밍 질의 수신: {}, 모델: {}, 세션: {}", message, model, sessionId);

        bootstrapSession(sessionId, message, model);
        Flux<StreamTokenDto> response = egovChatService.streamRagResponse(message, model, imageToken);
        SessionContext.clear();

        return response;
    }

    /**
     * 일반 스트리밍 응답 생성
     */
    @GetMapping(value = "/ai/simple/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<StreamTokenDto> streamSimpleResponse(
            @RequestParam(value = "message", defaultValue = "Tell me about this document") String message,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "imageToken", required = false) String imageToken) {
        log.info("일반 스트리밍 질의 수신: {}, 모델: {}, 세션: {}", message, model, sessionId);

        bootstrapSession(sessionId, message, model);
        Flux<StreamTokenDto> response = egovChatService.streamSimpleResponse(message, model, imageToken)
                .map(StreamTokenDto::new);
        SessionContext.clear();

        return response;
    }

    /**
     * SQL 생성 스트리밍 응답 생성 - 사용자가 선택한 DB 연결/테이블의 스키마를 컨텍스트로 붙여
     * RAG/일반 채팅과 동일한 세션(chat_sessions/chat_memory)을 공유한다. EventSource는 GET만
     * 지원하므로 tableNames는 반복 쿼리 파라미터로 받는다.
     */
    @GetMapping(value = "/ai/sqlgen/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<StreamTokenDto> streamSqlGenResponse(
            @RequestParam(value = "message") String message,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "connectionId") Long connectionId,
            @RequestParam(value = "tableNames") List<String> tableNames) {
        log.info("SQL 생성 스트리밍 질의 수신: {}, 모델: {}, 세션: {}, 연결: {}, 테이블: {}",
                message, model, sessionId, connectionId, tableNames);

        bootstrapSession(sessionId, message, model);

        Flux<StreamTokenDto> response = egovChatService.streamSqlGenResponse(message, model, connectionId, tableNames)
                .map(StreamTokenDto::new);
        SessionContext.clear();
        return response;
    }

    /**
     * 세 스트리밍 엔드포인트가 공통으로 수행하는 세션 컨텍스트 설정.
     * 유효한 세션 ID면 SessionContext에 설정하고(첫 메시지면 제목 생성, 아니면 마지막
     * 메시지 시각 갱신), 없거나 무효하면 기본("default") 세션으로 처리한다. 이번 요청에서
     * 사용한 모델도 세션에 함께 저장해, 다음에 이 세션을 열 때 프론트엔드가 복원할 수 있게 한다.
     */
    private void bootstrapSession(String sessionId, String message, String model) {
        if (sessionId != null && !sessionId.isEmpty()) {
            log.debug("세션 ID 검증 시작: {}", sessionId);
            if (egovChatSessionService.sessionExists(sessionId)) {
                log.debug("유효한 세션 ID 확인: {}", sessionId);
                SessionContext.setCurrentSessionId(sessionId);

                // 첫 메시지인 경우 세션 제목 업데이트
                List<ChatMessageDto> history = egovChatSessionService.getSessionMessages(sessionId);
                if (history.isEmpty()) {
                    log.debug("첫 메시지로 판단, 세션 제목 생성: {}", sessionId);
                    String title = egovChatSessionService.generateSessionTitle(message);
                    egovChatSessionService.updateSessionTitle(sessionId, title);
                } else {
                    log.debug("기존 세션 메시지 발견: {} - {} 개", sessionId, history.size());
                    // 마지막 메시지 시간 업데이트
                    egovChatSessionService.updateLastMessageTime(sessionId);
                }

                egovChatSessionService.updateSessionModel(sessionId, model);
            } else {
                log.warn("존재하지 않는 세션 ID: {}, 기본 세션으로 처리", sessionId);
                // 존재하지 않는 세션 ID인 경우 기본 세션으로 처리
                SessionContext.setCurrentSessionId("default");
            }
        } else {
            log.warn("세션 ID가 제공되지 않음, 기본 세션으로 처리");
            // 세션 ID가 없는 경우 기본 세션으로 처리
            SessionContext.setCurrentSessionId("default");
        }

        log.debug("현재 세션 컨텍스트 설정됨: {}", SessionContext.getCurrentSessionId());
    }
}
