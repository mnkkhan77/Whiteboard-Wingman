package com.mockinterview.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the non-Kafka Study Pack settings: app.tiers.*, app.storage.*, and for pack chat
 *  app.chat.* and app.llm.server.*. */
@Configuration
@EnableConfigurationProperties({TierProperties.class, StorageProperties.class, ChatProperties.class,
        LlmServerProperties.class})
public class StudyPackConfig {
}
