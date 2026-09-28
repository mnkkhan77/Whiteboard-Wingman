package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.Answer;
import com.mockinterview.backend.entity.Question;
import com.mockinterview.backend.service.PackQuizChunkReader.SourceChunk;
import com.mockinterview.backend.service.PackRetrievalService.RetrievedChunk;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The quiz prompts, from resource templates (prompts/pack-quiz-*.st) in the same style as pack chat:
 * - generation: the chunks go into the user turn fenced in numbered &lt;source&gt; tags (the very
 *   format pack chat uses — ChatPromptBuilder.formatSources), which the system prompt says are
 *   data, never instructions: the prompt-injection guard for document text;
 * - grounded grading: the question, the bank's reference answer with its page/section, and the
 *   student's answer fenced in &lt;student_answer&gt; so it can't pose as instructions either.
 */
@Component
public class PackQuizPromptBuilder {

    private final String generationSystem;
    private final PromptTemplate generationUser;
    private final String gradingSystem;
    private final PromptTemplate gradingUser;

    public PackQuizPromptBuilder(@Value("classpath:prompts/pack-quiz-generate-system.st") Resource generationSystem,
                                 @Value("classpath:prompts/pack-quiz-generate-user.st") Resource generationUser,
                                 @Value("classpath:prompts/pack-quiz-grade-system.st") Resource gradingSystem,
                                 @Value("classpath:prompts/pack-quiz-grade-user.st") Resource gradingUser) {
        this.generationSystem = read(generationSystem);
        this.generationUser = PromptTemplate.builder().resource(generationUser).build();
        this.gradingSystem = read(gradingSystem);
        this.gradingUser = PromptTemplate.builder().resource(gradingUser).build();
    }

    public String generationSystem() {
        return generationSystem;
    }

    /** Sources are numbered 1..n in list order — the numbers the model's sourceNumber refers to. */
    public String generationUser(List<SourceChunk> chunks, int mcqCount, int conceptualCount) {
        List<RetrievedChunk> numbered = new ArrayList<>(chunks.size());
        for (SourceChunk c : chunks) {
            numbered.add(new RetrievedChunk(numbered.size() + 1, c.text(), c.page(), c.pageEnd(), c.section()));
        }
        return render(generationUser, Map.of(
                "sources", ChatPromptBuilder.formatSources(numbered),
                "mcqCount", mcqCount,
                "conceptualCount", conceptualCount));
    }

    public String gradingSystem() {
        return gradingSystem;
    }

    public String gradingUser(Question question, Answer answer) {
        String reference = question.sourceReference();
        StringBuilder studentAnswer = new StringBuilder(orEmpty(answer.getAnswerText()));
        if (answer.getCodeSubmission() != null && !answer.getCodeSubmission().isBlank()) {
            studentAnswer.append("\n\n").append(answer.getCodeSubmission());
        }
        return render(gradingUser, Map.of(
                "difficulty", question.getDifficulty().name(),
                "question", question.getPromptText(),
                "source", reference != null ? reference : "source location not recorded",
                "referenceAnswer", orEmpty(question.getReferenceAnswer()),
                // The fence must not be closable from inside the answer.
                "answer", studentAnswer.toString().replace("</student_answer", "</ student_answer")));
    }

    /** StringTemplate writes newlines as the OS separator; normalize so prompts (and their token
     *  counts) are identical on Windows and Linux — as ChatPromptBuilder does. */
    private static String render(PromptTemplate template, Map<String, Object> values) {
        return template.render(values).replace("\r\n", "\n");
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing pack quiz prompt " + resource.getDescription(), e);
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
