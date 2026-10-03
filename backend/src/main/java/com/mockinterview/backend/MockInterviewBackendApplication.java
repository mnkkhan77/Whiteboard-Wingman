package com.mockinterview.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** EnableScheduling backs the transactional outbox poller (OutboxPublisher). */
@SpringBootApplication
@EnableScheduling
public class MockInterviewBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockInterviewBackendApplication.class, args);
    }
}
