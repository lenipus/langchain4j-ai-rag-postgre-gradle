package com.example.chat.service.impl;

import com.example.chat.config.etl.readers.EgovDocumentScanner;
import com.example.chat.config.etl.transformers.EgovContentFormatTransformer;
import com.example.chat.config.etl.transformers.EgovEnhancedDocumentTransformer;
import com.example.chat.config.etl.writers.EgovVectorStoreWriter;
import com.example.chat.entity.DocumentHashEntity;
import com.example.chat.repository.DocumentHashRepository;
import com.example.chat.response.DocumentStatusResponse;
import com.example.chat.service.EgovDocumentService;
import com.example.chat.util.DocumentHashUtil;
import dev.langchain4j.data.document.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EgovDocumentServiceImpl extends EgovAbstractServiceImpl implements EgovDocumentService {

    @Value("${document.upload-dir}")
    private String documentUploadDir;

    // 파일 업로드 용량 제한 (spring.servlet.multipart 설정을 그대로 재사용해 서버 검증과 일치시킨다)
    @Value("${spring.servlet.multipart.max-file-size}")
    private DataSize maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size}")
    private DataSize maxRequestSize;

    // 업로드 허용 확장자. document.allowed-upload-extensions 프로퍼티 하나로 프론트(EgovWebController가
    // 뷰에 전달) 및 백엔드 검증이 동시에 관리된다.
    @Value("${document.allowed-upload-extensions:.md,.pdf,.docx}")
    private String[] allowedUploadExtensions;

    // 재인덱싱 시 파일 단위 동시 처리 개수. 1~MAX_CONCURRENCY 범위를 벗어나면 clamp한다
    private static final int MAX_CONCURRENCY = 4;

    @Value("${document.processing.concurrency:1}")
    private int configuredConcurrency;

    // ETL 파이프라인 컴포넌트들
    private final EgovDocumentScanner egovDocumentScanner;
    private final EgovContentFormatTransformer egovContentFormatTransformer;
    private final EgovEnhancedDocumentTransformer egovEnhancedDocumentTransformer;
    private final EgovVectorStoreWriter egovVectorStoreWriter;

    // Repository
    private final DocumentHashRepository documentHashRepository;

    // Executor
    @Qualifier("documentProcessingExecutor")
    private final Executor executor;

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger changedCount = new AtomicInteger(0);

    @Override
    public boolean isProcessing() {
        return isProcessing.get();
    }

    @Override
    public int getProcessedCount() {
        return processedCount.get();
    }

    @Override
    public int getTotalCount() {
        return totalCount.get();
    }

    @Override
    public int getChangedCount() {
        return changedCount.get();
    }

    @Override
    public boolean isCancelRequested() {
        return cancelRequested.get();
    }

    @Override
    public CompletableFuture<Integer> loadDocumentsAsync() {
        // 검사·설정을 단일 원자 연산으로 처리하여 동시 진입(TOCTOU)을 차단한다.
        if (!isProcessing.compareAndSet(false, true)) {
            log.warn("이미 문서 처리가 진행 중입니다.");
            return CompletableFuture.completedFuture(0);
        }

        log.info("LangChain4j ETL 파이프라인으로 문서 처리 시작");
        cancelRequested.set(false);
        processedCount.set(0);
        totalCount.set(0);
        changedCount.set(0);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1단계: 업로드 디렉터리 전체를 스캔해 확장자별로 알맞은 리더로 문서 읽기
                List<Document> allDocuments = egovDocumentScanner.scanAll();
                totalCount.set(allDocuments.size());

                // 스캔 결과에 더 이상 존재하지 않는(=원본 파일이 삭제된) 문서의 해시/임베딩 정리.
                // 변경된 문서가 하나도 없어도 삭제는 반영해야 하므로 아래 조기 반환보다 먼저 수행한다.
                int deletedCount = cleanupDeletedDocuments(allDocuments);

                // 2단계: 변경된 문서 필터링
                List<Document> changedDocuments = filterChangedDocuments(allDocuments);
                changedCount.set(changedDocuments.size());
                log.info("총 {}개의 문서 중 {}개의 변경된 문서를 처리합니다. (삭제 정리: {}개)",
                        allDocuments.size(), changedDocuments.size(), deletedCount);

                if (changedDocuments.isEmpty()) {
                    log.info("변경된 문서가 없습니다. 인덱싱 작업을 건너뜁니다.");
                    return 0;
                }

                // 3~6단계: 파일 하나를 정규화 → 청크 분할 → 임베딩 저장 → 해시 커밋까지 전부
                // 처리한다. 처리 단위가 "파일 하나"로 독립적이므로, 설정된 동시 처리 개수만큼
                // 스레드 풀로 병렬 실행한다(기본 1 = 기존과 동일한 순차 처리).
                int fileTotal = changedDocuments.size();
                totalCount.set(fileTotal);
                processedCount.set(0);

                int effectiveConcurrency = clampConcurrency(configuredConcurrency);
                log.info("파일 처리 동시 실행 수: {}", effectiveConcurrency);

                AtomicInteger completedCount = new AtomicInteger(0);
                AtomicInteger totalChunksCounter = new AtomicInteger(0);

                // concurrency=1(기본값)은 스레드 풀 오버헤드 없이 호출 스레드에서 그대로 처리한다.
                // 2 이상일 때만 실제 스레드 풀을 쓴다
                boolean cancelled = effectiveConcurrency == 1
                        ? processFilesSequentially(changedDocuments, fileTotal, completedCount, totalChunksCounter)
                        : processFilesConcurrently(changedDocuments, fileTotal, effectiveConcurrency,
                                completedCount, totalChunksCounter);

                int fileIndex = completedCount.get();
                if (cancelled) {
                    log.info("문서 처리 취소됨: 전체 {}개 중 {}개 파일 처리 후 중단", fileTotal, fileIndex);
                } else {
                    log.info("문서 처리 완료: {}개 파일 처리됨 (청크: {}개 생성)", fileIndex, totalChunksCounter.get());
                }

                return fileIndex;

            } catch (Exception e) {
                log.error("문서 처리 중 오류 발생", e);
                throw new RuntimeException("문서 처리 중 오류 발생", e);
            } finally {
                isProcessing.set(false);
                cancelRequested.set(false);
            }
        }, executor);
    }

    @Override
    public String cancelProcessing() {
        if (!isProcessing.get()) {
            return "진행 중인 작업이 없습니다.";
        }
        cancelRequested.set(true);
        log.info("문서 처리 취소 요청 수신");
        return "취소 요청을 접수했습니다. 현재 파일 처리가 끝나는 대로 중단됩니다.";
    }

    @Override
    public Map<String, Object> uploadMarkdownFiles(MultipartFile[] files) {
        // 결과 맵 초기화
        Map<String, Object> result = new HashMap<>();
        if (files == null || files.length == 0) {
            result.put("success", false);
            result.put("message", "업로드할 파일이 없습니다.");
            result.putIfAbsent("files", Collections.emptyList());
            return result;
        }
        if (files.length > 5) {
            result.put("success", false);
            result.put("message", "최대 5개 파일만 업로드할 수 있습니다.");
            result.putIfAbsent("files", Collections.emptyList());
            return result;
        }
        long totalSize = 0;
        int uploaded = 0;
        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) {
                result.put("success", false);
                result.put("message", "파일명이 없습니다.");
                result.putIfAbsent("files", Collections.emptyList());
                return result;
            }
            String filename = Paths.get(originalFilename).getFileName().toString();
            String lowerFilename = filename.toLowerCase();
            boolean allowedExtension = Arrays.stream(allowedUploadExtensions).anyMatch(lowerFilename::endsWith);
            if (!allowedExtension) {
                result.put("success", false);
                result.put("message", String.join(", ", allowedUploadExtensions) + " 파일만 업로드 가능합니다.");
                result.putIfAbsent("files", Collections.emptyList());
                return result;
            }
            if (file.getSize() > maxFileSize.toBytes()) {
                result.put("success", false);
                result.put("message", "파일당 최대 " + maxFileSize.toMegabytes() + "MB까지만 업로드할 수 있습니다.");
                result.putIfAbsent("files", Collections.emptyList());
                return result;
            }
            totalSize += file.getSize();
        }
        if (totalSize > maxRequestSize.toBytes()) {
            result.put("success", false);
            result.put("message", "총 " + maxRequestSize.toMegabytes() + "MB를 초과할 수 없습니다.");
            result.putIfAbsent("files", Collections.emptyList());
            return result;
        }
        // 저장 경로
        File dir = new File(documentUploadDir);
        if (!dir.exists())
            dir.mkdirs();
        for (MultipartFile file : files) {
            String filename = Paths.get(file.getOriginalFilename()).getFileName().toString();
            File dest = new File(dir, filename);
            try {
                // 경로 탐색(Path Traversal) 방어: 저장 경로가 허용된 디렉토리 내인지 검증
                if (!dest.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                    result.put("success", false);
                    result.put("message", "허용되지 않는 파일 경로입니다: " + filename);
                    result.putIfAbsent("files", Collections.emptyList());
                    return result;
                }
                file.transferTo(dest);
                uploaded++;
            } catch (IOException e) {
                result.put("success", false);
                result.put("message", filename + " 저장 실패: " + e.getMessage());
                return result;
            }
        }
        result.put("success", true);
        result.put("uploaded", uploaded);
        return result;
    }

    @Override
    public String reindexDocuments() {
        log.info("문서 재인덱싱 요청 수신");
        CompletableFuture<Integer> future = this.loadDocumentsAsync();

        // 이미 인덱싱 중이면 0을 반환하도록 구현되어 있으므로, 바로 메시지 반환
        if (future.isDone()) {
            try {
                if (future.get() == 0) {
                    return "이미 문서 인덱싱이 진행 중입니다.";
                }
            } catch (Exception e) {
                log.error("상태 확인 중 오류", e);
                return "상태 확인 중 오류가 발생했습니다: " + e.getMessage();
            }
        }

        // 비동기 완료 후 로그 처리
        future.thenAccept(count -> log.info("재인덱싱 완료: {}개 파일 처리됨", count))
                .exceptionally(throwable -> {
                    log.error("재인덱싱 중 오류 발생", throwable);
                    return null;
                });

        log.info("비동기 재인덱싱 요청 성공");
        return "문서 재인덱싱이 처리되었습니다.";
    }

    @Override
    public String resetIndex() {
        log.info("인덱스 초기화 요청 수신");
        if (!isProcessing.compareAndSet(false, true)) {
            log.warn("이미 문서 처리가 진행 중입니다.");
            return "이미 문서 인덱싱이 진행 중입니다.";
        }

        try {
            egovVectorStoreWriter.deleteAll();
            documentHashRepository.deleteAllInBatch();
            log.info("인덱스 초기화 완료 (해시/임베딩 전체 삭제)");
        } catch (Exception e) {
            log.error("인덱스 초기화 중 오류 발생", e);
            isProcessing.set(false);
            return "인덱스 초기화 중 오류가 발생했습니다: " + e.getMessage();
        }
        // loadDocumentsAsync가 스스로 isProcessing을 재획득하므로 여기서 먼저 해제한다.
        isProcessing.set(false);

        CompletableFuture<Integer> future = this.loadDocumentsAsync();
        future.thenAccept(count -> log.info("인덱스 초기화 후 재인덱싱 완료: {}개 파일 처리됨", count))
                .exceptionally(throwable -> {
                    log.error("인덱스 초기화 후 재인덱싱 중 오류 발생", throwable);
                    return null;
                });

        log.info("인덱스 초기화 후 전체 재인덱싱 요청 성공");
        return "인덱스를 초기화하고 전체 재인덱싱을 시작했습니다.";
    }

    @Override
    public DocumentStatusResponse getStatusResponse() {
        return new DocumentStatusResponse(
                this.isProcessing(),
                this.getProcessedCount(),
                this.getTotalCount(),
                this.getChangedCount(),
                this.isCancelRequested());
    }

    /**
     * 변경된 문서만 필터링하는 메서드
     */
    private List<Document> filterChangedDocuments(List<Document> documents) {
        return documents.stream()
                .filter(this::isDocumentChanged)
                .toList();
    }

    /**
     * 문서가 변경되었는지 확인하는 메서드
     */
    private boolean isDocumentChanged(Document document) {
        String docId = document.metadata().getString("id");
        String content = document.text();

        if (content == null || content.trim().isEmpty()) {
            return false;
        }

        // 문서 내용의 해시 계산
        String newHash = DocumentHashUtil.calculateHash(content);

        // DB에서 기존 해시 조회
        Optional<DocumentHashEntity> existing = documentHashRepository.findById(docId);

        if (existing.isPresent() && existing.get().getHash().equals(newHash)) {
            log.debug("문서 '{}' 변경 없음 (해시: {})", docId, newHash);
            return false;
        }

        // 해시가 다르거나 없으면 변경됨으로 판단
        log.debug("문서 '{}' 변경 감지 (이전 해시: {}, 새 해시: {})",
                docId, existing.map(DocumentHashEntity::getHash).orElse("없음"), newHash);
        return true;
    }

    /**
     * 원본 파일이 삭제되어 더 이상 스캔되지 않는 문서의 해시/임베딩을 정리하는 메서드.
     * document_hashes에 남아있는 doc_id 중 이번 스캔 결과({@code currentDocuments})에
     * 없는 것을 "삭제된 파일"로 판단한다.
     */
    private int cleanupDeletedDocuments(List<Document> currentDocuments) {
        Set<String> currentIds = currentDocuments.stream()
                .map(document -> document.metadata().getString("id"))
                .collect(Collectors.toSet());

        List<String> deletedIds = documentHashRepository.findAllDocIds().stream()
                .filter(docId -> !currentIds.contains(docId))
                .toList();

        if (deletedIds.isEmpty()) {
            return 0;
        }

        log.info("삭제된 파일 {}개 감지 - 해시/임베딩 정리 시작: {}", deletedIds.size(), deletedIds);
        egovVectorStoreWriter.deleteByDocIds(deletedIds);
        documentHashRepository.deleteAllByIdInBatch(deletedIds);
        log.info("삭제된 파일 {}개에 대한 해시/임베딩 정리 완료", deletedIds.size());
        return deletedIds.size();
    }

    /**
     * 문서 처리 완료 후 해시값을 저장하는 메서드
     */
    private void saveDocumentHash(Document document) {
        String docId = document.metadata().getString("id");
        String content = document.text();

        if (content != null && !content.trim().isEmpty()) {
            String newHash = DocumentHashUtil.calculateHash(content);
            DocumentHashEntity entity = new DocumentHashEntity(docId, newHash);
            documentHashRepository.save(entity);
        }
    }

    /**
     * 설정된 동시 처리 개수를 1~{@value #MAX_CONCURRENCY} 범위로 보정한다.
     * 범위를 벗어나면 경고 로그를 남기고 가까운 경계값으로 조정한다.
     */
    private int clampConcurrency(int configured) {
        if (configured < 1) {
            log.warn("document.processing.concurrency 값({})이 1보다 작아 1로 보정합니다.", configured);
            return 1;
        }
        if (configured > MAX_CONCURRENCY) {
            log.warn("document.processing.concurrency 값({})이 최대값({})을 초과해 보정합니다.",
                    configured, MAX_CONCURRENCY);
            return MAX_CONCURRENCY;
        }
        return configured;
    }

    /**
     * concurrency=1 전용 순차 처리. 스레드 풀 없이 호출 스레드에서 파일 경계마다 취소
     * 여부를 확인하므로, 취소 요청은 다음 파일로 넘어가기 전에 정확히 반영된다.
     *
     * @return 취소로 인해 중단됐으면 true
     */
    private boolean processFilesSequentially(List<Document> changedDocuments, int fileTotal,
            AtomicInteger completedCount, AtomicInteger totalChunksCounter) {
        for (Document originalDocument : changedDocuments) {
            if (cancelRequested.get()) {
                log.info("취소 요청으로 재인덱싱을 중단합니다 ({}/{} 완료)", completedCount.get(), fileTotal);
                return true;
            }
            try {
                processOneFile(originalDocument, fileTotal, completedCount, totalChunksCounter);
            } catch (Exception e) {
                log.error("파일 처리 중 오류 발생", e);
            }
        }
        return false;
    }

    /**
     * concurrency&gt;1일 때 파일 단위 스레드 풀 병렬 처리. 이미 스레드 풀에 제출된 파일은
     * 취소 요청 이후에도 끝까지 실행된다(진행 중인 임베딩 호출을 안전하게 중간에 끊을
     * 방법이 없으므로, 제출 전 파일만 막는 best-effort 취소).
     *
     * @return 취소로 인해 남은 파일을 제출하지 않고 중단했으면 true
     */
    private boolean processFilesConcurrently(List<Document> changedDocuments, int fileTotal, int concurrency,
            AtomicInteger completedCount, AtomicInteger totalChunksCounter) {
        boolean cancelled = false;
        ExecutorService fileExecutor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Document originalDocument : changedDocuments) {
                if (cancelRequested.get()) {
                    cancelled = true;
                    log.info("취소 요청으로 남은 파일은 제출하지 않습니다 (제출된 파일 {}개는 계속 진행)",
                            futures.size());
                    break;
                }
                futures.add(fileExecutor.submit(() ->
                        processOneFile(originalDocument, fileTotal, completedCount, totalChunksCounter)));
            }

            // 개별 파일 실패가 전체 배치를 중단시키지 않도록 예외는 로그만 남기고 다음 파일
            // 결과를 계속 기다린다. 실패한 파일은 해시가 저장되지 않으므로 다음 재인덱싱 때
            // "변경됨"으로 다시 잡혀 재시도된다.
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    log.error("파일 처리 중 오류 발생", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            fileExecutor.shutdown();
        }
        return cancelled;
    }

    /**
     * 파일 하나를 정규화 → 청크 분할 → 임베딩 저장 → 해시 커밋까지 끝까지 처리한다.
     * 스레드 풀에서 동시에 여러 파일에 대해 호출될 수 있으므로, 공유 카운터는 모두
     * {@link AtomicInteger}로 받는다.
     */
    private void processOneFile(Document originalDocument, int fileTotal,
            AtomicInteger completedCount, AtomicInteger totalChunksCounter) {
        // 병렬 처리 시 제출은 됐지만 아직 실행되지 않은 파일은, 그 사이 취소 요청이 들어왔다면
        // 여기서 건너뛴다(제출 전 확인만으로는 막지 못하는 "제출 후 실행 전" 구간 보완).
        if (cancelRequested.get()) {
            return;
        }

        String fileName = originalDocument.metadata().getString("file_name");

        // 정규화(1:1)는 transform()으로, 청크 분할(1:N)은 transformAll()로 처리한다.
        // EgovEnhancedDocumentTransformer.transform()은 인터페이스 호환용 어댑터일 뿐
        // 청크 여러 개 중 첫 번째만 반환하므로(반환 타입이 Document 하나) 여기서 쓰면 안 된다.
        Document normalizedDocument = egovContentFormatTransformer.transform(originalDocument);
        List<Document> docChunks = egovEnhancedDocumentTransformer.transformAll(List.of(normalizedDocument));

        if (!docChunks.isEmpty()) {
            egovVectorStoreWriter.write(docChunks);
            totalChunksCounter.addAndGet(docChunks.size());
        }
        // 청크가 하나도 없어도(정규화 후 내용이 비어버린 경우 등) 해시는 등록해 다음
        // 실행에서 같은 문서를 계속 "변경됨"으로 재시도하지 않게 한다.
        saveDocumentHash(originalDocument);

        int completed = completedCount.incrementAndGet();
        processedCount.set(completed);
        log.info("[{}/{}] {} - 해시 저장 완료 ({}개 청크)", completed, fileTotal, fileName, docChunks.size());
    }
}
