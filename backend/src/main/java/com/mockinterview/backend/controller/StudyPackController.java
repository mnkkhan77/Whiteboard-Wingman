package com.mockinterview.backend.controller;

import com.mockinterview.backend.dto.ChatHistoryDto;
import com.mockinterview.backend.dto.ChatRequest;
import com.mockinterview.backend.dto.ChatStreamEvent;
import com.mockinterview.backend.dto.CourseDto;
import com.mockinterview.backend.dto.CourseLessonCompletionRequest;
import com.mockinterview.backend.dto.CourseLessonDto;
import com.mockinterview.backend.dto.FlashcardDeckDto;
import com.mockinterview.backend.dto.FlashcardReviewRequest;
import com.mockinterview.backend.dto.PackDto;
import com.mockinterview.backend.dto.PackFlashcardDto;
import com.mockinterview.backend.dto.PackLimitsDto;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.UserRepository;
import com.mockinterview.backend.service.PackChatService;
import com.mockinterview.backend.service.PackCourseService;
import com.mockinterview.backend.service.PackFlashcardService;
import com.mockinterview.backend.service.PackQuizService;
import com.mockinterview.backend.service.StudyPackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

/** Study Packs REST API (docs/study-packs-contract.md "REST API", "Chat with a pack" and "Quiz from
 *  a pack"). Every route is authenticated (SecurityConfig) and scoped to the caller's own packs.
 *  A quiz itself is started through the existing POST /api/sessions with a packId. */
@RestController
@RequestMapping("/api/packs")
@RequiredArgsConstructor
public class StudyPackController {

    private final StudyPackService studyPackService;
    private final PackChatService packChatService;
    private final PackQuizService packQuizService;
    private final PackFlashcardService packFlashcardService;
    private final PackCourseService packCourseService;
    private final UserRepository userRepository;

    // Literal path segment, resolved before the sibling "/{id}" mapping — no routing collision.
    @GetMapping("/limits")
    public PackLimitsDto limits(Authentication auth) {
        return studyPackService.limits(resolveUser(auth));
    }

    @GetMapping
    public List<PackDto> list(Authentication auth) {
        return studyPackService.list(resolveUser(auth));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PackDto upload(@RequestParam("file") MultipartFile file,
                          @RequestParam(value = "title", required = false) String title,
                          Authentication auth) {
        return studyPackService.upload(resolveUser(auth), file, title);
    }

    @GetMapping("/{id}")
    public PackDto get(@PathVariable Long id, Authentication auth) {
        return studyPackService.get(resolveUser(auth), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication auth) {
        studyPackService.delete(resolveUser(auth), id);
    }

    @GetMapping("/{id}/chat")
    public ChatHistoryDto chatHistory(@PathVariable Long id, Authentication auth) {
        return packChatService.history(resolveUser(auth), id);
    }

    /**
     * Streams the answer as SSE. Returning a Flux (rather than managing an SseEmitter by hand) lets
     * Spring MVC do the bridging: it subscribes, writes each ServerSentEvent as it arrives, and
     * cancels the subscription — and with it the LLM call — when the client disconnects or the
     * async request times out. Refusals are thrown before the Flux exists, so they stay JSON errors.
     */
    @PostMapping(value = "/{id}/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> chat(@PathVariable Long id, @Valid @RequestBody ChatRequest request,
                                                       Authentication auth) {
        return packChatService.chat(resolveUser(auth), id, request.message())
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder(event).event(event.eventName()).build());
    }

    @DeleteMapping("/{id}/chat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearChat(@PathVariable Long id, Authentication auth) {
        packChatService.clearHistory(resolveUser(auth), id);
    }

    /** 202: the bank is written in the background; poll GET /api/packs/{id} for quizStatus. */
    @PostMapping("/{id}/quiz/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PackDto generateQuiz(@PathVariable Long id, Authentication auth) {
        return packQuizService.requestGeneration(resolveUser(auth), id);
    }

    /** 202: the deck is written in the background; poll GET /api/packs/{id} for flashcardStatus. */
    @PostMapping("/{id}/flashcards/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PackDto generateFlashcards(@PathVariable Long id, Authentication auth) {
        return packFlashcardService.requestGeneration(resolveUser(auth), id);
    }

    @GetMapping("/{id}/flashcards")
    public FlashcardDeckDto flashcards(@PathVariable Long id, Authentication auth) {
        return packFlashcardService.deck(resolveUser(auth), id);
    }

    @PostMapping(value = "/{id}/flashcards/{cardId}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PackFlashcardDto reviewFlashcard(@PathVariable Long id, @PathVariable Long cardId,
                                            @Valid @RequestBody FlashcardReviewRequest request, Authentication auth) {
        return packFlashcardService.review(resolveUser(auth), id, cardId, request.quality());
    }

    /** 202: the outline is written in the background; poll GET /api/packs/{id} for courseStatus. */
    @PostMapping("/{id}/course/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PackDto generateCourse(@PathVariable Long id, Authentication auth) {
        return packCourseService.requestGeneration(resolveUser(auth), id);
    }

    @GetMapping("/{id}/course")
    public CourseDto course(@PathVariable Long id, Authentication auth) {
        return packCourseService.course(resolveUser(auth), id);
    }

    /** Generates and caches the lesson's content the first time it's opened. */
    @GetMapping("/{id}/course/lessons/{lessonId}")
    public CourseLessonDto courseLesson(@PathVariable Long id, @PathVariable Long lessonId, Authentication auth) {
        return packCourseService.lesson(resolveUser(auth), id, lessonId);
    }

    @PutMapping(value = "/{id}/course/lessons/{lessonId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CourseLessonDto completeCourseLesson(@PathVariable Long id, @PathVariable Long lessonId,
                                                @Valid @RequestBody CourseLessonCompletionRequest request,
                                                Authentication auth) {
        return packCourseService.setCompleted(resolveUser(auth), id, lessonId, request.completed());
    }

    private User resolveUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }
}
