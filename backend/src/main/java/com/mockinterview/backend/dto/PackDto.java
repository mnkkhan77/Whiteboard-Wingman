package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.CourseStatus;
import com.mockinterview.backend.entity.FlashcardStatus;
import com.mockinterview.backend.entity.QuizStatus;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;

import java.time.LocalDateTime;

/** Study Pack as returned by /api/packs (docs/study-packs-contract.md "PackDto", plus the question
 *  bank fields from "Quiz from a pack", the deck fields from "Flashcards from a pack" and the
 *  outline fields from "Course from a pack"). Storage paths and owner ids stay server-side. */
public record PackDto(
        Long id,
        String title,
        String fileName,
        StudyPackStatus status,
        long sizeBytes,
        Integer pageCount,
        Integer chunkCount,
        String parser,
        Boolean ocrUsed,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        QuizStatus quizStatus,
        int quizQuestionCount,
        String quizErrorMessage,
        FlashcardStatus flashcardStatus,
        int flashcardCount,
        String flashcardErrorMessage,
        CourseStatus courseStatus,
        int courseLessonCount,
        String courseErrorMessage
) {
    public static PackDto from(StudyPack pack) {
        return new PackDto(
                pack.getId(), pack.getTitle(), pack.getFileName(), pack.getStatus(), pack.getSizeBytes(),
                pack.getPageCount(), pack.getChunkCount(), pack.getParser(), pack.getOcrUsed(),
                pack.getErrorCode(), pack.getErrorMessage(), pack.getCreatedAt(), pack.getUpdatedAt(),
                pack.getQuizStatus(), pack.getQuizQuestionCount(), pack.getQuizErrorMessage(),
                pack.getFlashcardStatus(), pack.getFlashcardCount(), pack.getFlashcardErrorMessage(),
                pack.getCourseStatus(), pack.getCourseLessonCount(), pack.getCourseErrorMessage());
    }
}
