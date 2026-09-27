package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Role;
import com.mockinterview.backend.entity.Tier;
import com.mockinterview.backend.entity.Topic;

import java.time.LocalDateTime;

/** Row shape for GET /admin/users (PLAN.md §5). */
public record AdminUserSummary(
        Long id,
        String email,
        String displayName,
        Role role,
        Tier tier,
        LocalDateTime createdAt,
        LocalDateTime lastActiveAt,
        long sessionCount,
        Double averageScore,
        Topic mostPracticedTopic
) {
}
