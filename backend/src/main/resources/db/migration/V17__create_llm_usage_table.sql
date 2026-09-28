-- Pack chat: LLM tokens spent per user per calendar month (UTC), checked against
-- app.tiers.*.chat-tokens-per-month. period_start is the first day of that month. One row per
-- (user, month), incremented with INSERT ... ON CONFLICT DO UPDATE (LlmUsageRepository), so
-- concurrent answers never lose an update to a read-modify-write race.
CREATE TABLE llm_usage (
    user_id BIGINT NOT NULL,
    period_start DATE NOT NULL,
    tokens_used BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_llm_usage PRIMARY KEY (user_id, period_start),
    CONSTRAINT fk_llm_usage_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
