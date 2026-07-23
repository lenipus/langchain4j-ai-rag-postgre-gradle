package com.example.chat.service.impl;

import com.example.chat.context.SessionContext;
import com.example.chat.service.EgovChatService;
import com.example.chat.service.ChatbotFactory;
import com.example.chat.service.RagChatbot;
import com.example.chat.service.SimpleChatbot;
import com.example.chat.service.SqlGenChatbot;
import com.example.sqlgen.service.SqlGenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 세션별 채팅 서비스 구현체
 * - AiServices 기반 스트리밍 구현
 * - ChatMemory를 통한 자동 히스토리 관리
 * - langchain4j-reactor를 통한 네이티브 Flux 지원
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EgovChatServiceImpl extends EgovAbstractServiceImpl implements EgovChatService {

    private final ChatbotFactory chatbotFactory;
    private final SqlGenService sqlGenService;

    /**
     * 세션별 RAG 기반 스트리밍 응답 생성
     * - AiServices + ContentRetriever로 자동 RAG 검색
     * - ChatMemory로 자동 히스토리 관리
     * - langchain4j-reactor가 Flux 변환 자동 처리
     */
    @Override
    public Flux<String> streamRagResponse(String query, String model) {
        String sessionId = SessionContext.getCurrentSessionId();
        long startTime = System.currentTimeMillis();
        log.info("RAG 스트리밍 질의 시작 - 세션: {}, 모델: {}, 쿼리: {}", sessionId, model, query);

        try {
            validateSessionId(sessionId);

            AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
            AtomicLong answerLength = new AtomicLong(0);

            // RAG 챗봇 생성 및 스트리밍 응답 (Flux 직접 반환)
            RagChatbot ragChatbot = chatbotFactory.createRagChatbot(model, sessionId);
            return ragChatbot.streamChat(query)
                    .doOnNext(chunk -> {
                        answerLength.addAndGet(chunk.length());
                        if (firstChunkReceived.compareAndSet(false, true)) {
                            log.info("RAG 답변 수신 시작 - 세션: {}, 소요: {}ms",
                                    sessionId, System.currentTimeMillis() - startTime);
                        }
                    })
                    .doOnComplete(() -> log.info("RAG 스트리밍 완료 - 세션: {}, 총 소요: {}ms, 답변 길이: {}",
                            sessionId, System.currentTimeMillis() - startTime, answerLength.get()))
                    .doOnError(e -> log.error("RAG 스트리밍 오류 - 세션: {}", sessionId, e))
                    .transform(stream -> applyRetryAndErrorHandling(stream, "SQL 생성", sessionId));

        } catch (Exception e) {
            // 질의 압축(CompressingQueryTransformer) 등은 스트림이 만들어지기 전에 동기적으로
            // 실행되므로, 컨텍스트 초과 같은 오류가 여기서 터지면 Flux.error()로 그냥 던져서는
            // 프론트(EventSource)가 원인을 전혀 알 수 없는 연결 끊김으로만 본다. 503 처리와
            // 동일하게 스트림 안에서 친화적 메시지로 전달한다.
            log.error("RAG 스트리밍 응답 생성 중 오류 - 세션: {}", sessionId, e);
            return Flux.just("\n[오류: " + friendlyErrorMessage(e) + "]");
        }
    }

    /**
     * 세션별 일반 스트리밍 응답 생성 (RAG 없음)
     * langchain4j-reactor가 Flux 변환 자동 처리
     */
    @Override
    public Flux<String> streamSimpleResponse(String query, String model) {
        String sessionId = SessionContext.getCurrentSessionId();
        long startTime = System.currentTimeMillis();
        log.info("Simple 스트리밍 질의 시작 - 세션: {}, 모델: {}, 쿼리: {}", sessionId, model, query);

        try {
            validateSessionId(sessionId);

            AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
            AtomicLong answerLength = new AtomicLong(0);

            // Simple 챗봇 생성 및 스트리밍 응답 (Flux 직접 반환)
            SimpleChatbot simpleChatbot = chatbotFactory.createSimpleChatbot(model, sessionId);
            return simpleChatbot.streamChat(query)
                    .doOnNext(chunk -> {
                        answerLength.addAndGet(chunk.length());
                        if (firstChunkReceived.compareAndSet(false, true)) {
                            log.info("Simple 답변 수신 시작 - 세션: {}, 소요: {}ms",
                                    sessionId, System.currentTimeMillis() - startTime);
                        }
                    })
                    .doOnComplete(() -> log.info("Simple 스트리밍 완료 - 세션: {}, 총 소요: {}ms, 답변 길이: {}",
                            sessionId, System.currentTimeMillis() - startTime, answerLength.get()))
                    .doOnError(e -> log.error("Simple 스트리밍 오류 - 세션: {}", sessionId, e))
                    .transform(stream -> applyRetryAndErrorHandling(stream, "SQL 생성", sessionId));

        } catch (Exception e) {
            log.error("Simple 스트리밍 응답 생성 중 오류 - 세션: {}", sessionId, e);
            return Flux.just("\n[오류: " + friendlyErrorMessage(e) + "]");
        }
    }

    /**
     * 세션별 SQL 생성 스트리밍 응답 생성
     * - 사용자가 선택한 테이블의 스키마 텍스트를 사용자 메시지 뒤에 붙여(RAG의 문서 주입과
     *   같은 패턴) SqlGenChatbot에 전달한다.
     * - ChatMemory를 세션과 공유하므로, 이전 턴에서 생성한 SQL을 이어서 수정하는 후속
     *   요청("방금 쿼리에 email 컬럼도 추가해줘")도 이전 대화를 참고해 처리된다.
     */
    @Override
    public Flux<String> streamSqlGenResponse(String query, String model, Long connectionId, List<String> tableNames) {
        String sessionId = SessionContext.getCurrentSessionId();
        long startTime = System.currentTimeMillis();
        log.info("SQL 생성 스트리밍 질의 시작 - 세션: {}, 모델: {}, 연결: {}, 테이블: {}, 쿼리: {}",
                sessionId, model, connectionId, tableNames, query);

        try {
            validateSessionId(sessionId);

            AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
            AtomicLong answerLength = new AtomicLong(0);

            String schemaContext = sqlGenService.buildSchemaContext(connectionId, tableNames);
            String augmentedQuery = query + SqlGenChatbot.SCHEMA_CONTEXT_MARKER + schemaContext;

            SqlGenChatbot sqlGenChatbot = chatbotFactory.createSqlGenChatbot(model, sessionId);
            return sqlGenChatbot.streamChat(augmentedQuery)
                    .doOnNext(chunk -> {
                        answerLength.addAndGet(chunk.length());
                        if (firstChunkReceived.compareAndSet(false, true)) {
                            log.info("SQL 생성 답변 수신 시작 - 세션: {}, 소요: {}ms",
                                    sessionId, System.currentTimeMillis() - startTime);
                        }
                    })
                    .doOnComplete(() -> log.info("SQL 생성 스트리밍 완료 - 세션: {}, 총 소요: {}ms, 답변 길이: {}",
                            sessionId, System.currentTimeMillis() - startTime, answerLength.get()))
                    .doOnError(e -> log.error("SQL 생성 스트리밍 오류 - 세션: {}", sessionId, e))
                    .transform(stream -> applyRetryAndErrorHandling(stream, "SQL 생성", sessionId));

        } catch (Exception e) {
            log.error("SQL 생성 스트리밍 응답 생성 중 오류 - 세션: {}", sessionId, e);
            return Flux.just("\n[오류: " + friendlyErrorMessage(e) + "]");
        }
    }

    /**
     * RAG 응답 생성 (비스트리밍)
     */
    public String generateRagResponse(String query) {
        String sessionId = SessionContext.getCurrentSessionId();
        log.info("RAG 응답 생성 (비스트리밍) - 세션: {}, 쿼리: {}", sessionId, query);

        try {
            RagChatbot ragChatbot = chatbotFactory.createRagChatbot(null, sessionId);
            return ragChatbot.chat(query);

        } catch (Exception e) {
            log.error("RAG 응답 생성 중 오류", e);
            return handleException(e);
        }
    }

    /**
     * 일반 응답 생성 (비스트리밍)
     */
    public String generateSimpleResponse(String query) {
        String sessionId = SessionContext.getCurrentSessionId();
        log.info("Simple 응답 생성 (비스트리밍) - 세션: {}, 쿼리: {}", sessionId, query);

        try {
            SimpleChatbot simpleChatbot = chatbotFactory.createSimpleChatbot(null, sessionId);
            return simpleChatbot.chat(query);

        } catch (Exception e) {
            log.error("Simple 응답 생성 중 오류", e);
            return handleException(e);
        }
    }

    /**
     * 세션 ID 검증
     */
    private void validateSessionId(String sessionId) {
        if ("default".equals(sessionId)) {
            log.warn("세션 ID가 'default'로 설정됨 - 세션 관리에 문제가 있을 수 있습니다");
        }
    }

    /**
     * 예외 처리
     */
    private String handleException(Exception e) {
        return friendlyErrorMessage(e);
    }

    // Ollama 원본 오류 본문이 langchain4j 예외 메시지 안에 JSON-in-JSON으로 한 번 더 감싸여
    // 오므로, 실제 메시지에는 큰따옴표 앞에 이스케이프 백슬래시(\")가 그대로 문자로 남아있다
    // (예: ...\"n_prompt_tokens\":13018...). 그래서 따옴표 앞의 백슬래시는 있어도 없어도
    // 매칭되게 \\* 로 느슨하게 잡는다.
    private static final Pattern PROMPT_TOKENS_PATTERN = Pattern.compile("n_prompt_tokens\\\\*\"?\\s*:\\s*(\\d+)");
    private static final Pattern CTX_TOKENS_PATTERN = Pattern.compile("n_ctx\\\\*\"?\\s*:\\s*(\\d+)");

    /**
     * 예외를 사용자에게 보여줄 친화적인 한국어 메시지로 변환한다. 원인 체인을 훑어 Ollama의
     * "컨텍스트 크기 초과"(exceed_context_size_error) 오류처럼 구체적인 원인을 알 수 있는
     * 경우 실제 토큰 수까지 포함한 메시지를 만들고, 그 외에는 기존 타임아웃/연결 오류
     * 메시지로, 마지막엔 원본 메시지를 그대로 붙인 범용 메시지로 폴백한다.
     *
     * <p>이 메서드가 필요했던 이유: 채팅 기록이 길어져 모델의 컨텍스트 창을 넘기면 Ollama가
     * "model is required"처럼 뭉뚱그린 게 아니라 실제 토큰 수(n_prompt_tokens/n_ctx)를
     * 정확히 알려주는데, 그동안은 이 정보를 그냥 버리고 "네트워크 연결을 확인해 주세요"처럼
     * 아무 단서도 없는 메시지만 보여줬었다.</p>
     */
    // 테스트에서 직접 검증할 수 있도록 package-private로 연다.
    String friendlyErrorMessage(Throwable e) {
        String contextSizeMessage = findContextSizeExceededMessage(e);
        if (contextSizeMessage != null) {
            return contextSizeMessage;
        }

        String errorMessage = e.getMessage();
        if (errorMessage != null && (errorMessage.contains("timeout")
                || errorMessage.contains("timed out")
                || errorMessage.contains("connection")
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.util.concurrent.TimeoutException)) {
            return "죄송합니다. 서버 응답 시간이 초과되었습니다. 잠시 후 다시 시도해주세요.";
        }

        return "죄송합니다. 응답을 생성하는 중에 오류가 발생했습니다: " + errorMessage;
    }

    /**
     * 원인 체인(getCause 연쇄)을 훑어 Ollama의 컨텍스트 크기 초과 오류를 찾는다. 찾으면
     * 오류 본문에 실려오는 n_prompt_tokens/n_ctx 값으로 구체적인 메시지를 만들고, 그 표시가
     * 없으면 null을 반환해 다른 폴백 메시지로 넘어가게 한다.
     */
    private String findContextSizeExceededMessage(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg == null || !msg.contains("exceed_context_size_error")) {
                continue;
            }
            Matcher promptMatcher = PROMPT_TOKENS_PATTERN.matcher(msg);
            Matcher ctxMatcher = CTX_TOKENS_PATTERN.matcher(msg);
            if (promptMatcher.find() && ctxMatcher.find()) {
                return String.format(
                        "대화 내용이 너무 길어 답변을 생성할 수 없습니다 (%s / %s 토큰). "
                                + "새 대화를 시작하거나 더 짧게 질문해주세요.",
                        promptMatcher.group(1), ctxMatcher.group(1));
            }
            return "대화 내용이 너무 길어 답변을 생성할 수 없습니다. 새 대화를 시작하거나 더 짧게 질문해주세요.";
        }
        return null;
    }

    private Flux<String> applyRetryAndErrorHandling(Flux<String> stream, String serviceType, String sessionId) {
        return stream
                .retryWhen(reactor.util.retry.Retry.backoff(3, java.time.Duration.ofSeconds(1))
                        .filter(throwable -> {
                            String msg = throwable.getMessage();
                            boolean is503Error = msg != null && (msg.contains("503") || msg.contains("Service Temporarily Unavailable"));
                            if (is503Error) {
                                log.warn("[{}] LLM 서버 503 감지 - 재시도 중... (세션: {})", serviceType, sessionId);
                            }
                            return is503Error;
                        })
                )
                .onErrorResume(e -> {
                    log.error("[{}] 스트리밍 최종 실패 - 세션: {}", serviceType, sessionId, e);
                    return Flux.just("\n[오류: " + friendlyErrorMessage(e) + "]");
                });
    }
}
