package com.example.chat.service;

import java.util.concurrent.CompletableFuture;
import org.springframework.web.multipart.MultipartFile;

import com.example.chat.response.DocumentStatusResponse;

import java.util.Map;

/**
 * 문서 처리 서비스 인터페이스
 * 마크다운 및 PDF 문서를 로드하고 벡터 저장소에 저장하는 기능 제공
 */
public interface EgovDocumentService {

    /**
     * 문서를 비동기로 로드하고 벡터 저장소에 저장
     *
     * @return 처리된 문서 수
     */
    CompletableFuture<Integer> loadDocumentsAsync();

    // 처리 상태 확인 메서드
    boolean isProcessing();

    int getProcessedCount();

    int getTotalCount();

    int getChangedCount();

    // 취소 요청 후 아직 완전히 멈추지 않은 상태인지 여부 (병렬 처리 중 이미 제출된 파일들은
    // 취소 요청 이후에도 끝까지 실행되므로, 처리 중 상태가 곧바로 꺼지지 않을 수 있다)
    boolean isCancelRequested();

    // 파일 업로드 및 검증/저장
    Map<String, Object> uploadMarkdownFiles(MultipartFile[] files);

    // 재인덱싱 요청(비동기) 및 결과 메시지 반환
    String reindexDocuments();

    // 인덱스 초기화(해시/임베딩 전체 삭제 후 전체 재인덱싱, 비동기) 요청 및 결과 메시지 반환
    String resetIndex();

    // 진행 중인 문서 처리(재인덱싱/초기화 재인덱싱) 취소 요청 및 결과 메시지 반환
    String cancelProcessing();

    // 상태 응답 객체 반환
    DocumentStatusResponse getStatusResponse();
}
