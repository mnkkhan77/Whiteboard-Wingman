package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.exception.PackChatException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/**
 * The LLM an interview session grades answers and writes its report narrative with — whose key it
 * runs on, and who pays for it. Obtained per request from {@link SessionLlmResolver}:
 * - handbook-topic sessions: the candidate's own key from the X-LLM-* headers, nothing charged
 *   (exactly the pre-Phase-4 behaviour);
 * - study-pack quizzes: the server key, every call charged to the user's monthly token budget
 *   (docs/study-packs-contract.md "Modelling"); the X-LLM-* headers are ignored.
 */
public interface SessionLlm {

    /** One structured-output call: system + user prompt in, the parsed {@code type} out. */
    <T> T entity(String system, String user, Class<T> type);

    /** For log lines only; never contains a key. */
    String describe();

    /** BYO key: the same ChatClient call chain InterviewSessionService/ReportService always used. */
    final class ByoKey implements SessionLlm {

        private final ChatClient client;
        private final String description;

        ByoKey(ChatClient client, String description) {
            this.client = client;
            this.description = description;
        }

        @Override
        public <T> T entity(String system, String user, Class<T> type) {
            return client.prompt().system(system).user(user).call().entity(type);
        }

        @Override
        public String describe() {
            return description;
        }
    }

    /**
     * Server key, metered: usage is recorded even past the limit — a started session always
     * finishes. A 429 (the shared key's per-minute limit) is retried briefly; a provider failure
     * then becomes a {code, message} error the user can retry — the answer isn't saved, the
     * request's transaction rolls back — never GlobalExceptionHandler's BYO "check your API key"
     * reply, whose providerDetail would expose the server's own provider account.
     */
    final class ServerKey implements SessionLlm {

        private final ChatClient client;
        private final MeteredLlmCall meteredCall;
        private final User payer;
        private final int rateLimitRetries;
        private final Duration rateLimitBackoff;

        ServerKey(ChatClient client, MeteredLlmCall meteredCall, User payer, int rateLimitRetries,
                  Duration rateLimitBackoff) {
            this.client = client;
            this.meteredCall = meteredCall;
            this.payer = payer;
            this.rateLimitRetries = rateLimitRetries;
            this.rateLimitBackoff = rateLimitBackoff;
        }

        @Override
        public <T> T entity(String system, String user, Class<T> type) {
            try {
                return meteredCall.entityWithRetry(client, payer, system, user, type, rateLimitRetries, rateLimitBackoff);
            } catch (NonTransientAiException | TransientAiException e) {
                throw MeteredLlmCall.isRateLimited(e)
                        ? new PackChatException(HttpStatus.SERVICE_UNAVAILABLE, PackChatException.LLM_RATE_LIMITED,
                                "The AI provider is busy right now (rate limited). Please try again in a moment.")
                        : new PackChatException(HttpStatus.BAD_GATEWAY, PackChatException.LLM_ERROR,
                                "The AI provider couldn't process this right now. Please try again.");
            }
        }

        @Override
        public String describe() {
            return "server key (user " + payer.getId() + ")";
        }
    }
}
