package com.example.chat.repository;

import com.example.chat.entity.RagRetrievalLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RagRetrievalLogRepository extends JpaRepository<RagRetrievalLogEntity, Long> {

    /** 특정 세션에서 검색된 로그를 시간순으로 조회한다(디버깅/감사용). */
    List<RagRetrievalLogEntity> findBySessionIdOrderByRetrievedAtAsc(String sessionId);
}
