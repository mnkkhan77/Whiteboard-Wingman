package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One non-streaming, structured-output call on the server key whose token usage is charged to the
 * user's monthly budget (ChatQuotaService) — the building block of every pack-quiz LLM call: bank
 * generation, grading and the report narrative.
 *
 * It is ChatClient's {@code .call().entity(type)} split in two, because entity() hides the
 * response metadata the charge is read from. The order is call -> charge -> parse, so a response
 * that then fails to parse was still paid for. The quota itself is never checked here: callers
 * check it where the contract says (generate, session start) — once started, work always finishes.
 */
@Component
@RequiredArgsConstructor
public class MeteredLlmCall {

    private static final Logger log = LoggerFactory.getLogger(MeteredLlmCall.class);

    /** Rough chars-per-token for English text, only used when the provider reports no usage. */
    private static final int CHARS_PER_TOKEN = 4;
    private static final AtomicBoolean ESTIMATE_LOGGED = new AtomicBoolean();

    private final ChatQuotaService chatQuotaService;

    /**
     * @throws RuntimeException the provider's error (nothing is charged then — a rejected call
     *                          reports no usage), or a parse error for a malformed response
     *                          (charged: the tokens were spent)
     */
    public <T> T entity(ChatClient client, User user, String system, String userText, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        // No template params are passed, so ChatClient sends this text verbatim: braces in the
        // JSON schema (or in document text / candidate code inside userText) are safe.
        String prompt = userText + "\n\n" + converter.getFormat();
        ChatResponse response = client.prompt().system(system).user(prompt).call().chatResponse();
        String text = textOf(response);
        chargeQuietly(user, tokensOf(response, system.length() + prompt.length(), text.length()));
        if (text.isBlank()) {
            throw new IllegalStateException("The model returned an empty response");
        }
        return converter.convert(text);
    }

    /**
     * {@link #entity}, but plain prose rather than a structured-output type — a course lesson's
     * content, written once and cached, not JSON. No format suffix is appended to the prompt.
     *
     * @throws RuntimeException the provider's error (nothing is charged then), or
     *                          IllegalStateException for a blank response (charged: spent anyway)
     */
    public String text(ChatClient client, User user, String system, String userText) {
        ChatResponse response = client.prompt().system(system).user(userText).call().chatResponse();
        String text = textOf(response);
        chargeQuietly(user, tokensOf(response, system.length() + userText.length(), text.length()));
        if (text.isBlank()) {
            throw new IllegalStateException("The model returned an empty response");
        }
        return text.strip();
    }

    /** {@link #text}, with the same rate-limit retry as {@link #entityWithRetry}. */
    public String textWithRetry(ChatClient client, User user, String system, String userText,
                                int retries, Duration backoff) {
        Duration wait = backoff;
        for (int attempt = 0; ; attempt++) {
            try {
                return text(client, user, system, userText);
            } catch (RuntimeException e) {
                if (!isRateLimited(e) || attempt >= retries) {
                    throw e;
                }
                log.info("Server LLM rate limited; retrying in {} ms", wait.toMillis());
                sleep(wait);
                wait = wait.multipliedBy(2);
            }
        }
    }

    /**
     * {@link #entity}, retrying only an HTTP 429 (Groq's per-minute token/request limits, which the
     * shared server key hits easily) up to {@code retries} times, waiting {@code backoff} and
     * doubling it each time. A rejected call costs nothing, so retrying is never double-charged.
     */
    public <T> T entityWithRetry(ChatClient client, User user, String system, String userText, Class<T> type,
                                 int retries, Duration backoff) {
        Duration wait = backoff;
        for (int attempt = 0; ; attempt++) {
            try {
                return entity(client, user, system, userText, type);
            } catch (RuntimeException e) {
                if (!isRateLimited(e) || attempt >= retries) {
                    throw e;
                }
                log.info("Server LLM rate limited; retrying in {} ms", wait.toMillis());
                sleep(wait);
                wait = wait.multipliedBy(2);
            }
        }
    }

    /** Non-streaming calls surface a 429 as Spring AI's NonTransientAiException("429 - ..."). */
    public static boolean isRateLimited(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof NonTransientAiException && t.getMessage() != null && t.getMessage().startsWith("429")) {
                return true;
            }
        }
        return PackChatService.isRateLimited(error);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }

    private long tokensOf(ChatResponse response, int promptChars, int outputChars) {
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
            return usage.getTotalTokens();
        }
        if (ESTIMATE_LOGGED.compareAndSet(false, true)) {
            log.warn("LLM response reported no token usage; charging the quota with a ~{} chars/token estimate",
                    CHARS_PER_TOKEN);
        }
        return (promptChars + outputChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /** A failed quota write must not throw away an answer that was already graded / generated. */
    private void chargeQuietly(User user, long tokens) {
        try {
            chatQuotaService.record(user, tokens);
        } catch (RuntimeException e) {
            log.error("Could not record {} LLM tokens for user {}", tokens, user.getId(), e);
        }
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }
}
