package com.example.chatbot.chat.service.impl;

import com.example.chatbot.chat.context.SessionContext;
import com.example.chatbot.chat.dto.RagProcessingStage;
import com.example.chatbot.chat.dto.StreamTokenDto;
import com.example.chatbot.chat.service.EgovChatService;
import com.example.chatbot.chat.service.ChatbotFactory;
import com.example.chatbot.chat.service.PendingImageStore;
import com.example.chatbot.chat.service.RagChatbot;
import com.example.chatbot.chat.service.SimpleChatbot;
import com.example.chatbot.sqlgen.service.SqlGenChatbot;
import com.example.chatbot.sqlgen.service.SqlGenService;
import dev.langchain4j.data.message.ImageContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
    private final PendingImageStore pendingImageStore;
    private final Optional<SqlGenService> sqlGenService;

    // false면 프런트가 imageToken을 보내도 무시한다(프런트 UI를 우회해 직접 호출해도 안전).
    @Value("${chat.image-attachment.enabled:true}")
    private boolean imageAttachmentEnabled;

    private static final int MEMORY_CONFLICT_MAX_RETRIES = 2;
    private static final long MEMORY_CONFLICT_RETRY_DELAY_MS = 300;
    private static final Pattern PROMPT_TOKENS_PATTERN = Pattern.compile("n_prompt_tokens\\\\*\"?\\s*:\\s*(\\d+)");
    private static final Pattern CTX_TOKENS_PATTERN = Pattern.compile("n_ctx\\\\*\"?\\s*:\\s*(\\d+)");

    /**
     * 세션별 RAG 기반 스트리밍 응답 생성
     * - AiServices + ContentRetriever로 자동 RAG 검색
     * - ChatMemory로 자동 히스토리 관리
     * - langchain4j-reactor가 Flux 변환 자동 처리
     */
    @Override
    public Flux<StreamTokenDto> streamRagResponse(String query, String model, String imageToken) {
        String sessionId = SessionContext.getCurrentSessionId();
        log.info("RAG 스트리밍 질의 시작 - 세션: {}, 모델: {}, 쿼리: {}", sessionId, model, query);

        return Flux.<StreamTokenDto>create(sink -> {
            long startTime = System.currentTimeMillis();

            AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
            AtomicLong answerLength = new AtomicLong(0);
            AtomicReference<RagProcessingStage> currentStage = new AtomicReference<>();

            Consumer<RagProcessingStage> progressReporter = stage -> {
                RagProcessingStage previous = currentStage.getAndSet(stage);
                if (previous != stage && !sink.isCancelled()) {
                    log.debug("RAG 처리 단계 변경 - 세션: {}, 단계: {}", sessionId, stage.code());
                    sink.next(StreamTokenDto.status(stage));
                }
            };

            try {
                progressReporter.accept(RagProcessingStage.PREPARING);
                validateSessionId(sessionId);
                ImageContent image = resolveImageContent(imageToken);

                Flux<String> answerStream = withMemoryConflictRetry(() -> {
                    RagChatbot ragChatbot = chatbotFactory.createRagChatbot(model, sessionId, progressReporter);
                    Flux<String> stream = image != null ? ragChatbot.streamChat(query, image) : ragChatbot.streamChat(query);
                    return stream
                            .doOnNext(chunk -> answerLength.addAndGet(chunk.length()))
                            .doOnComplete(() -> log.info("RAG 스트리밍 완료 - 세션: {}, 총 소요: {}ms, 답변 길이: {}", sessionId, System.currentTimeMillis() - startTime, answerLength.get()))
                            .doOnError(e -> log.error("RAG 스트리밍 오류 - 세션: {}", sessionId, e))
                            .transform(s -> applyRetryAndErrorHandling(s, "RAG", sessionId));
                }, sessionId);

                var subscription = answerStream.subscribe(
                        chunk -> {
                            if (firstChunkReceived.compareAndSet(false, true) && !chunk.startsWith("\n[오류: ")) {
                                progressReporter.accept(RagProcessingStage.RECEIVING_ANSWER);
                                log.info("RAG 답변 수신 시작 - 세션: {}, 소요: {}ms", sessionId, System.currentTimeMillis() - startTime);
                            }
                            sink.next(StreamTokenDto.token(chunk));
                        },
                        error -> {
                            log.error("RAG 스트리밍 이벤트 전달 오류 - 세션: {}", sessionId, error);
                            sink.next(StreamTokenDto.token("\n[오류: " + friendlyErrorMessage(error) + "]"));
                            sink.complete();
                        },
                        sink::complete);
                sink.onCancel(subscription::dispose);
            } catch (Exception e) {
                // 질의 압축 등 스트림 생성 전 동기 오류도 정상 SSE 본문으로 전달한다.
                log.error("RAG 스트리밍 응답 생성 중 오류 - 세션: {}", sessionId, e);
                sink.next(StreamTokenDto.token("\n[오류: " + friendlyErrorMessage(e) + "]"));
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic())
                .publishOn(Schedulers.parallel());
    }

    /**
     * 세션별 일반 스트리밍 응답 생성 (RAG 없음)
     * langchain4j-reactor가 Flux 변환 자동 처리
     */
    @Override
    public Flux<String> streamSimpleResponse(String query, String model, String imageToken) {
        String sessionId = SessionContext.getCurrentSessionId();
        long startTime = System.currentTimeMillis();
        log.info("Simple 스트리밍 질의 시작 - 세션: {}, 모델: {}, 쿼리: {}", sessionId, model, query);

        try {
            validateSessionId(sessionId);

            ImageContent image = resolveImageContent(imageToken);
            AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
            AtomicLong answerLength = new AtomicLong(0);

            // Simple 챗봇 생성 및 스트리밍 응답 (Flux 직접 반환)
            return withMemoryConflictRetry(() -> {
                SimpleChatbot simpleChatbot = chatbotFactory.createSimpleChatbot(model, sessionId);
                Flux<String> stream = image != null
                        ? simpleChatbot.streamChat(query, image)
                        : simpleChatbot.streamChat(query);
                return stream
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
                        .transform(s -> applyRetryAndErrorHandling(s, "Simple", sessionId));
            }, sessionId);

        } catch (Exception e) {
            log.error("Simple 스트리밍 응답 생성 중 오류 - 세션: {}", sessionId, e);
            return Flux.just("\n[오류: " + friendlyErrorMessage(e) + "]");
        }
    }

    /**
     * 세션별 SQL 생성 스트리밍 응답 생성
     */
    @Override
    public Flux<String> streamSqlGenResponse(String query, String model, Long connectionId, List<String> tableNames) {
        String sessionId = SessionContext.getCurrentSessionId();
        long startTime = System.currentTimeMillis();
        log.info("SQL 생성 스트리밍 질의 시작 - 세션: {}, 모델: {}, 연결: {}, 테이블: {}, 쿼리: {}",
                sessionId, model, connectionId, tableNames, query);

        try {
            if (sqlGenService.isEmpty()) {
                log.warn("SQL 생성 요청을 받았지만 sqlgen.enabled=false로 비활성화되어 있음 - 세션: {}", sessionId);
                return Flux.just("\n[오류: SQL 생성 기능이 비활성화되어 있습니다. 관리자에게 문의해주세요.]");
            }

            validateSessionId(sessionId);

            AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
            AtomicLong answerLength = new AtomicLong(0);

            String schemaContext = sqlGenService.get().buildSchemaContext(connectionId, tableNames);
            String augmentedQuery = query + SqlGenChatbot.SCHEMA_CONTEXT_MARKER + schemaContext;

            return withMemoryConflictRetry(() -> {
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
                        .transform(s -> applyRetryAndErrorHandling(s, "SQL 생성", sessionId));
            }, sessionId);

        } catch (Exception e) {
            log.error("SQL 생성 스트리밍 응답 생성 중 오류 - 세션: {}", sessionId, e);
            return Flux.just("\n[오류: " + friendlyErrorMessage(e) + "]");
        }
    }

    /** imageToken을 1회성으로 소비해 ImageContent로 변환한다. 없거나 만료됐으면 null. */
    // 테스트에서 직접 검증할 수 있도록 package-private로 연다.
    private ImageContent resolveImageContent(String imageToken) {
        if (!imageAttachmentEnabled || imageToken == null || imageToken.isBlank()) {
            return null;
        }
        return pendingImageStore.takeAndRemove(imageToken)
                .map(attachment -> new ImageContent(attachment.base64Data(), attachment.mimeType()))
                .orElse(null);
    }

    /**
     * 세션 ID 검증
     */
    private void validateSessionId(String sessionId) {
        if ("default".equals(sessionId)) {
            log.warn("세션 ID가 'default'로 설정됨 - 세션 관리에 문제가 있을 수 있습니다");
        }
    }

    private Flux<String> withMemoryConflictRetry(Supplier<Flux<String>> streamSupplier, String sessionId) {
        int attempt = 0;
        while (true) {
            try {
                return streamSupplier.get();
            } catch (ObjectOptimisticLockingFailureException e) {
                attempt++;
                if (attempt > MEMORY_CONFLICT_MAX_RETRIES) {
                    throw e;
                }
                log.warn("채팅 메모리 동시 갱신 충돌 감지 - {}번째 재시도, 세션: {}", attempt, sessionId);
                try {
                    Thread.sleep(MEMORY_CONFLICT_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private String friendlyErrorMessage(Throwable e) {
        if (e instanceof ObjectOptimisticLockingFailureException) {
            return "죄송합니다. 방금 응답을 중지한 직후라 이전 요청 정리와 충돌했습니다. 잠시 후 다시 시도해주세요.";
        }

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
                .onErrorResume(e -> {
                    log.error("[{}] 스트리밍 실패 - 세션: {}", serviceType, sessionId, e);
                    return Flux.just("\n[오류: " + friendlyErrorMessage(e) + "]");
                });
    }
}
