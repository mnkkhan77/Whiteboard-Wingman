package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Tier;
import jakarta.validation.constraints.NotNull;

/** PUT /api/admin/users/{userId}/tier body, e.g. {"tier": "PRO"}. */
public record UpdateTierRequest(@NotNull Tier tier) {
}
