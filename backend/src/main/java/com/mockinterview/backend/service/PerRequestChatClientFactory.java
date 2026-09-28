package com.mockinterview.backend.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

/**
 * Builds a throwaway ChatClient per request from the caller's own LLM API key.
 * This is the BYO-key mechanism: no shared server-side key or ChatClient bean is ever used
 * for an end-user's chat/evaluation calls, and the key passed in here is never persisted
 * (not to a DB column, not to a log line) — see PLAN.md §8.
 *
 * The one exception is pack chat, which runs on the server's own key (ServerChatClientProvider);
 * it reuses {@link #build} so both paths construct the provider client the same way.
 *
 * Groq exposes an OpenAI-compatible chat-completions API, so both providers reuse the same
 * OpenAiChatModel client, just pointed at a different base URL / default model.
 */
@Service
public class PerRequestChatClientFactory {

    public enum Provider { GROQ, OPENAI }

    private static final String GROQ_BASE_URL = "https://api.groq.com/openai";
    private static final String OPENAI_BASE_URL = "https://api.openai.com";
    // Groq periodically retires/renames model ids; verified live against /v1/models as of 2026-09.
    private static final String GROQ_DEFAULT_MODEL = "openai/gpt-oss-20b";
    private static final String OPENAI_DEFAULT_MODEL = "gpt-4o-mini";

    public ChatClient forRequest(String apiKey, Provider provider, String modelOverride) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Missing X-LLM-Api-Key header");
        }
        return build(apiKey, provider, OpenAiChatOptions.builder().model(modelOverride).build());
    }

    /**
     * A client for an explicit key/provider with the given default options (model, token cap,
     * streaming usage...). A null/blank model in the options falls back to the provider's default.
     */
    public ChatClient build(String apiKey, Provider provider, OpenAiChatOptions defaultOptions) {
        OpenAiChatOptions options = defaultOptions.copy();
        if (options.getModel() == null || options.getModel().isBlank()) {
            options.setModel(defaultModel(provider));
        }

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(provider == Provider.GROQ ? GROQ_BASE_URL : OPENAI_BASE_URL)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();

        return ChatClient.builder(chatModel).build();
    }

    static String defaultModel(Provider provider) {
        return provider == Provider.GROQ ? GROQ_DEFAULT_MODEL : OPENAI_DEFAULT_MODEL;
    }
}
