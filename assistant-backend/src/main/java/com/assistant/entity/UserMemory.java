package com.assistant.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_memory",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_user_memory_key",
                        columnNames = {"user_id", "memory_key"}
                )
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "memory_key", nullable = false)
    private String memoryKey;

    @Column(columnDefinition = "TEXT")
    private String memoryValue;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();
}