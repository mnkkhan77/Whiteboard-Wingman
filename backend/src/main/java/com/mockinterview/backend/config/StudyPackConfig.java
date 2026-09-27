package com.mockinterview.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the non-Kafka Study Pack settings: app.tiers.* and app.storage.*. */
@Configuration
@EnableConfigurationProperties({TierProperties.class, StorageProperties.class})
public class StudyPackConfig {
}
