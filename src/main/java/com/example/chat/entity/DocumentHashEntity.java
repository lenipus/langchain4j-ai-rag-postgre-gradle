package com.example.chat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_hashes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentHashEntity {

    @Id
    @Column(name = "doc_id", length = 500)
    private String docId;

    @Column(name = "hash", nullable = false, length = 32)
    private String hash;

    // 원본 파일의 마지막 수정 시각(epoch millis). 재인덱싱 시 파싱(PDF 텍스트 추출 등 비용이
    // 큰 작업) 전에 이 값과 현재 파일의 mtime을 비교해, 안 바뀐 파일은 파싱 자체를
    // 건너뛰는 데 쓴다(EgovDocumentScanner). null이면(과거 데이터 등) 비교를 건너뛰고
    // 항상 파싱한다 - 안전한 폴백.
    @Column(name = "source_last_modified")
    private Long sourceLastModified;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public DocumentHashEntity(String docId, String hash) {
        this.docId = docId;
        this.hash = hash;
    }
}
