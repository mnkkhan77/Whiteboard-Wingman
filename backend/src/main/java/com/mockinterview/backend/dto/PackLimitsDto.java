package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Tier;

import java.util.List;

/** GET /api/packs/limits — the caller's tier limits plus current usage, so the upload UI can
 *  pre-validate without hard-coding app.tiers.* (docs/study-packs-contract.md "PackLimitsDto").
 *  maxPacks is -1 for unlimited. chatTokensUsed is this calendar month's (UTC) pack chat usage. */
public record PackLimitsDto(
        Tier tier,
        long maxFileBytes,
        int maxPages,
        int maxPacks,
        int maxChunksPerPack,
        boolean ocrEnabled,
        List<String> allowedExtensions,
        long packsUsed,
        long chatTokensPerMonth,
        long chatTokensUsed
) {
}
