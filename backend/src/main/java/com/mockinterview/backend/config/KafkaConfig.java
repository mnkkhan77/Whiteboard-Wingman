package com.mockinterview.backend.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mockinterview.backend.kafka.InvalidDocumentEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka wiring for the Study Pack pipeline (docs/study-packs-contract.md). Serializers, consumer
 * group and poll settings are plain spring.kafka.* properties in application.yml; this class only
 * adds what properties can't express: the retry/dead-letter policy and the topics to create.
 */
@Configuration
@EnableConfigurationProperties(StudyPackKafkaProperties.class)
public class KafkaConfig {

    private static final String DLT_SUFFIX = ".DLT";
    private static final int PARTITIONS = 3;

    /**
     * Picked up by Boot's default listener container factory. A handler exception is retried a
     * few times (covers a transient DB/storage hiccup), then the record is published unchanged to
     * {topic}.DLT and the partition moves on — one bad message must never block every later
     * pack. Decode failures skip the retries: the same bytes will never parse.
     *
     * Partition -1 lets the producer choose, so the DLT doesn't need the source topic's
     * partition count (it may have been auto-created by the broker with fewer).
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(2_000L, 3));
        handler.addNotRetryableExceptions(JsonProcessingException.class, InvalidDocumentEventException.class);
        return handler;
    }

    /** Created on startup if missing (KafkaAdmin; no-op when they exist, e.g. made by compose). */
    @Bean
    public KafkaAdmin.NewTopics studyPackTopics(StudyPackKafkaProperties properties) {
        StudyPackKafkaProperties.Topics topics = properties.topics();
        return new KafkaAdmin.NewTopics(
                topic(topics.uploaded()), topic(topics.uploaded() + DLT_SUFFIX),
                topic(topics.parsed()), topic(topics.parsed() + DLT_SUFFIX),
                topic(topics.failed()), topic(topics.failed() + DLT_SUFFIX));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }
}
