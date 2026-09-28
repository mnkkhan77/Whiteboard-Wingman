package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.ChatRole;
import com.mockinterview.backend.entity.PackChatMessage;
import com.mockinterview.backend.service.PackRetrievalService.RetrievedChunk;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
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
 * Builds the pack chat prompt from two resource templates (prompts/pack-chat-*.st): the fixed
 * system instructions, then the recent history turns, then the current question with its
 * numbered sources. Sources go into the user turn, fenced in &lt;source&gt; tags the system prompt
 * tells the model to treat as data — the prompt-injection guard.
 */
@Component
public class ChatPromptBuilder {

    private final String systemPrompt;
    private final PromptTemplate userTemplate;

    public ChatPromptBuilder(@Value("classpath:prompts/pack-chat-system.st") Resource system,
                             @Value("classpath:prompts/pack-chat-user.st") Resource user) {
        try {
            this.systemPrompt = system.getContentAsString(StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing pack chat system prompt", e);
        }
        this.userTemplate = PromptTemplate.builder().resource(user).build();
    }

    /** @param history oldest first; ASSISTANT turns are sent without their old [n] markers */
    public Prompt build(List<RetrievedChunk> sources, List<PackChatMessage> history, String question) {
        List<Message> messages = new ArrayList<>(history.size() + 2);
        messages.add(new SystemMessage(systemPrompt));
        for (PackChatMessage turn : history) {
            messages.add(turn.getRole() == ChatRole.USER
                    ? new UserMessage(turn.getContent())
                    : new AssistantMessage(CitationParser.stripMarkers(turn.getContent())));
        }
        // StringTemplate writes every newline (values included) as the OS line separator; normalize
        // so the prompt — and its token count — is the same on Windows dev machines and Linux.
        String userTurn = userTemplate.render(Map.of("sources", formatSources(sources), "question", question))
                .replace("\r\n", "\n");
        messages.add(new UserMessage(userTurn));
        return new Prompt(messages);
    }

    static String formatSources(List<RetrievedChunk> sources) {
        StringBuilder sb = new StringBuilder();
        for (RetrievedChunk source : sources) {
            sb.append("<source n=\"").append(source.n()).append('"');
            String pages = pages(source);
            if (pages != null) {
                sb.append(" pages=\"").append(pages).append('"');
            }
            if (source.section() != null && !source.section().isBlank()) {
                sb.append(" section=\"").append(source.section().replace("\"", "'")).append('"');
            }
            // A chunk containing a literal closing tag must not be able to end its own fence.
            sb.append(">\n").append(source.text().replace("</source", "</ source")).append("\n</source>\n\n");
        }
        return sb.toString().strip();
    }

    private static String pages(RetrievedChunk source) {
        if (source.page() == null) {
            return null;
        }
        return source.pageEnd() == null || source.pageEnd().equals(source.page())
                ? String.valueOf(source.page()) : source.page() + "-" + source.pageEnd();
    }
}
