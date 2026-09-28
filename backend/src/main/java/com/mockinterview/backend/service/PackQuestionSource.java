package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.Answer;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.InterviewSession;
import com.mockinterview.backend.entity.PackQuizQuestion;
import com.mockinterview.backend.entity.Question;
import com.mockinterview.backend.entity.QuestionType;
import com.mockinterview.backend.entity.Topic;
import com.mockinterview.backend.repository.PackQuizQuestionRepository;
import com.mockinterview.backend.repository.PackQuizQuestionRepository.TypeCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Questions for a study-pack quiz, from the pack's generated bank (docs/study-packs-contract.md
 * "Quiz from a pack"):
 * - plan: questionCount n is split into CONCEPTUAL = ceil(n/2), then MCQ = floor(n/2), each capped
 *   by what the bank has — a section the bank can't fill is simply shorter (or skipped);
 * - next: the unused bank question of that type closest to the session's current difficulty;
 * - grading: grounded in the bank's reference answer and its page/section.
 *
 * The plan reads the bank's live counts, so a bank regenerated (or a pack deleted) mid-session
 * just shortens the remaining sections; questions already asked are snapshots on their Question
 * rows and keep grading normally.
 */
@Component
@RequiredArgsConstructor
public class PackQuestionSource implements QuestionSource {

    /** Prefix for Question.sourceChunkId, which the session's no-repeat check compares. */
    static final String SOURCE_ID_PREFIX = "packq-";

    private final PackQuizQuestionRepository bankRepository;
    private final PackQuizPromptBuilder promptBuilder;

    @Override
    public SectionPlan plan(InterviewSession session, Topic topic) {
        if (session.getPackId() == null) {
            return SectionPlan.EMPTY; // pack deleted: nothing more to ask
        }
        long conceptual = 0;
        long mcq = 0;
        for (TypeCount count : bankRepository.countByType(session.getPackId())) {
            if (count.type() == QuestionType.CONCEPTUAL) {
                conceptual = count.count();
            } else if (count.type() == QuestionType.MCQ) {
                mcq = count.count();
            }
        }
        return split(session.getTargetQuestionCount(), conceptual, mcq);
    }

    /** The contract's split, capped by availability; empty sections are left out of the plan. */
    static SectionPlan split(int questionCount, long conceptualAvailable, long mcqAvailable) {
        int conceptual = (int) Math.min((questionCount + 1) / 2, conceptualAvailable);
        int mcq = (int) Math.min(questionCount / 2, mcqAvailable);
        List<SectionPlan.Section> sections = new ArrayList<>(2);
        if (conceptual > 0) {
            sections.add(new SectionPlan.Section(QuestionType.CONCEPTUAL, conceptual));
        }
        if (mcq > 0) {
            sections.add(new SectionPlan.Section(QuestionType.MCQ, mcq));
        }
        return new SectionPlan(sections);
    }

    @Override
    public Optional<QuestionDraft> next(InterviewSession session, QuestionType section, Set<String> usedSourceIds) {
        if (session.getPackId() == null) {
            return Optional.empty();
        }
        return closest(bankRepository.findByPackIdAndQuestionTypeOrderByIdAsc(session.getPackId(), section),
                session.getCurrentDifficulty(), usedSourceIds)
                .map(PackQuestionSource::toDraft);
    }

    /** Closest difficulty to {@code current} among the unused ones; ties go to the earlier bank
     *  question (bank order follows the document, so a quiz tends to walk through it in order). */
    static Optional<PackQuizQuestion> closest(List<PackQuizQuestion> candidates, Difficulty current, Set<String> used) {
        return candidates.stream()
                .filter(q -> !used.contains(sourceId(q)))
                .min(Comparator.comparingInt((PackQuizQuestion q) -> Math.abs(q.getDifficulty().ordinal() - current.ordinal()))
                        .thenComparing(PackQuizQuestion::getId));
    }

    static String sourceId(PackQuizQuestion q) {
        return SOURCE_ID_PREFIX + q.getId();
    }

    private static QuestionDraft toDraft(PackQuizQuestion q) {
        return new QuestionDraft(sourceId(q), q.getDifficulty(), q.getQuestionType(), q.getPrompt(),
                q.getOptions(), q.getCorrectOptionIndex(), q.getExplanation(), null, null,
                q.getReferenceAnswer(), q.getSourcePage(), q.getSourceSection());
    }

    @Override
    public GradingPrompt gradingPrompt(Question question, Answer answer) {
        return new GradingPrompt(promptBuilder.gradingSystem(), promptBuilder.gradingUser(question, answer));
    }
}
