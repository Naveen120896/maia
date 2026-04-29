package com.assistant.repository;

import com.assistant.entity.ChatHistory;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {

    List<ChatHistory> findTop20ByUserIdOrderByCreatedAtDesc(String userId);
    @Transactional
    void deleteByUserId(String userId);
}