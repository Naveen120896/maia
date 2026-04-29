package com.assistant.repository;

import com.assistant.entity.ChatCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatCacheRepository extends JpaRepository<ChatCache, Long> {

    Optional<ChatCache> findByQuestionHash(String questionHash);

    void deleteByUserId(String userId);
}