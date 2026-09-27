package com.mockinterview.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.storage.root — local directory shared with the doc-processor (mounted as its STORAGE_ROOT
 *  in docker-compose). Every path exchanged over Kafka is relative to it. */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(String root) {

    public StorageProperties {
        if (root == null || root.isBlank()) {
            root = "./data/uploads";
        }
    }
}
