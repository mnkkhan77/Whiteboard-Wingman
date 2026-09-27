package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.StaticQuestionEntry;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.QuestionType;
import com.mockinterview.backend.entity.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaticQuestionBankServiceTest {

    @Mock private TopicCatalogService topicCatalogService;

    private StaticQuestionBankService bank;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        bank = new StaticQuestionBankService(topicCatalogService);
        // @Value isn't processed outside a Spring context, so the cache path would be null (NPE in
        // loadBank) — point it at an empty temp dir: no cache file, and nothing leaks between runs.
        ReflectionTestUtils.setField(bank, "mcqBankCachePath", tempDir.resolve("mcq-bank-cache.json").toString());
        bank.loadBank(); // normally triggered by @PostConstruct; called directly here (same package)
    }

    @Test
    void refreshingRemoteBanksIsANoOpWhenTheCatalogHasNoRemoteTopics() {
        when(topicCatalogService.all()).thenReturn(List.of());

        bank.refreshRemoteBanks();

        assertThat(bank.countByType(Topic.DSA, QuestionType.CODING)).isEqualTo(6);
    }

    @Test
    void picksAQuestionMatchingTheExactRequestedDifficulty() {
        StaticQuestionEntry entry = bank.pickNext(Topic.DSA, Difficulty.EASY, QuestionType.CODING, Set.of());
        assertThat(entry.difficulty()).isEqualTo(Difficulty.EASY);
    }

    @Test
    void onlyReturnsQuestionsOfTheRequestedType() {
        StaticQuestionEntry entry = bank.pickNext(Topic.DSA, Difficulty.EASY, QuestionType.MCQ, Set.of());
        assertThat(entry.questionType()).isEqualTo(QuestionType.MCQ);
    }

    @Test
    void doesNotRepeatAQuestionAlreadyUsedInTheSession() {
        Set<String> used = new HashSet<>();
        StaticQuestionEntry first = bank.pickNext(Topic.DSA, Difficulty.EASY, QuestionType.CODING, used);
        used.add(first.id());

        StaticQuestionEntry second = bank.pickNext(Topic.DSA, Difficulty.EASY, QuestionType.CODING, used);

        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void fallsBackToAnyUnusedQuestionWhenTheExactDifficultyIsExhausted() {
        Set<String> used = new HashSet<>();
        // Exhaust every EASY CODING question for DSA.
        while (true) {
            try {
                StaticQuestionEntry entry = bank.pickNext(Topic.DSA, Difficulty.EASY, QuestionType.CODING, used);
                if (entry.difficulty() != Difficulty.EASY) break; // fallback already kicked in
                used.add(entry.id());
            } catch (IllegalStateException e) {
                throw new AssertionError("Bank exhausted entirely before the EASY-only pool ran out", e);
            }
        }
        // Reaching here means pickNext returned a non-EASY question once EASY was exhausted,
        // rather than throwing — i.e. the fallback-to-any-unused path worked.
    }

    @Test
    void throwsWhenTheEntireTopicAndTypeIsExhausted() {
        Set<String> used = new HashSet<>();
        assertThatThrownBy(() -> {
            // DSA has 6 CODING questions (see dsa.json) — 20 draws guarantees exhaustion.
            for (int i = 0; i < 20; i++) {
                StaticQuestionEntry entry = bank.pickNext(Topic.DSA, Difficulty.EASY, QuestionType.CODING, used);
                used.add(entry.id());
            }
        }).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void countByTypeReflectsTheJsonContent() {
        assertThat(bank.countByType(Topic.DSA, QuestionType.MCQ)).isEqualTo(2);
        assertThat(bank.countByType(Topic.DSA, QuestionType.CODING)).isEqualTo(6);
        assertThat(bank.countByType(Topic.SPRING, QuestionType.CODING)).isEqualTo(0);
    }
}
