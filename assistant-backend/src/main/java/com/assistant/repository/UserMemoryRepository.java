package com.assistant.repository;

import com.assistant.entity.UserMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserMemoryRepository extends JpaRepository<UserMemory, Long> {

    List<UserMemory> findByUserId(String userId);

    Optional<UserMemory> findByUserIdAndMemoryKey(String userId, String memoryKey);

    void deleteByUserId(String userId);

    void deleteByUserIdAndMemoryKey(String userId, String memoryKey);

    // Upsert — insert new row or update value if (user_id + memory_key) already exists
    @Modifying
    @Query(value = """
        INSERT INTO user_memory (user_id, memory_key, memory_value)
        VALUES (:userId, :memoryKey, :memoryValue)
        ON DUPLICATE KEY UPDATE memory_value = :memoryValue, updated_at = NOW()
        """, nativeQuery = true)
    void upsertMemory(
            @Param("userId")      String userId,
            @Param("memoryKey")   String memoryKey,
            @Param("memoryValue") String memoryValue
    );
}