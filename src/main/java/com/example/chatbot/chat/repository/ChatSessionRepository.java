package com.example.chatbot.chat.repository;

import com.example.chatbot.chat.entity.ChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    /**
     * 모든 세션을 업데이트 시간 내림차순으로 조회
     */
    List<ChatSessionEntity> findAllByOrderByUpdatedAtDesc();
}
