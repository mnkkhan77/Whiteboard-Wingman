package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.ChatQuotaDto;
import com.mockinterview.backend.entity.Tier;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.exception.PackChatException;
import com.mockinterview.backend.repository.LlmUsageRepository;
import com.mockinterview.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Monthly pack-chat quota against the real llm_usage table (the atomicity claim is about
 * PostgreSQL's ON CONFLICT, so it has to run on PostgreSQL). Same annotations as the other
 * integration tests so Spring reuses their cached context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatQuotaServiceTest {

    @Autowired private ChatQuotaService chatQuotaService;
    @Autowired private LlmUsageRepository llmUsageRepository;
    @Autowired private UserRepository userRepository;

    private User newUser(Tier tier) {
        User user = new User();
        user.setEmail("quota-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("x");
        user.setTier(tier);
        return userRepository.save(user);
    }

    @Test
    void thePeriodIsTheUtcCalendarMonth() {
        assertThat(ChatQuotaService.periodStart(Instant.parse("2026-09-30T23:59:59Z"))).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(ChatQuotaService.periodStart(Instant.parse("2026-10-01T00:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 1));
        // 01:30 on Oct 1 in India is still September in UTC.
        assertThat(ChatQuotaService.periodStart(Instant.parse("2026-09-30T20:00:00Z"))).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(ChatQuotaService.periodStart(Instant.parse("2026-12-31T23:00:00Z"))).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void usageAccumulatesAndIsRefusedOnceTheLimitIsReached() {
        User user = newUser(Tier.FREE); // 20000 tokens / month

        assertThat(chatQuotaService.quota(user)).isEqualTo(new ChatQuotaDto(0, 20_000));
        assertThat(chatQuotaService.record(user, 19_999)).isEqualTo(new ChatQuotaDto(19_999, 20_000));
        assertThat(chatQuotaService.requireAvailable(user).used()).isEqualTo(19_999); // one token left: still allowed

        // The answer that crosses the limit is still recorded in full (accepted overshoot)...
        assertThat(chatQuotaService.record(user, 500).used()).isEqualTo(20_499);
        // ...and the next request is refused before any LLM call.
        assertThatThrownBy(() -> chatQuotaService.requireAvailable(user))
                .isInstanceOfSatisfying(PackChatException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getCode()).isEqualTo(PackChatException.CHAT_QUOTA_EXCEEDED);
                });
    }

    @Test
    void theLimitFollowsTheTier() {
        assertThat(chatQuotaService.quota(newUser(Tier.PRO)).limit()).isEqualTo(500_000);
        assertThat(chatQuotaService.quota(newUser(Tier.MAX)).limit()).isEqualTo(2_000_000);
    }

    @Test
    void recordingZeroTokensWritesNothing() {
        User user = newUser(Tier.FREE);
        assertThat(chatQuotaService.record(user, 0)).isEqualTo(new ChatQuotaDto(0, 20_000));
        assertThat(llmUsageRepository.findTokensUsed(user.getId(), ChatQuotaService.periodStart(Instant.now()))).isEmpty();
    }

    @Test
    void concurrentIncrementsNeverLoseAnUpdate() throws Exception {
        User user = newUser(Tier.MAX);
        LocalDate period = LocalDate.of(2026, 9, 1);
        int threads = 16;
        int incrementsPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    start.await(); // maximize overlap, including on the very first (inserting) upsert
                    for (int i = 0; i < incrementsPerThread; i++) {
                        llmUsageRepository.addTokens(user.getId(), period, 7);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(llmUsageRepository.findTokensUsed(user.getId(), period)).contains(7L * threads * incrementsPerThread);
    }
}
