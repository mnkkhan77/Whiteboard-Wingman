package com.mockinterview.backend.service;

import com.mockinterview.backend.config.QuizProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * The pool question-bank generation runs on: never the HTTP request thread (a bank is several LLM
 * calls, tens of seconds) and never the Kafka listener thread (which must stay free for
 * embedding). Bounded both ways — a few threads so one burst can't hog the server key's rate
 * limit, and a finite queue so overload fails fast as "busy" instead of piling up jobs a restart
 * would lose anyway.
 *
 * Deliberately a wrapper, not an Executor bean: any Executor bean in the context makes Spring
 * Boot back off its own applicationTaskExecutor, which Spring MVC uses for async (SSE) requests.
 */
@Component
public class QuizGenerationExecutor implements DisposableBean {

    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public QuizGenerationExecutor(QuizProperties properties) {
        executor.setCorePoolSize(properties.generationThreads());
        executor.setMaxPoolSize(properties.generationThreads());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("quiz-gen-");
        // Don't hold up shutdown for running jobs: they are marked FAILED ("interrupted") at the
        // next startup, and the user can simply generate again.
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
