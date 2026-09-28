package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.PackRef;
import com.mockinterview.backend.entity.InterviewSession;
import com.mockinterview.backend.repository.StudyPackRepository;
import com.mockinterview.backend.repository.StudyPackRepository.PackTitle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the packId/packTitle shown next to pack-quiz sessions. Lists (session history,
 * progress) fetch every title in ONE query ({@link #titles}), never per session; handbook-only
 * lists and single handbook sessions issue no query at all.
 */
@Component
@RequiredArgsConstructor
public class PackTitleLookup {

    private final StudyPackRepository studyPackRepository;

    /** packId -> title for every pack session in the list; titles of deleted packs are simply absent. */
    public Map<Long, String> titles(Collection<InterviewSession> sessions) {
        Set<Long> packIds = sessions.stream().map(InterviewSession::getPackId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (packIds.isEmpty()) {
            return Map.of();
        }
        return studyPackRepository.findTitlesByIdIn(packIds).stream()
                .collect(Collectors.toMap(PackTitle::id, PackTitle::title));
    }

    public static PackRef ref(InterviewSession session, Map<Long, String> titles) {
        return session.getPackId() == null ? PackRef.NONE : new PackRef(session.getPackId(), titles.get(session.getPackId()));
    }

    public PackRef ref(InterviewSession session) {
        return session.getPackId() == null ? PackRef.NONE : ref(session, titles(List.of(session)));
    }
}
