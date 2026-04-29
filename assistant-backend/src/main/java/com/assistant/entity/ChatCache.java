package com.assistant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class ChatCache {

    @Id
    @GeneratedValue
    private Long id;

    private String userId;

    @Column(unique = true)
    private String questionHash;

    @Column(length = 5000)
    private String response;
}