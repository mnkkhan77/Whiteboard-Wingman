package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.ChatSourceDto;
import com.mockinterview.backend.dto.ChatUsageDto;
import com.mockinterview.backend.entity.ChatRole;
import com.mockinterview.backend.entity.PackChatMessage;
import com.mockinterview.backend.repository.PackChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persistence of pack chat history. Every method is its own short transaction — none is ever
 *  held open across an answer stream. Callers have already checked pack ownership. */
@Service
@RequiredArgsConstructor
public class PackChatHistoryService {

    private final PackChatMessageRepository repository;

    /** The latest {@code limit} messages, oldest first. */
    @Transactional(readOnly = true)
    public List<PackChatMessage> recent(Long packId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<PackChatMessage> newestFirst = new ArrayList<>(
                repository.findByPackIdOrderByCreatedAtDescIdDesc(packId, Limit.of(limit)));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** Saves a question and its complete answer together (never a partial answer); returns the
     *  assistant message. */
    @Transactional
    public PackChatMessage saveExchange(Long packId, String question, LocalDateTime askedAt, String answer,
                                        List<ChatSourceDto> sources, List<Integer> citedSources,
                                        ChatUsageDto usage) {
        PackChatMessage userMessage = new PackChatMessage();
        userMessage.setPackId(packId);
        userMessage.setRole(ChatRole.USER);
        userMessage.setContent(question);
        userMessage.setCreatedAt(askedAt);
        repository.save(userMessage);

        PackChatMessage assistant = new PackChatMessage();
        assistant.setPackId(packId);
        assistant.setRole(ChatRole.ASSISTANT);
        assistant.setContent(answer);
        assistant.setSources(new ArrayList<>(sources));
        assistant.setCitedSources(new ArrayList<>(citedSources));
        assistant.setPromptTokens(usage.promptTokens());
        assistant.setCompletionTokens(usage.completionTokens());
        // Never before the question, even if the clock ticked backwards in between.
        LocalDateTime now = LocalDateTime.now();
        assistant.setCreatedAt(now.isBefore(askedAt) ? askedAt : now);
        return repository.save(assistant);
    }

    @Transactional
    public void clear(Long packId) {
        repository.deleteByPackId(packId);
    }
}
