package com.mockinterview.backend.config;

import com.mockinterview.backend.service.PerRequestChatClientFactory.Provider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * app.llm.server.* — the one server-side LLM key, used by the study-pack features: "chat with a
 * pack" and "quiz from a pack" (bank generation, grading and report narrative of pack sessions —
 * docs/study-packs-contract.md). Everything else stays BYO-key (PerRequestChatClientFactory.forRequest).
 * A blank apiKey is a valid configuration: those features then answer 503 CHAT_UNAVAILABLE instead
 * of the app failing to start.
 *
 * @param maxOutputTokens           chat answers: sent as max_completion_tokens; for a reasoning model
 *                                  (gpt-oss) this budget also covers its hidden reasoning, hence
 *                                  reasoningEffort "low"
 * @param structuredMaxOutputTokens the non-streaming structured-JSON calls (question generation,
 *                                  grading, report narrative): one generation batch returns several
 *                                  questions of JSON, far more than a chat answer
 * @param reasoningEffort           optional ("low" | "medium" | "high"), only sent when set
 */
@ConfigurationProperties(prefix = "app.llm.server")
public record LlmServerProperties(
        String apiKey,
        Provider provider,
        String model,
        Integer maxOutputTokens,
        Double temperature,
        String reasoningEffort,
        Integer structuredMaxOutputTokens
) {
    @ConstructorBinding // needed now that a second (convenience) constructor exists
    public LlmServerProperties {
        provider = provider == null ? Provider.GROQ : provider;
        maxOutputTokens = maxOutputTokens == null ? 800 : maxOutputTokens;
        temperature = temperature == null ? 0.2 : temperature;
        structuredMaxOutputTokens = structuredMaxOutputTokens == null ? 4096 : structuredMaxOutputTokens;
    }

    public LlmServerProperties(String apiKey, Provider provider, String model, Integer maxOutputTokens,
                               Double temperature, String reasoningEffort) {
        this(apiKey, provider, model, maxOutputTokens, temperature, reasoningEffort, null);
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Never print the key — this record's generated toString would. */
    @Override
    public String toString() {
        return "LlmServerProperties[provider=" + provider + ", model=" + model + ", configured=" + configured() + "]";
    }
}
