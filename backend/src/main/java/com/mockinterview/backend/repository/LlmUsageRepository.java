package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.LlmUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

public interface LlmUsageRepository extends JpaRepository<LlmUsage, LlmUsage.Key> {

    @Query("select u.tokensUsed from LlmUsage u where u.id.userId = :userId and u.id.periodStart = :periodStart")
    Optional<Long> findTokensUsed(@Param("userId") Long userId, @Param("periodStart") LocalDate periodStart);

    /**
     * Adds tokens to the user's month and returns the new total, in one atomic statement: the
     * upsert takes the row lock itself, so two answers finishing at the same moment both count —
     * no read-modify-write window, no lost update, no unique-violation on the first answer of a
     * month. Not @Modifying: RETURNING makes it a result-producing query.
     */
    @Transactional
    @Query(nativeQuery = true, value = """
            INSERT INTO llm_usage (user_id, period_start, tokens_used)
            VALUES (:userId, :periodStart, :tokens)
            ON CONFLICT (user_id, period_start)
            DO UPDATE SET tokens_used = llm_usage.tokens_used + EXCLUDED.tokens_used
            RETURNING tokens_used
            """)
    long addTokens(@Param("userId") Long userId, @Param("periodStart") LocalDate periodStart,
                   @Param("tokens") long tokens);
}
