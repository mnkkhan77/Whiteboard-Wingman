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
 * The flashcard-generation prompt, from resource templates (prompts/pack-flashcard-generate-*.st),
 * in the same style as the quiz bank's (PackQuizPromptBuilder): the chunks go into the user turn
 * fenced in numbered &lt;source&gt; tags (ChatPromptBuilder.formatSources), which the system prompt
 * says are data, never instructions — the prompt-injection guard for document text.
 */
@Component
public class PackFlashcardPromptBuilder {

    private final String generationSystem;
    private final PromptTemplate generationUser;

    public PackFlashcardPromptBuilder(@Value("classpath:prompts/pack-flashcard-generate-system.st") Resource generationSystem,
                                      @Value("classpath:prompts/pack-flashcard-generate-user.st") Resource generationUser) {
        this.generationSystem = read(generationSystem);
        this.generationUser = PromptTemplate.builder().resource(generationUser).build();
    }

    public String generationSystem() {
        return generationSystem;
    }

    /** Sources are numbered 1..n in list order — the numbers the model's sourceNumber refers to. */
    public String generationUser(List<SourceChunk> chunks, int cardCount) {
        List<RetrievedChunk> numbered = new ArrayList<>(chunks.size());
        for (SourceChunk c : chunks) {
            numbered.add(new RetrievedChunk(numbered.size() + 1, c.text(), c.page(), c.pageEnd(), c.section()));
        }
        return render(generationUser, Map.of(
                "sources", ChatPromptBuilder.formatSources(numbered),
                "cardCount", cardCount));
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
            throw new UncheckedIOException("Missing pack flashcard prompt " + resource.getDescription(), e);
        }
    }
}
