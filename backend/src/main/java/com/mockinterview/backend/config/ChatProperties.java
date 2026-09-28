package com.mockinterview.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.chat.* — retrieval and prompt knobs for "chat with a pack".
 *
 * @param topK                how many chunks to retrieve per question (each becomes a numbered source)
 * @param similarityThreshold minimum cosine similarity (1 - cosine distance) for a chunk to count as
 *                            a source; below it the question is treated as not covered by the
 *                            document. Calibrated against the local all-MiniLM-L6-v2 model — see the
 *                            comment next to the value in application.yml
 * @param historyTurns        previous question/answer pairs included in the prompt, for follow-ups
 * @param maxMessageChars     upper bound for one question, after trimming
 */
@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(Integer topK, Double similarityThreshold, Integer historyTurns, Integer maxMessageChars) {

    public ChatProperties {
        topK = topK == null ? 6 : topK;
        similarityThreshold = similarityThreshold == null ? 0.3 : similarityThreshold;
        historyTurns = historyTurns == null ? 3 : historyTurns;
        maxMessageChars = maxMessageChars == null ? 2000 : maxMessageChars;
    }
}
