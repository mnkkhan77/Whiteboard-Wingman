package com.mockinterview.backend.service;

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
 * The course prompts, from resource templates (prompts/pack-course-*.st), in the same style as the
 * quiz bank's and the flashcard deck's: the chunks go into the user turn fenced in numbered
 * &lt;source&gt; tags (ChatPromptBuilder.formatSources), which the system prompt says are data,
 * never instructions — the prompt-injection guard for document text.
 */
@Component
public class PackCoursePromptBuilder {

    private final String outlineSystem;
    private final PromptTemplate outlineUser;
    private final String lessonSystem;
    private final PromptTemplate lessonUser;

    public PackCoursePromptBuilder(@Value("classpath:prompts/pack-course-outline-system.st") Resource outlineSystem,
                                   @Value("classpath:prompts/pack-course-outline-user.st") Resource outlineUser,
                                   @Value("classpath:prompts/pack-course-lesson-system.st") Resource lessonSystem,
                                   @Value("classpath:prompts/pack-course-lesson-user.st") Resource lessonUser) {
        this.outlineSystem = read(outlineSystem);
        this.outlineUser = PromptTemplate.builder().resource(outlineUser).build();
        this.lessonSystem = read(lessonSystem);
        this.lessonUser = PromptTemplate.builder().resource(lessonUser).build();
    }

    public String outlineSystem() {
        return outlineSystem;
    }

    public String outlineUser(List<SourceChunk> chunks, int targetModules, int lessonsPerModule) {
        return render(outlineUser, Map.of(
                "sources", formatSources(chunks),
                "targetModules", targetModules,
                "lessonsPerModule", lessonsPerModule));
    }

    public String lessonSystem() {
        return lessonSystem;
    }

    public String lessonUser(String title, String summary, List<SourceChunk> chunks) {
        return render(lessonUser, Map.of(
                "title", title,
                "summary", summary,
                "sources", formatSources(chunks)));
    }

    /** Sources are numbered 1..n in list order (no sourceNumber comes back from the model here —
     *  a lesson's chunk range is assigned programmatically, not by anything the LLM picks). */
    private static String formatSources(List<SourceChunk> chunks) {
        List<RetrievedChunk> numbered = new ArrayList<>(chunks.size());
        for (SourceChunk c : chunks) {
            numbered.add(new RetrievedChunk(numbered.size() + 1, c.text(), c.page(), c.pageEnd(), c.section()));
        }
        return ChatPromptBuilder.formatSources(numbered);
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
            throw new UncheckedIOException("Missing pack course prompt " + resource.getDescription(), e);
        }
    }
}
