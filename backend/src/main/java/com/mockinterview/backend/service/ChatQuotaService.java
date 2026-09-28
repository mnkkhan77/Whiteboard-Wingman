package com.mockinterview.backend.service;

import com.mockinterview.backend.config.TierProperties;
import com.mockinterview.backend.dto.ChatQuotaDto;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.exception.PackChatException;
import com.mockinterview.backend.repository.LlmUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Monthly pack-chat token budget (docs/study-packs-contract.md "Tier quota"): calendar month in
 * UTC, prompt + completion tokens. A request is refused once used >= limit; the answer's real
 * usage is only known afterwards and is added then, so one answer may overshoot — accepted.
 */
@Service
@RequiredArgsConstructor
public class ChatQuotaService {

    private final LlmUsageRepository llmUsageRepository;
    private final TierProperties tierProperties;

    /** First day of the UTC calendar month containing {@code now} — the llm_usage period key. */
    static LocalDate periodStart(Instant now) {
        return now.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    }

    public long usedThisMonth(Long userId) {
        return llmUsageRepository.findTokensUsed(userId, periodStart(Instant.now())).orElse(0L);
    }

    public ChatQuotaDto quota(User user) {
        return new ChatQuotaDto(usedThisMonth(user.getId()), limitFor(user));
    }

    /** @throws PackChatException 429 CHAT_QUOTA_EXCEEDED once this month's budget is used up */
    public ChatQuotaDto requireAvailable(User user) {
        ChatQuotaDto quota = quota(user);
        if (quota.used() >= quota.limit()) {
            throw new PackChatException(HttpStatus.TOO_MANY_REQUESTS, PackChatException.CHAT_QUOTA_EXCEEDED,
                    "You've used this month's chat budget for your " + user.getTier() + " tier ("
                            + quota.limit() + " tokens). It resets on the 1st (UTC) — or upgrade for more.");
        }
        return quota;
    }

    /** Adds spent tokens atomically and returns the resulting quota (no extra read). */
    public ChatQuotaDto record(User user, long tokens) {
        long used = tokens > 0
                ? llmUsageRepository.addTokens(user.getId(), periodStart(Instant.now()), tokens)
                : usedThisMonth(user.getId());
        return new ChatQuotaDto(used, limitFor(user));
    }

    private long limitFor(User user) {
        return tierProperties.forTier(user.getTier()).chatTokensPerMonth();
    }
}
