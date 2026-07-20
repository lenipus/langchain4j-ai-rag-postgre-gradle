package com.example.chat.repository;

import com.example.chat.entity.DocumentHashEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentHashRepository extends JpaRepository<DocumentHashEntity, String> {

    /** 삭제된 파일 감지(현재 스캔 결과와 대조)를 위해 저장된 문서 ID 전체를 가볍게 조회한다. */
    @Query("select d.docId from DocumentHashEntity d")
    List<String> findAllDocIds();
}
