package com.example.chat.repository;

import com.example.chat.entity.RagRetrievalLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RagRetrievalLogRepository extends JpaRepository<RagRetrievalLogEntity, Long> {

    /** 특정 세션에서 검색된 로그를 시간순으로 조회한다(디버깅/감사용). */
    List<RagRetrievalLogEntity> findBySessionIdOrderByRetrievedAtAsc(String sessionId);

    /** 특정 질의(턴) 하나에 딸린 검색 결과만 정확히 조회한다. session_id+시각 추정과 달리 오차가 없다. */
    List<RagRetrievalLogEntity> findByTurnIdOrderByRetrievedAtAsc(String turnId);

    /** 세션 삭제 시 그 세션의 감사 로그도 같이 정리하기 위한 삭제. */
    void deleteBySessionId(String sessionId);
}
