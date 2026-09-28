package com.mockinterview.backend.service;

import com.mockinterview.backend.config.LlmServerProperties;
import com.mockinterview.backend.exception.PackChatException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerChatClientProviderTest {

    private final PerRequestChatClientFactory factory = new PerRequestChatClientFactory();

    @Test
    void withoutAServerKeyChatIsUnavailableButTheAppStillStarts() {
        for (String key : new String[]{null, "", "   "}) {
            ServerChatClientProvider provider = new ServerChatClientProvider(
                    new LlmServerProperties(key, null, "openai/gpt-oss-20b", null, null, null), factory);
            assertThatThrownBy(provider::require).isInstanceOfSatisfying(PackChatException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(e.getCode()).isEqualTo(PackChatException.CHAT_UNAVAILABLE);
            });
        }
    }

    @Test
    void aConfiguredKeyBuildsAClientWithoutCallingTheProvider() {
        LlmServerProperties properties = new LlmServerProperties("gsk_test_not_a_real_key", null, null, null, null, "low");
        assertThat(new ServerChatClientProvider(properties, factory).require()).isNotNull();
        // Defaults, and the key never shows up in a log line built from the properties.
        assertThat(properties.provider()).isEqualTo(PerRequestChatClientFactory.Provider.GROQ);
        assertThat(properties.maxOutputTokens()).isEqualTo(800);
        assertThat(properties.toString()).doesNotContain("gsk_test_not_a_real_key");
    }

    @Test
    void theByoKeyPathStillRejectsAMissingKey() {
        assertThatThrownBy(() -> factory.forRequest(" ", PerRequestChatClientFactory.Provider.GROQ, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing X-LLM-Api-Key header");
        assertThat(factory.forRequest("user-key", PerRequestChatClientFactory.Provider.OPENAI, null)).isNotNull();
    }
}
