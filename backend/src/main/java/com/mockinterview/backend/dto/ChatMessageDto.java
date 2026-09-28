package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.ChatRole;
import com.mockinterview.backend.entity.PackChatMessage;

import java.time.LocalDateTime;
import java.util.List;

/** A pack chat history entry (docs/study-packs-contract.md "ChatMessageDto"). sources and
 *  citedSources are empty arrays on USER messages. */
public record ChatMessageDto(
        Long id,
        ChatRole role,
        String content,
        List<ChatSourceDto> sources,
        List<Integer> citedSources,
        LocalDateTime createdAt
) {
    public static ChatMessageDto from(PackChatMessage message) {
        return new ChatMessageDto(message.getId(), message.getRole(), message.getContent(),
                message.getSources() == null ? List.of() : message.getSources(),
                message.getCitedSources() == null ? List.of() : message.getCitedSources(),
                message.getCreatedAt());
    }
}
