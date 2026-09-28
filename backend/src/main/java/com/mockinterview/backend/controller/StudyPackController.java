package com.mockinterview.backend.controller;

import com.mockinterview.backend.dto.ChatHistoryDto;
import com.mockinterview.backend.dto.ChatRequest;
import com.mockinterview.backend.dto.ChatStreamEvent;
import com.mockinterview.backend.dto.PackDto;
import com.mockinterview.backend.dto.PackLimitsDto;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.UserRepository;
import com.mockinterview.backend.service.PackChatService;
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

/** Study Packs REST API (docs/study-packs-contract.md "REST API" and "Chat with a pack"). Every
 *  route is authenticated (SecurityConfig) and scoped to the caller's own packs. */
@RestController
@RequestMapping("/api/packs")
@RequiredArgsConstructor
public class StudyPackController {

    private final StudyPackService studyPackService;
    private final PackChatService packChatService;
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

    private User resolveUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }
}
