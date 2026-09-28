package com.mockinterview.backend.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Shared by the pack-quiz integration tests: a fake server-key LLM that answers the three kinds of
 * non-streaming calls a pack quiz makes — bank generation, grounded grading, report narrative —
 * told apart by their system prompt, with scripted replies and provider-style usage metadata.
 */
final class PackQuizTestSupport {

    private PackQuizTestSupport() {
    }

    static final ObjectMapper JSON = new ObjectMapper();

    static final int GENERATION_PROMPT_TOKENS = 1000;
    static final int GENERATION_COMPLETION_TOKENS = 500;
    static final int GRADING_TOKENS = 400;
    static final int NARRATIVE_TOKENS = 300;

    enum CallKind { GENERATION, GRADING, NARRATIVE }

    record Call(CallKind kind, String system, String user) {
    }

    static final class FakeQuizLlm implements ChatModel {

        final List<Call> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger generationCalls = new AtomicInteger();

        /** Generation reply for the n-th (0-based) generation call; may throw to simulate a failure. */
        volatile Function<Integer, String> generation = PackQuizTestSupport::validBatch;
        /** While set, generation calls wait for it — to hold a job in GENERATING. */
        volatile CountDownLatch gate;
        volatile String narrative = narrativeJson(List.of(), List.of(), "Solid work on the material.");
        /** When set, every grading call throws it (after being recorded). */
        volatile RuntimeException gradingFailure;

        @Override
        public ChatResponse call(Prompt prompt) {
            String system = textOf(prompt, MessageType.SYSTEM);
            String user = textOf(prompt, MessageType.USER);
            if (system.contains("You write quiz questions")) {
                calls.add(new Call(CallKind.GENERATION, system, user));
                int n = generationCalls.getAndIncrement();
                awaitGate();
                return reply(generation.apply(n), GENERATION_PROMPT_TOKENS, GENERATION_COMPLETION_TOKENS);
            }
            if (system.contains("tutor grading")) {
                calls.add(new Call(CallKind.GRADING, system, user));
                if (gradingFailure != null) {
                    throw gradingFailure;
                }
                boolean bad = user.contains("BAD ANSWER");
                return reply(gradeJson(bad ? 15 : 90, bad ? "INCORRECT" : "CORRECT", bad ? "EASIER" : "HARDER"),
                        GRADING_TOKENS - 100, 100);
            }
            if (system.contains("summarizing a completed mock technical interview")) {
                calls.add(new Call(CallKind.NARRATIVE, system, user));
                return reply(narrative, NARRATIVE_TOKENS - 100, 100);
            }
            throw new IllegalStateException("Unexpected prompt: " + system);
        }

        List<Call> calls(CallKind kind) {
            return calls.stream().filter(c -> c.kind() == kind).toList();
        }

        private void awaitGate() {
            CountDownLatch latch = gate;
            if (latch != null) {
                try {
                    if (!latch.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate never opened");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    static ChatResponse reply(String text, int promptTokens, int completionTokens) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(promptTokens, completionTokens)).build())
                .build();
    }

    private static String textOf(Prompt prompt, MessageType type) {
        StringBuilder sb = new StringBuilder();
        for (Message m : prompt.getInstructions()) {
            if (m.getMessageType() == type && m.getText() != null) {
                sb.append(m.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    private static final String[] DIFFICULTIES = {"EASY", "MEDIUM", "HARD"};

    /** 3 valid MCQs + 3 valid conceptual questions with prompts unique to call n, followed by
     *  three that validation must drop (3 options, CODING type, duplicate prompt). */
    static String validBatch(int n) {
        List<Map<String, Object>> questions = new ArrayList<>();
        for (int j = 0; j < 3; j++) {
            questions.add(mcqItem("Call %d MCQ %d: which ACID property is this?".formatted(n, j), DIFFICULTIES[j], 1));
            questions.add(conceptualItem("Call %d conceptual %d: explain isolation.".formatted(n, j), DIFFICULTIES[j], 1));
        }
        Map<String, Object> threeOptions = mcqItem("INVALID three options " + n, "EASY", 1);
        threeOptions.put("options", List.of("a", "b", "c"));
        questions.add(threeOptions);
        Map<String, Object> coding = conceptualItem("INVALID coding " + n, "EASY", 1);
        coding.put("type", "CODING");
        questions.add(coding);
        questions.add(conceptualItem("Call %d conceptual 0: explain isolation.".formatted(n), "HARD", 1));
        return json(Map.of("questions", questions));
    }

    /** Only questions validation drops. */
    static String invalidBatch(int n) {
        Map<String, Object> noOptions = mcqItem("INVALID no options " + n, "EASY", 1);
        noOptions.remove("options");
        Map<String, Object> noReference = conceptualItem("INVALID no reference " + n, "EASY", 1);
        noReference.remove("referenceAnswer");
        return json(Map.of("questions", List.of(noOptions, noReference)));
    }

    static Map<String, Object> mcqItem(String prompt, String difficulty, int source) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "MCQ");
        q.put("difficulty", difficulty);
        q.put("prompt", prompt);
        q.put("options", List.of("Atomicity", "Consistency", "Isolation", "Durability"));
        q.put("correctOptionIndex", 2);
        q.put("explanation", "Isolation keeps concurrent transactions apart.");
        q.put("sourceNumber", source);
        return q;
    }

    static Map<String, Object> conceptualItem(String prompt, String difficulty, int source) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "CONCEPTUAL");
        q.put("difficulty", difficulty);
        q.put("prompt", prompt);
        q.put("referenceAnswer", "Concurrent transactions don't see each other's intermediate states.");
        q.put("sourceNumber", source);
        return q;
    }

    static String gradeJson(int score, String correctness, String next) {
        return json(Map.of("score", score, "correctness", correctness, "feedback", "Graded against the material.",
                "strengths", List.of("Clear"), "weaknesses", List.of(), "recommendedNextDifficulty", next));
    }

    static String narrativeJson(List<String> strong, List<String> weak, String summary) {
        return json(Map.of("strongTopics", strong, "weakTopics", weak, "narrativeSummary", summary));
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static void awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 20s");
            }
            Thread.sleep(25);
        }
    }
}
