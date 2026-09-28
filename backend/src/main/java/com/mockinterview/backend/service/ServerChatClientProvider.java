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
 * The server-key ChatClients for the study-pack features (app.llm.server.*), built once at
 * startup — the key is static config, unlike the per-request BYO keys. No key configured is a
 * supported state: pack chat and pack quizzes then refuse with 503 CHAT_UNAVAILABLE and nothing
 * else in the app changes.
 *
 * Two clients over the same key and model, because their default options differ and Spring AI
 * 1.0 doesn't reliably merge a per-call max_completion_tokens over the defaults:
 * - {@link #require()}: streamed chat answers (small output cap, stream usage on);
 * - {@link #requireStructured()}: non-streaming JSON calls — quiz generation, grading and report
 *   narrative of pack sessions — which need a bigger output cap and must not send stream options.
 */
@Component
public class ServerChatClientProvider {

    private static final Logger log = LoggerFactory.getLogger(ServerChatClientProvider.class);

    private final ChatClient chatClient;
    private final ChatClient structuredClient;

    public ServerChatClientProvider(LlmServerProperties properties, PerRequestChatClientFactory factory) {
        if (!properties.configured()) {
            log.info("No server LLM key (GROQ_API_KEY) configured — pack chat and pack quizzes are disabled");
            this.chatClient = null;
            this.structuredClient = null;
            return;
        }
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(properties.model())
                .maxCompletionTokens(properties.maxOutputTokens())
                .temperature(properties.temperature())
                .reasoningEffort(properties.reasoningEffort())
                // Ask for the final usage chunk (stream_options.include_usage) — the quota is
                // charged from it; PackChatService falls back to an estimate if it never arrives.
                .streamUsage(true)
                .build();
        OpenAiChatOptions structuredOptions = OpenAiChatOptions.builder()
                .model(properties.model())
                .maxCompletionTokens(properties.structuredMaxOutputTokens())
                .temperature(properties.temperature())
                .reasoningEffort(properties.reasoningEffort())
                .build();
        this.chatClient = factory.build(properties.apiKey(), properties.provider(), chatOptions);
        this.structuredClient = factory.build(properties.apiKey(), properties.provider(), structuredOptions);
        log.info("Pack chat and pack quizzes enabled: {}", properties); // toString omits the key
    }

    /** The streaming chat client. @throws PackChatException 503 CHAT_UNAVAILABLE when no server key is configured */
    public ChatClient require() {
        return orUnavailable(chatClient, "Chat is");
    }

    /** The non-streaming structured-output client. @throws PackChatException 503 CHAT_UNAVAILABLE when no server key is configured */
    public ChatClient requireStructured() {
        return orUnavailable(structuredClient, "Study pack quizzes are");
    }

    private static ChatClient orUnavailable(ChatClient client, String featureIs) {
        if (client == null) {
            throw new PackChatException(HttpStatus.SERVICE_UNAVAILABLE, PackChatException.CHAT_UNAVAILABLE,
                    featureIs + " not available right now — the server has no LLM key configured.");
        }
        return client;
    }
}
