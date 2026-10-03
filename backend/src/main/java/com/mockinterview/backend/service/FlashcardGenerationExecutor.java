package com.mockinterview.backend.service;

import com.mockinterview.backend.config.FlashcardProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * The pool flashcard-deck generation runs on — see QuizGenerationExecutor for why this is a bounded
 * wrapper of its own rather than a shared Executor bean. A separate pool from the quiz one so a burst
 * of quiz generation can't starve flashcard generation (and vice versa).
 */
@Component
public class FlashcardGenerationExecutor implements DisposableBean {

    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public FlashcardGenerationExecutor(FlashcardProperties properties) {
        executor.setCorePoolSize(properties.generationThreads());
        executor.setMaxPoolSize(properties.generationThreads());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("flashcard-gen-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
    }

    /** @throws TaskRejectedException when every thread is busy and the queue is full */
    public void submit(Runnable job) {
        executor.execute(job);
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
