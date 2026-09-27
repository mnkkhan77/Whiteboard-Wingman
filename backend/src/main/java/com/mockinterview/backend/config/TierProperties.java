package com.mockinterview.backend.config;

import com.mockinterview.backend.entity.Tier;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Per-tier Study Pack limits (app.tiers.{free,pro,max}.* in application.yml), the single source
 * of truth the frontend reads back through GET /api/packs/limits rather than hard-coding them
 * (docs/study-packs-contract.md "Tiers").
 */
@ConfigurationProperties(prefix = "app.tiers")
public record TierProperties(TierLimits free, TierLimits pro, TierLimits max) {

    public TierLimits forTier(Tier tier) {
        TierLimits limits = switch (tier) {
            case FREE -> free;
            case PRO -> pro;
            case MAX -> max;
        };
        if (limits == null) {
            throw new IllegalStateException("No app.tiers config for tier " + tier);
        }
        return limits;
    }

    /**
     * @param maxPacks          -1 means unlimited
     * @param allowedExtensions lower-case, without the leading dot
     */
    public record TierLimits(
            long maxFileBytes,
            int maxPages,
            int maxPacks,
            int maxChunksPerPack,
            boolean ocrEnabled,
            List<String> allowedExtensions
    ) {
        public TierLimits {
            allowedExtensions = allowedExtensions == null ? List.of()
                    : allowedExtensions.stream().map(e -> e.toLowerCase(Locale.ROOT)).toList();
        }

        public boolean unlimitedPacks() {
            return maxPacks < 0;
        }

        public boolean allowsExtension(String extension) {
            return extension != null && allowedExtensions.contains(extension.toLowerCase(Locale.ROOT));
        }
    }
}
