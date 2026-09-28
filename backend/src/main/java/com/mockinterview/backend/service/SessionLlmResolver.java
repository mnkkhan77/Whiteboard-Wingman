package com.mockinterview.backend.service;

import com.mockinterview.backend.config.QuizProperties;
import com.mockinterview.backend.entity.InterviewSession;
import com.mockinterview.backend.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Picks a session's {@link SessionLlm}: the one place that decides "BYO key or server key" for
 * interview grading and report narratives, so InterviewSessionService and ReportService never
 * branch on the session kind to build a ChatClient themselves.
 */
@Component
@RequiredArgsConstructor
public class SessionLlmResolver {

    private final PerRequestChatClientFactory chatClientFactory;
    private final ServerChatClientProvider serverChatClientProvider;
    private final MeteredLlmCall meteredLlmCall;
    private final QuizProperties quizProperties;

    /**
     * @param payer the session's owner (already authorized by the caller) — charged for pack sessions
     * @throws IllegalArgumentException BYO session without an X-LLM-Api-Key (unchanged behaviour)
     * @throws com.mockinterview.backend.exception.PackChatException 503 for a pack session when the
     *         server key has been removed since it started
     */
    public SessionLlm forSession(InterviewSession session, User payer, String apiKey,
                                 PerRequestChatClientFactory.Provider provider, String model) {
        if (session.isPackSession()) {
            return new SessionLlm.ServerKey(serverChatClientProvider.requireStructured(), meteredLlmCall, payer,
                    quizProperties.sessionRateLimitRetries(), quizProperties.sessionRateLimitBackoff());
        }
        return new SessionLlm.ByoKey(chatClientFactory.forRequest(apiKey, provider, model),
                "provider=" + provider + ", model=" + model);
    }
}
