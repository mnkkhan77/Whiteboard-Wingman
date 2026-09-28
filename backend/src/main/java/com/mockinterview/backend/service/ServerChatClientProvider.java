package com.mockinterview.backend.service;

import com.mockinterview.backend.config.LlmServerProperties;
import com.mockinterview.backend.exception.PackChatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The server-key ChatClient for pack chat (app.llm.server.*), built once at startup — the key is
 * static config, unlike the per-request BYO keys. No key configured is a supported state: chat
 * then refuses with 503 CHAT_UNAVAILABLE and nothing else in the app changes.
 */
@Component
public class ServerChatClientProvider {

    private static final Logger log = LoggerFactory.getLogger(ServerChatClientProvider.class);

    private final ChatClient chatClient;

    public ServerChatClientProvider(LlmServerProperties properties, PerRequestChatClientFactory factory) {
        if (!properties.configured()) {
            log.info("No server LLM key (GROQ_API_KEY) configured — pack chat is disabled");
            this.chatClient = null;
            return;
        }
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.model())
                .maxCompletionTokens(properties.maxOutputTokens())
                .temperature(properties.temperature())
                .reasoningEffort(properties.reasoningEffort())
                // Ask for the final usage chunk (stream_options.include_usage) — the quota is
                // charged from it; PackChatService falls back to an estimate if it never arrives.
                .streamUsage(true)
                .build();
        this.chatClient = factory.build(properties.apiKey(), properties.provider(), options);
        log.info("Pack chat enabled: {}", properties); // toString omits the key
    }

    /** @throws PackChatException 503 CHAT_UNAVAILABLE when no server key is configured */
    public ChatClient require() {
        if (chatClient == null) {
            throw new PackChatException(HttpStatus.SERVICE_UNAVAILABLE, PackChatException.CHAT_UNAVAILABLE,
                    "Chat is not available right now — the server has no LLM key configured.");
        }
        return chatClient;
    }
}
