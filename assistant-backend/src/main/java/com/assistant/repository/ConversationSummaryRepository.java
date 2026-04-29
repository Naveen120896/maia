package com.assistant.repository;

import com.assistant.entity.ConversationSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationSummaryRepository extends JpaRepository<ConversationSummary, Long> {

    List<ConversationSummary> findByUserId(String userId);
}