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
 * Groq exposes an OpenAI-compatible chat-completions API, so both providers reuse the same
 * OpenAiChatModel client, just pointed at a different base URL / default model.
 * [VERIFY] the exact OpenAiApi/OpenAiChatModel builder API against the pinned Spring AI version.
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

        String baseUrl = provider == Provider.GROQ ? GROQ_BASE_URL : OPENAI_BASE_URL;
        String model = modelOverride != null && !modelOverride.isBlank()
                ? modelOverride
                : (provider == Provider.GROQ ? GROQ_DEFAULT_MODEL : OPENAI_DEFAULT_MODEL);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();

        return ChatClient.builder(chatModel).build();
    }
}
