package com.assistant.repository;

import com.assistant.entity.UserSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSummaryRepository extends JpaRepository<UserSummary, Long> {

    Optional<UserSummary> findByUserId(String userId);

    void deleteByUserId(String userId);
}