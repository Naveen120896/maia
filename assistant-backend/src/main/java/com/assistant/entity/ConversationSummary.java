package com.assistant.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "conversation_summary")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;

    @Column(columnDefinition = "TEXT")
    private String summary;

    private LocalDateTime createdAt = LocalDateTime.now();
}