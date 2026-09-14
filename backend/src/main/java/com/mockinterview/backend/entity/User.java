package com.mockinterview.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @JsonIgnore
    @Column(nullable = false)
    private String passwordHash;

    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    /** True for a no-signup trial account created via POST /api/auth/guest — everything else about
     *  a guest is a normal authenticated User, so ownership checks and JWT auth don't need to
     *  special-case it. Only InterviewSessionService.startSession treats it differently, to cap
     *  guests at one attempt. */
    @Column(nullable = false)
    private boolean guest = false;

    /** Client-generated id (browser localStorage) used to recognize the same guest across logins;
     *  unique and null for regular accounts. */
    @Column(unique = true)
    private String guestId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastActiveAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.lastActiveAt = LocalDateTime.now();
    }
}
