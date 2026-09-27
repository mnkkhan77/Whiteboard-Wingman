package com.mockinterview.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Topic names for the Study Pack pipeline (app.kafka.topics.*). Kept in config rather than as
 * constants so an environment can namespace them without a code change; the defaults are the
 * names in docs/study-packs-contract.md, which the doc-processor uses too.
 */
@ConfigurationProperties(prefix = "app.kafka")
public record StudyPackKafkaProperties(Topics topics) {

    public record Topics(String uploaded, String parsed, String failed) {
    }
}
