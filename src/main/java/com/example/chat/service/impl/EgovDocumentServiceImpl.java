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
import java.util.concurrent.Executor;
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
    public CompletableFuture<Integer> loadDocumentsAsync() {
        // 검사·설정을 단일 원자 연산으로 처리하여 동시 진입(TOCTOU)을 차단한다.
        if (!isProcessing.compareAndSet(false, true)) {
            log.warn("이미 문서 처리가 진행 중입니다.");
            return CompletableFuture.completedFuture(0);
        }

        log.info("LangChain4j ETL 파이프라인으로 문서 처리 시작");
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

                // 3단계: 문서 형식 정규화 (ContentFormatTransformer)
                log.info("문서 형식 정규화 시작");
                List<Document> normalizedDocuments = egovContentFormatTransformer.transformAll(changedDocuments);
                log.info("문서 형식 정규화 완료: {}개 문서", normalizedDocuments.size());

                // 4단계: 문서 변환 (청크 분할, 메타데이터 추가)
                log.info("문서 변환 시작");
                List<Document> transformedDocuments = egovEnhancedDocumentTransformer.transformAll(normalizedDocuments);
                log.info("문서 변환 완료: {}개 청크 생성", transformedDocuments.size());

                // 진행률 표시 기준을 청크 개수로 맞춤 (처리 단위가 청크이므로)
                totalCount.set(transformedDocuments.size());
                processedCount.set(0);

                // 5단계: 벡터 저장소에 저장 (배치 완료마다 진행 개수 갱신)
                log.info("벡터 저장소 저장 시작");
                egovVectorStoreWriter.write(transformedDocuments, processedCount::set);
                log.info("벡터 저장소 저장 완료");

                // 6단계: 처리된 문서 해시 저장
                for (Document document : changedDocuments) {
                    saveDocumentHash(document);
                }

                processedCount.set(transformedDocuments.size());
                log.info("문서 처리 완료: {}개 문서 처리됨 (원본: {}개 → 청크: {}개)",
                        transformedDocuments.size(), changedDocuments.size(), transformedDocuments.size());

                return transformedDocuments.size();

            } catch (Exception e) {
                log.error("문서 처리 중 오류 발생", e);
                throw new RuntimeException("문서 처리 중 오류 발생", e);
            } finally {
                isProcessing.set(false);
            }
        }, executor);
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
        future.thenAccept(count -> log.info("재인덱싱 완료: {}개 청크 처리됨", count))
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
        future.thenAccept(count -> log.info("인덱스 초기화 후 재인덱싱 완료: {}개 청크 처리됨", count))
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
                this.getChangedCount());
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
            log.debug("문서 '{}' 해시 저장 완료: {}", docId, newHash);
        }
    }
}
