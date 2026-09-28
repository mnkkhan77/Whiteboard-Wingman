package com.mockinterview.backend.config;

import com.mockinterview.backend.service.PerRequestChatClientFactory.Provider;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.llm.server.* — the one server-side LLM key, used only by "chat with a pack"
 * (docs/study-packs-contract.md "Chat with a pack"). Everything else stays BYO-key
 * (PerRequestChatClientFactory.forRequest). A blank apiKey is a valid configuration: chat then
 * answers 503 CHAT_UNAVAILABLE instead of the app failing to start.
 *
 * @param maxOutputTokens sent as max_completion_tokens; for a reasoning model (gpt-oss) this budget
 *                        also covers its hidden reasoning, hence reasoningEffort "low"
 * @param reasoningEffort optional ("low" | "medium" | "high"), only sent when set
 */
@ConfigurationProperties(prefix = "app.llm.server")
public record LlmServerProperties(
        String apiKey,
        Provider provider,
        String model,
        Integer maxOutputTokens,
        Double temperature,
        String reasoningEffort
) {
    public LlmServerProperties {
        provider = provider == null ? Provider.GROQ : provider;
        maxOutputTokens = maxOutputTokens == null ? 800 : maxOutputTokens;
        temperature = temperature == null ? 0.2 : temperature;
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
